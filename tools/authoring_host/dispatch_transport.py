"""Bounded duplex process transport for one external runtime assessment exchange."""
from __future__ import annotations

import os
import selectors
import signal
import subprocess
import tempfile
import time

from .contracts import MAX_BYTES, canonical, loads, require


class RuntimeSession:
    """JSON-line frames, one elapsed deadline, bounded combined I/O, no shell.

    The external runtime must abort uncommitted work on EOF or timeout. Killing
    this process group cannot undo work it already committed; callers must retain
    their durable reservation even when a response is missing.
    """

    def __init__(self, argv, timeout, environment):
        self.argv, self.timeout, self.environment = argv, timeout, environment
        self.process = None
        self.selector = selectors.DefaultSelector()
        self.buffer = bytearray()
        self.total = 0
        self.stdout_open = True

    def __enter__(self):
        self.temp = tempfile.TemporaryDirectory(prefix="flow-dispatch-")
        self.deadline = time.monotonic() + self.timeout
        try:
            self.process = subprocess.Popen(self.argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, cwd=self.temp.name, start_new_session=True,
                env={"PATH": os.defpath, "LANG": "C.UTF-8", "HOME": self.temp.name, **self.environment})
            for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
                os.set_blocking(stream.fileno(), False)
            for stream in (self.process.stdout, self.process.stderr):
                self.selector.register(stream, selectors.EVENT_READ)
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def remaining(self):
        remaining = self.deadline - time.monotonic()
        require(remaining > 0, "External runtime exchange timed out")
        return remaining

    def _count(self, size):
        self.total += size
        require(self.total <= 6 * MAX_BYTES, "External runtime exchange exceeds byte limit")

    def _pump(self):
        events = self.selector.select(min(self.remaining(), 0.1))
        writable = False
        for key, _ in events:
            if key.fileobj is self.process.stdin:
                writable = True
                continue
            chunk = os.read(key.fd, 65536)
            if not chunk:
                self.selector.unregister(key.fileobj)
                if key.fileobj is self.process.stdout:
                    self.stdout_open = False
                continue
            self._count(len(chunk))
            if key.fileobj is self.process.stdout:
                self.buffer.extend(chunk)
                require(len(self.buffer) <= MAX_BYTES, "External runtime frame exceeds byte limit")
        return writable

    def send(self, frame):
        data = canonical(frame)
        require(len(data) <= MAX_BYTES, "External runtime request exceeds byte limit")
        self._count(len(data))
        self.selector.register(self.process.stdin, selectors.EVENT_WRITE)
        try:
            offset = 0
            while offset < len(data):
                if self._pump():
                    try:
                        offset += os.write(self.process.stdin.fileno(), data[offset:offset + 65536])
                    except BlockingIOError:
                        continue
        finally:
            self.selector.unregister(self.process.stdin)

    def receive(self):
        while b"\n" not in self.buffer:
            require(self.stdout_open, "External runtime ended without a complete frame")
            self._pump()
        self.remaining()
        data, _, remaining = self.buffer.partition(b"\n")
        self.buffer = bytearray(remaining)
        return loads(data)

    def finish(self):
        self.process.stdin.close()
        while self.selector.get_map():
            self._pump()
        require(not self.buffer, "External runtime emitted trailing data")
        self.process.wait(timeout=self.remaining())
        require(self.process.returncode == 0, "External runtime exited unsuccessfully")

    def __exit__(self, *unused):
        if self.process is not None:
            try:
                os.killpg(self.process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            self.process.wait()
            for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
                stream.close()
        self.selector.close()
        self.temp.cleanup()
