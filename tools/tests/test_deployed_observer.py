import base64
from concurrent.futures import ThreadPoolExecutor
import copy
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
from authoring_host.contracts import HostError, canonical, digest
from observation_host import channel, deployment, journal, witness

START = "2020-01-01T00:00:00Z"
END = "2020-01-01T00:00:01Z"


class DeployedObserverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="fo-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory, self.store = self.root / "journal", self.root / "retained"
        req = dict(formatVersion=2, graphDigest="a" * 64, runId="run-test", challenge="b" * 32, startedAt=START,
                   execution={"workflow": "main"}, resources=[{"identity": {"scope": "test", "type": "local", "key": "one"}}])
        requirements = canonical(req)
        review = dict(formatVersion=2, graphDigest=req["graphDigest"], runId=req["runId"], state="UNRESOLVED",
                      executionAuthorized=False, requirementsSource={"sha256": digest(requirements)},
                      results=[], execution={"invocationTargets": [{"nodeId": "create", "capability": "PROVISION"}]})
        self.genesis = journal._create(self.directory, "proposal", b"{}", requirements, canonical(review), "f" * 64)
        self.tip = self.genesis
        self.genesis_path = self.root / "genesis.json"
        self.genesis_path.write_bytes(canonical(self.genesis))
        witness.enroll(self.store, self.genesis)
        self.witness_socket, self.collector_socket = self.root / "w.sock", self.root / "c.sock"
        self.processes = []
        self.addCleanup(self.cleanup_processes)

    def cleanup_processes(self):
        for process in self.processes:
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=5)

    def launch(self, role, script=None):
        args = (["serve-witness", "--store", str(self.store), "--socket", str(self.witness_socket)] if role == "witness" else
                ["serve", "--journal", str(self.directory), "--witness-socket", str(self.witness_socket),
                 "--genesis", str(self.genesis_path), "--socket", str(self.collector_socket)])
        argv = [sys.executable, "-c", script, *args] if script else [sys.executable, str(TOOLS / "observe.py"), *args]
        process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   env={**os.environ, "PYTHONPATH": str(TOOLS)})
        self.processes.append(process)
        sock = self.witness_socket if role == "witness" else self.collector_socket
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if process.poll() is not None:
                self.fail("Service did not start: " + process.communicate()[1].decode())
            try:
                channel.call(sock, deployment.request("STATUS", sessionId=self.genesis["sessionId"]))
                return process
            except (HostError, OSError):
                time.sleep(0.02)
        self.fail("Service startup timed out")

    def services(self):
        return self.launch("witness"), self.launch("collector")

    def status(self):
        return channel.call(self.collector_socket, deployment.request("STATUS", sessionId=self.genesis["sessionId"]))

    def start(self, identifier="first"):
        return journal.event_for(self.tip, identifier, "START", dict(invocationId=identifier, nodeId="create", attempt=1, startedAt=START))

    def finish(self, capture=b"created-once"):
        return journal.event_for(self.tip, "finish", "FINISH", dict(invocationId="first", completedAt=END, status="SUCCEEDED"), capture)

    def deliver(self, event, capture=b""):
        self.tip = channel.call(self.collector_socket, deployment.append_request(event, capture))
        return self.tip

    def test_two_services_retain_original_duplicate_receipt_across_restart(self):
        w, c = self.services()
        event = self.start(); receipt = self.deliver(event)
        self.deliver(self.finish(), b"created-once")
        latest = self.tip
        for process in (w, c):
            process.kill(); process.communicate(timeout=5)
        self.services()
        self.assertEqual(receipt, self.deliver(event))
        self.assertEqual(latest, self.status()["retainedCheckpoint"])
        self.assertEqual(1, len(self.status()["completedInvocations"]))

    def test_journal_rollback_is_rejected_without_reenrollment(self):
        backup = self.root / "backup"; shutil.copytree(self.directory, backup)
        _, collector = self.services()
        self.deliver(self.start())
        collector.kill(); collector.communicate(timeout=5)
        shutil.copyfile(backup / "journal.sqlite3", self.directory / "journal.sqlite3")
        handler = deployment.Collector(self.directory, self.witness_socket, self.genesis)
        with self.assertRaises(HostError):
            handler(deployment.request("STATUS", sessionId=self.genesis["sessionId"]))
        with self.assertRaises(FileExistsError):
            witness.enroll(self.store, self.genesis)
        self.assertEqual(self.tip, witness.current(self.store, self.genesis["sessionId"]))

    def test_same_length_fork_is_rejected(self):
        backup = self.root / "backup"; shutil.copytree(self.directory, backup)
        _, collector = self.services()
        self.deliver(self.start())
        collector.kill(); collector.communicate(timeout=5)
        journal.append(backup, canonical(journal.event_for(self.genesis, "other", "START",
                       dict(invocationId="other", nodeId="create", attempt=1, startedAt=START))))
        shutil.copyfile(backup / "journal.sqlite3", self.directory / "journal.sqlite3")
        with self.assertRaises(HostError):
            deployment.Collector(self.directory, self.witness_socket, self.genesis)._inspect()

    def test_concurrent_writers_cannot_both_extend_the_tip(self):
        self.services()
        def deliver(identifier):
            try:
                return channel.call(self.collector_socket, deployment.append_request(self.start(identifier)))
            except HostError:
                return None
        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(deliver, ("first", "second")))
        self.assertEqual(1, len([r for r in results if r]))
        self.assertEqual(1, self.status()["checkpoint"]["sequence"])

    def crash_script(self, boundary):
        # Test-only process instrumentation stops at a real persistence boundary.
        marker = str(self.root / "boundary")
        return f'''
import os, signal
from pathlib import Path
from observation_host import channel, deployment
original = channel.call
def call(path, value):
    if value.get("operation") == "ADVANCE":
        if {boundary!r} == "after-witness":
            result = original(path, value)
        Path({marker!r}).write_text("committed")
        os.kill(os.getpid(), signal.SIGSTOP)
    return original(path, value)
channel.call = call
import observe
raise SystemExit(observe.main())
'''

    def assert_crash_boundary(self, boundary):
        self.launch("witness")
        process = self.launch("collector", self.crash_script(boundary))
        event = self.start()
        with ThreadPoolExecutor(1) as pool:
            future = pool.submit(channel.call, self.collector_socket, deployment.append_request(event))
            deadline = time.monotonic() + 8
            while not (self.root / "boundary").exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue((self.root / "boundary").exists())
            process.kill(); process.communicate(timeout=5)
            with self.assertRaises((HostError, OSError)):
                future.result(timeout=5)
        self.launch("collector")
        state = self.status()
        self.assertEqual(1, state["checkpoint"]["sequence"])
        self.assertEqual("REDELIVERY_REQUIRED" if boundary == "before-witness" else "CONFIRMED", state["retentionState"])
        if boundary == "before-witness":
            with self.assertRaises(HostError):
                self.deliver(self.start("conflict"))
        self.deliver(event)
        self.assertEqual("CONFIRMED", self.status()["retentionState"])
        self.assertEqual(1, self.status()["checkpoint"]["sequence"])

    def test_process_death_after_journal_commit_requires_exact_redelivery(self):
        self.assert_crash_boundary("before-witness")

    def test_process_death_after_witness_commit_preserves_acknowledgement(self):
        self.assert_crash_boundary("after-witness")

    def test_witness_unavailable_blocks_new_capture(self):
        w, _ = self.services()
        w.kill(); w.communicate(timeout=5)
        with self.assertRaises(HostError):
            self.deliver(self.start())
        self.assertEqual(self.genesis, journal.snapshot(self.directory)["checkpoint"])
        self.launch("witness")
        self.deliver(self.start())

    def test_unconfirmed_history_blocks_export_before_any_output(self):
        self.launch("witness")
        event = self.start()
        pending = journal.append(self.directory, canonical(event))
        output = self.root / "export"
        with self.assertRaises(HostError):
            deployment.confirmed_export(self.directory, pending, self.witness_socket,
                "/missing/compiler", b"{}", "/missing/key", "observer", output)
        self.assertFalse(output.exists())

    def test_witness_rejects_skips_conflicts_foreign_sessions_and_changed_duplicates(self):
        first = dict(self.genesis, sequence=1, digest="c" * 64)
        self.assertEqual(first, witness.advance(self.store, self.genesis, first))
        self.assertEqual(first, witness.advance(self.store, self.genesis, first))
        for previous, following in [(self.genesis, dict(first, digest="d" * 64)),
            (first, dict(first, sequence=3)), (first, dict(first, sequence=2, sessionId="d" * 64)),
            (dict(self.genesis, digest="e" * 64), first), (first, dict(first, sequence=True))]:
            with self.assertRaises(HostError):
                witness.advance(self.store, previous, following)
        self.assertEqual(first, witness.current(self.store, self.genesis["sessionId"]))

    def test_more_than_one_unwitnessed_event_blocks_reconciliation(self):
        self.launch("witness")
        self.tip = journal.append(self.directory, canonical(self.start()))
        journal.append(self.directory, canonical(self.finish()), b"created-once")
        with self.assertRaises(HostError):
            deployment.Collector(self.directory, self.witness_socket, self.genesis)._inspect()

    def test_missing_enrollment_does_not_create_store(self):
        missing = self.root / "missing"
        with self.assertRaises(OSError):
            witness.current(missing, self.genesis["sessionId"])
        self.assertFalse(missing.exists())

    def test_socket_and_store_symlinks_and_permissive_storage_are_rejected(self):
        self.launch("witness")
        alias = self.root / "alias.sock"; alias.symlink_to(self.witness_socket)
        with self.assertRaises(HostError):
            channel.call(alias, deployment.request("STATUS", sessionId=self.genesis["sessionId"]))
        self.store.chmod(0o755)
        with self.assertRaises(HostError):
            witness.current(self.store, self.genesis["sessionId"])

    def test_malformed_frames_never_expose_captures_or_kill_service(self):
        self.services()
        for data in (b"[]", b'{"formatVersion":1,"formatVersion":1}', b'{"private":"sensitive-marker"}'):
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as stream:
                stream.connect(str(self.collector_socket))
                stream.sendall(len(data).to_bytes(4, "big") + data)
                response = channel._receive(stream, time.monotonic() + 2)
                self.assertFalse(response["ok"])
                self.assertNotIn("sensitive-marker", json.dumps(response))
        self.assertEqual(self.genesis, self.status()["checkpoint"])

    def test_frame_limits_and_absolute_deadline(self):
        left, right = socket.socketpair()
        self.addCleanup(left.close); self.addCleanup(right.close)
        right.sendall((channel.MAX_FRAME + 1).to_bytes(4, "big"))
        with self.assertRaises(HostError):
            channel._receive(left, time.monotonic() + 1)
        right.sendall((10).to_bytes(4, "big") + b"{")
        with self.assertRaises((TimeoutError, HostError)):
            channel._receive(left, time.monotonic() + 0.05)

    def test_socket_lock_prevents_second_server_and_survives_stale_socket(self):
        original = self.launch("witness")
        with self.assertRaises(BlockingIOError):
            with channel.lock(str(self.witness_socket) + ".lock"):
                pass
        original.kill(); original.communicate(timeout=5)
        self.launch("witness")

    def test_witness_transaction_rolls_back_uncommitted_receipt(self):
        with witness.connection(self.store, True) as db:
            db.execute("INSERT INTO receipts VALUES (?, ?, ?)", (1, self.genesis["sessionId"], "d" * 64))
        self.assertEqual(self.genesis, witness.current(self.store, self.genesis["sessionId"]))

    def test_corrupt_retained_sequence_is_rejected(self):
        with witness.connection(self.store, True) as db:
            db.execute("INSERT INTO receipts VALUES (?, ?, ?)", (2, self.genesis["sessionId"], "d" * 64))
            db.commit()
        with self.assertRaises(HostError):
            witness.current(self.store, self.genesis["sessionId"])

    def test_duplicate_cannot_confirm_an_unrelated_concurrent_local_write(self):
        event = self.start()
        self.tip = journal.append(self.directory, canonical(event))
        witness.advance(self.store, self.genesis, self.tip)
        original = journal.append
        def competing_append(directory, raw, capture):
            receipt = original(directory, raw, capture)
            original(directory, canonical(self.finish()), b"created-once")
            return receipt
        with patch.object(channel, "call", return_value=self.tip), patch.object(journal, "append", side_effect=competing_append):
            with self.assertRaises(HostError):
                deployment.Collector(self.directory, self.witness_socket, self.genesis)(deployment.append_request(event))
        self.assertEqual(self.tip, witness.current(self.store, self.genesis["sessionId"]))


if __name__ == "__main__":
    unittest.main()
