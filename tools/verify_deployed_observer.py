#!/usr/bin/env python3
"""Installed-product proof with actual collector/witness processes and worker creations."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

from authoring_host.contracts import HostError, canonical, loads, require
from observation_host import channel, deployment
from verify_resource_lineage import verify as verify_lineage


class Services:
    """Test-only lifecycle harness; no production process executes the observed work."""
    def __init__(self, root, folder, journal, genesis, direct):
        self.root, self.folder, self.journal, self.genesis, self.direct = root, folder, journal, genesis, direct
        self.runtime = tempfile.TemporaryDirectory(prefix="fo-")
        self.retention = tempfile.TemporaryDirectory(prefix="fw-")
        self.run = Path(self.runtime.name)
        self.package = self.run / "installed"
        self.package.mkdir()
        shutil.copyfile(root / "tools/observe.py", self.package / "observe.py")
        shutil.copytree(root / "tools/observation_host", self.package / "observation_host", ignore=shutil.ignore_patterns("__pycache__"))
        (self.package / "authoring_host").mkdir()
        for name in ("__init__.py", "contracts.py", "transport.py"):
            shutil.copyfile(root / "tools/authoring_host" / name, self.package / "authoring_host" / name)
        self.store = Path(self.retention.name) / "receipts"
        self.witness_socket, self.socket = self.run / "w.sock", self.run / "c.sock"
        self.genesis_path, self.marker = self.run / "genesis.json", self.run / "committed"
        self.genesis_path.write_bytes(canonical(genesis))
        self.processes = []
        self.variants = []
        try:
            self.cli("enroll-witness", "--store", self.store, "--genesis", self.genesis_path)
            self.witness = self.start("witness")
            self.collector = self.start("collector", crash=True)
        except BaseException:
            self.close()
            raise

    def cli(self, *args, expected=0):
        result = subprocess.run([sys.executable, str(self.package / "observe.py"), *map(str, args)], cwd=self.run,
                                env={**os.environ, "PYTHONPATH": str(self.package)}, capture_output=True, timeout=150)
        require(result.returncode == expected, f"Deployed observer command returned {result.returncode}, expected {expected}")
        return json.loads(result.stdout or result.stderr)

    def start(self, role, crash=False, rejected=False):
        args = (["serve-witness", "--store", str(self.store), "--socket", str(self.witness_socket)] if role == "witness" else
                ["serve", "--journal", str(self.journal), "--witness-socket", str(self.witness_socket),
                 "--genesis", str(self.genesis_path), "--socket", str(self.socket)])
        if crash:
            # Pause immediately after the genuine SQLite commit and kill this process from the harness.
            script = f'''
import os, signal
from pathlib import Path
from authoring_host.contracts import loads
from observation_host import journal
original = journal.append
def append(directory, raw, capture=b""):
    receipt = original(directory, raw, capture)
    event = loads(raw)
    if event["type"] == "FINISH" and event["data"]["invocationId"] == "create-1":
        Path({str(self.marker)!r}).write_text("committed")
        os.kill(os.getpid(), signal.SIGSTOP)
    return receipt
journal.append = append
import observe
raise SystemExit(observe.main())
'''
            argv = [sys.executable, "-c", script, *args]
        else:
            argv = [sys.executable, str(self.package / "observe.py"), *args]
        process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            cwd=self.run, env={**os.environ, "PYTHONPATH": str(self.package)})
        self.processes.append(process)
        if rejected:
            stdout, stderr = process.communicate(timeout=15)
            require(process.returncode == 2 and b"OBSERVER_INPUT_OR_STORAGE_REJECTED" in stderr and not stdout,
                    "Collector accepted a rolled back journal")
            return process
        endpoint = self.witness_socket if role == "witness" else self.socket
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            require(process.poll() is None, "Deployed observer service exited before readiness")
            try:
                channel.call(endpoint, deployment.request("STATUS", sessionId=self.genesis["sessionId"]))
                return process
            except (HostError, OSError):
                time.sleep(0.02)
        raise HostError("Deployed observer service startup deadline exceeded")

    @staticmethod
    def stop(process):
        if process.poll() is None:
            process.kill()
        process.communicate(timeout=5)

    def host(self, command, *args, expected=0):
        if command == "status":
            return self.cli("inspect", "--socket", self.socket, "--genesis", self.genesis_path, expected=expected)
        if command == "export":
            return self.cli(command, "--journal", self.journal, *args, "--witness-socket", self.witness_socket, expected=expected)
        require(command == "append", "Unexpected deployed proof command")
        options = dict(zip(args[::2], args[1::2]))
        event = loads(Path(options["--event"]).read_bytes())
        if event["type"] == "FINISH" and event["data"]["invocationId"] == "create-1" and not self.marker.exists():
            self.backup = self.folder / "pre-creation-finish.sqlite3"
            shutil.copyfile(self.journal / "journal.sqlite3", self.backup)
            with ThreadPoolExecutor(1) as pool:
                lost = pool.submit(self.cli, "send", "--socket", self.socket, *args, expected=2)
                deadline = time.monotonic() + 15
                while not self.marker.exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                require(self.marker.exists(), "Creation result did not reach the committed crash boundary")
                self.stop(self.collector)
                lost.result(timeout=5)
            self.collector = self.start("collector")
            state = self.host("status")
            require(state["retentionState"] == "REDELIVERY_REQUIRED" and
                    state["checkpoint"]["sequence"] == state["retainedCheckpoint"]["sequence"] + 1,
                    "Crash did not preserve the exact unconfirmed event")
            self.variants.append(dict(variant="collector-crash-after-creation-capture", state="REDELIVERY_REQUIRED"))
            changed = dict(event, id="substituted-finish")
            try:
                channel.call(self.socket, deployment.append_request(changed, Path(options["--capture"]).read_bytes()))
                raise AssertionError("Different pending event accepted")
            except HostError:
                pass
            receipt = self.cli("send", "--socket", self.socket, *args, expected=expected)
            require(self.host("status")["retentionState"] == "CONFIRMED", "Exact redelivery did not close retention gap")
            self.variants.append(dict(variant="same-result-redelivered", state="CONFIRMED"))
            self.stop(self.witness); self.stop(self.collector)
            self.witness = self.start("witness"); self.collector = self.start("collector")
            require(self.cli("send", "--socket", self.socket, *args) == receipt, "Process restart changed the original receipt")
            self.variants.append(dict(variant="both-services-restarted", state="CONFIRMED"))
            return receipt
        return self.cli("send", "--socket", self.socket, *args, expected=expected)

    def finish_proof(self):
        latest = self.host("status")["retainedCheckpoint"]
        self.stop(self.collector)
        shutil.copyfile(self.backup, self.journal / "journal.sqlite3")
        self.start("collector", rejected=True)
        retained = channel.call(self.witness_socket, deployment.request("STATUS", sessionId=self.genesis["sessionId"]))
        require(retained == latest, "Restoring the collector rolled back independent receipts")
        self.variants.append(dict(variant="restored-journal-prefix", state="REJECTED"))
        return self.variants

    def close(self):
        for process in self.processes:
            self.stop(process)
        self.runtime.cleanup()
        self.retention.cleanup()


def verify(root, cli):
    return verify_lineage(root, cli, deployment_factory=Services)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(args.root, args.cli)
