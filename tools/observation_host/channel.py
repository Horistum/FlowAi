"""Bounded single-request Unix socket transport for trusted local observer services."""
from contextlib import contextmanager
import fcntl
import os
from pathlib import Path
import socket
import stat
import struct
import time

from authoring_host.contracts import HostError, canonical, keys, loads, require
from .journal import private

MAX_FRAME = 512 * 1024
TIMEOUT = 10
ERROR = dict(error="OBSERVER_SERVICE_REJECTED", executionAuthorized=False)


@contextmanager
def lock(path):
    path = Path(path).absolute()
    private(path.parent, True)
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and not info.st_mode & 0o077
                and info.st_nlink == 1, "Invalid service lock")
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        os.close(fd)


def _path(path):
    path = Path(path).absolute()
    require(len(os.fsencode(path)) <= 100, "Unix socket path is too long")
    private(path.parent, True)
    return path


def _socket_file(path):
    info = path.lstat()
    require(stat.S_ISSOCK(info.st_mode) and info.st_uid == os.getuid() and info.st_mode & 0o077 == 0, "Invalid private socket")
    return info.st_dev, info.st_ino


def _peer(stream):
    require(hasattr(socket, "SO_PEERCRED"), "Linux peer credentials are required")
    _, uid, _ = struct.unpack("3i", stream.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
    require(uid == os.getuid(), "Unexpected local service identity")


def _remaining(stream, deadline):
    remaining = deadline - time.monotonic()
    require(remaining > 0, "Service request deadline exceeded")
    stream.settimeout(remaining)


def _receive(stream, deadline):
    def exact(size):
        data = bytearray()
        while len(data) < size:
            _remaining(stream, deadline)
            part = stream.recv(size - len(data))
            require(part, "Truncated service frame")
            data.extend(part)
        return bytes(data)
    size = int.from_bytes(exact(4), "big")
    require(0 < size <= MAX_FRAME, "Service frame limit exceeded")
    return loads(exact(size))


def _send(stream, value, deadline):
    data = canonical(value)
    require(0 < len(data) <= MAX_FRAME, "Service response limit exceeded")
    _remaining(stream, deadline)
    stream.sendall(len(data).to_bytes(4, "big") + data)


def call(path, request):
    path = _path(path)
    _socket_file(path)
    deadline = time.monotonic() + TIMEOUT
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as stream:
        _remaining(stream, deadline)
        stream.connect(str(path))
        _peer(stream)
        _send(stream, request, deadline)
        result = _receive(stream, deadline)
    keys(result, ("ok", "result") if result.get("ok") is True else ("ok", "error"))
    require(result["ok"] is True, "Observer service rejected request")
    return result["result"]


def serve(path, handler):
    """One bounded request at a time. A killed process releases the persistent lock."""
    path = _path(path)
    with lock(str(path) + ".lock"):
        if path.exists() or path.is_symlink():
            _socket_file(path)
            path.unlink()
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
            old = os.umask(0o077)
            try:
                listener.bind(str(path))
            finally:
                os.umask(old)
            identity = _socket_file(path)
            listener.listen(8)
            try:
                while True:
                    stream, _ = listener.accept()
                    with stream:
                        deadline = time.monotonic() + TIMEOUT
                        try:
                            _peer(stream)
                            result = handler(_receive(stream, deadline))
                            response = dict(ok=True, result=result)
                        except (HostError, OSError, ValueError, TypeError, KeyError, AttributeError, OverflowError):
                            response = dict(ok=False, error=ERROR)
                        try:
                            _send(stream, response, deadline)
                        except (HostError, OSError):
                            pass  # A lost reply must not undo a committed event or receipt.
            finally:
                if path.exists() and _socket_file(path) == identity:
                    path.unlink()
