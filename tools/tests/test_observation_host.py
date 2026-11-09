import base64
import copy
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
from authoring_host.contracts import HostError, canonical, digest
from observation_host import journal, session

START = "2020-01-01T00:00:00Z"
END = "2020-01-01T00:00:01Z"
CLOSED = "2020-01-01T00:00:02Z"
FINAL = "2020-01-01T00:00:03Z"


class ObservationHostTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.keys = tempfile.TemporaryDirectory()
        cls.key = Path(cls.keys.name) / "observer.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(cls.key)], check=True, capture_output=True)
        cls.public = subprocess.check_output(["openssl", "pkey", "-in", str(cls.key), "-pubout", "-outform", "DER"])

    @classmethod
    def tearDownClass(cls):
        cls.keys.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / "journal"
        self.resource = {"scope": "isolated-test", "type": "generic-resource", "key": "one"}
        self.req = dict(formatVersion=2, graphDigest="a" * 64, runId="run-test", challenge="b" * 32, startedAt=START,
                        execution={"workflow": "main"}, resources=[{"identity": self.resource}])
        self.requirements = canonical(self.req)
        self.review = dict(formatVersion=2, graphDigest=self.req["graphDigest"], runId=self.req["runId"],
                           state="UNRESOLVED", executionAuthorized=False, requirementsSource={"sha256": digest(self.requirements)},
                           results=[{"id": "probe", "nodeId": "operation", "resource": self.resource}], execution={"invocationTargets": [
                               {"nodeId": "operation", "capability": "VERIFY"}, {"nodeId": "repair", "capability": "RESTORE"}]})
        self.tip = journal._create(self.directory, "proposal", b"{}", self.requirements, canonical(self.review), "f" * 64)
        self.trust = dict(formatVersion=2, grants=[dict(keyId="observer", publicKey=base64.b64encode(self.public).decode(),
                          scopes=["isolated-test"], resourceTypes=["generic-resource"], kinds=["RESULT", "STATE", "OWNERSHIP", "CREATION"], workflows=["main"])])

    def event(self, kind, data, capture=b"", event_id=None, tip=None):
        return journal.event_for(tip or self.tip, event_id or "event-" + str(self.tip["sequence"] + 1), kind, data, capture)

    def append(self, kind, data, capture=b"", event_id=None):
        event = self.event(kind, data, capture, event_id)
        self.tip = journal.append(self.directory, canonical(event), capture)
        return event

    def start(self, invocation="first", **extra):
        return self.append("START", dict(invocationId=invocation, nodeId="operation", attempt=1, startedAt=START, **extra))

    def finish(self, status="SUCCEEDED", **extra):
        return self.append("FINISH", dict(invocationId="first", completedAt=END, status=status, **extra), b"actual-result")

    def close(self):
        return self.append("CLOSE", dict(completedAt=CLOSED, status="SUCCEEDED"), b"observer-closure")

    def claim(self, kind="RESULT", **extra):
        return dict(id="claim", kind=kind, status="SUCCEEDED", nodeId="operation", invocationId="first",
                    resource=self.resource, observedAt=END, **extra)

    def rejected(self, kind, data, capture=b"capture"):
        with self.assertRaises(HostError):
            journal.append(self.directory, canonical(self.event(kind, data, capture)), capture)
        self.assertEqual(self.tip, journal.snapshot(self.directory, self.tip)["checkpoint"])

    def compiler_review(self, cli, source_kind, directory, observations=False):
        value = copy.deepcopy(self.review)
        bundle = json.loads((Path(directory) / "observations.json").read_bytes())
        value["state"] = "SATISFIED" if "execution" in bundle else "UNRESOLVED"
        (Path(directory) / "review").mkdir()
        return canonical(value), 0 if value["state"] == "SATISFIED" else 3

    def export(self, name="export", **kwargs):
        with patch.object(session, "fingerprint", return_value="f" * 64), patch.object(session, "_review", side_effect=self.compiler_review):
            return session.export(self.directory, self.tip, "/trusted/compiler", canonical(kwargs.get("trust", self.trust)),
                                  kwargs.get("key", self.key), "observer", self.root / name)

    def child(self, script, *args):
        return subprocess.Popen([sys.executable, "-c", script, str(self.directory), *map(str, args)],
                                env={**os.environ, "PYTHONPATH": str(TOOLS)}, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_starts_and_results_survive_reopening_without_inventing_closure(self):
        self.start()
        current = journal.snapshot(self.directory, self.tip)
        self.assertEqual("PENDING_INVOCATION", current["state"])
        self.assertEqual(["first"], current["pendingInvocations"])
        self.finish()
        current = journal.snapshot(self.directory, self.tip)
        self.assertEqual("OPEN", current["state"])
        self.assertEqual("SUCCEEDED", current["completedInvocations"][0]["status"])
        self.assertFalse(current["executionAuthorized"])

    def test_identical_retry_returns_original_receipt_without_duplicate_attempt(self):
        event = self.start()
        receipt = self.tip
        self.finish()
        self.assertEqual(receipt, journal.append(self.directory, canonical(event)))
        self.assertEqual(2, journal.snapshot(self.directory)["checkpoint"]["sequence"])
        changed = copy.deepcopy(event); changed["data"]["attempt"] = 2
        with self.assertRaises(HostError):
            journal.append(self.directory, canonical(changed))

    def test_stale_and_foreign_contexts_do_not_append_or_rebase(self):
        initial = self.tip
        self.start()
        for old in [initial, dict(self.tip, sessionId="c" * 64), dict(self.tip, digest="d" * 64)]:
            with self.subTest(old=old), self.assertRaises(HostError):
                event = self.event("FINISH", dict(invocationId="first", completedAt=END, status="SUCCEEDED"), b"capture", "other", old)
                journal.append(self.directory, canonical(event), b"capture")
        self.assertEqual(1, journal.snapshot(self.directory)["checkpoint"]["sequence"])

    def test_unknown_targets_and_attempt_bounds_are_rejected(self):
        for node, attempt in [("missing", 1), ("operation", 0), ("operation", 101), ("operation", True)]:
            self.rejected("START", dict(invocationId="first", nodeId=node, attempt=attempt, startedAt=START))

    def test_unmatched_start_prevents_concurrency_closure_and_unknown_finish(self):
        self.start()
        self.rejected("START", dict(invocationId="second", nodeId="operation", attempt=1, startedAt=END))
        self.rejected("CLOSE", dict(completedAt=CLOSED, status="SUCCEEDED"))
        self.rejected("FINISH", dict(invocationId="first", completedAt=END, status="UNKNOWN"))
        self.rejected("FINISH", dict(invocationId="other", completedAt=END, status="SUCCEEDED"))
        self.rejected("FINISH", dict(invocationId="first", completedAt=END, status="FAILED"))

    def test_failure_cause_is_preserved_and_must_identify_recorded_failure(self):
        self.start(); self.finish("FAILED", errorId="original-error")
        self.rejected("START", dict(invocationId="handler", nodeId="repair", attempt=1, startedAt=END, causedBy="invented"))
        self.append("START", dict(invocationId="handler", nodeId="repair", attempt=1, startedAt=END, causedBy="first"))
        self.rejected("FINISH", dict(invocationId="handler", completedAt=CLOSED, status="FAILED", errorId="original-error"))
        self.append("FINISH", dict(invocationId="handler", completedAt=CLOSED, status="FAILED", errorId="handler-error"), b"second-error")
        self.append("CLOSE", dict(completedAt=FINAL, status="FAILED", errorId="handler-error"), b"closed")
        invocations = journal.snapshot(self.directory)["completedInvocations"]
        self.assertEqual(["original-error", "handler-error"], [i["errorId"] for i in invocations])
        self.assertEqual("first", invocations[1]["causedBy"])

    def test_nanosecond_order_and_future_timestamps_are_checked(self):
        self.append("START", dict(invocationId="first", nodeId="operation", attempt=1, startedAt="2020-01-01T00:00:00.123456789Z"))
        self.rejected("FINISH", dict(invocationId="first", completedAt="2020-01-01T00:00:00.123456788Z", status="SUCCEEDED"))
        self.rejected("FINISH", dict(invocationId="first", completedAt="9999-01-01T00:00:00Z", status="SUCCEEDED"))

    def test_result_claim_must_match_exact_invocation_resource_and_completion(self):
        self.start(); self.finish()
        for field, value in [("nodeId", "repair"), ("invocationId", "other"), ("resource", dict(self.resource, key="other")),
                             ("status", "FAILED"), ("observedAt", CLOSED)]:
            changed = self.claim(); changed[field] = value
            self.rejected("OBSERVATION", changed)
        self.append("OBSERVATION", self.claim(), b"result")
        self.rejected("OBSERVATION", dict(self.claim(), id="different-id"))

    def test_late_ownership_and_wrong_kind_fields_are_rejected(self):
        self.start()
        self.rejected("OBSERVATION", self.claim("OWNERSHIP", owner="owner"))
        self.rejected("OBSERVATION", self.claim("STATE", created=True))
        self.rejected("OBSERVATION", self.claim("CREATION", created="true"))
        self.append("OBSERVATION", dict(self.claim("OWNERSHIP", owner="owner"), observedAt=START), b"owned-before-start")

    def test_numeric_facts_do_not_silently_round_through_float(self):
        self.start(); self.finish()
        for facts in [{"value": 9007199254740993.1}, {"value": None}, {"value": {"nested": True}}, {"value": 10 ** 100}]:
            self.rejected("OBSERVATION", self.claim("STATE", facts=facts))
        self.append("OBSERVATION", self.claim("STATE", facts={"value": 9007199254740993}), b"integer-state")

    def test_closed_run_accepts_late_bound_claims_and_fresh_state_but_never_more_work(self):
        self.start(); self.finish(); close = self.close()
        self.rejected("START", dict(invocationId="later", nodeId="operation", attempt=2, startedAt=FINAL))
        self.append("OBSERVATION", self.claim(), b"late-delivered-result")
        self.rejected("OBSERVATION", dict(self.claim("STATE", facts={"ready": True}), id="state"))
        self.append("OBSERVATION", dict(self.claim("STATE", facts={"ready": True}), id="state", observedAt=FINAL), b"fresh-state")
        self.assertEqual("CLOSED", journal.snapshot(self.directory)["state"])
        self.assertEqual(close["sequence"], journal.append(self.directory, canonical(close), b"observer-closure")["sequence"])

    def test_late_creation_is_bound_to_the_actual_invocation_time(self):
        self.start(); self.finish(); self.close()
        self.rejected("OBSERVATION", dict(self.claim("CREATION", created=True), observedAt=FINAL))
        self.append("OBSERVATION", self.claim("CREATION", created=True), b"creation-observed-at-completion")

    def test_invalid_capture_and_duplicate_json_fields_are_rejected(self):
        event = self.event("START", dict(invocationId="first", nodeId="operation", attempt=1, startedAt=START), b"original")
        with self.assertRaises(HostError):
            journal.append(self.directory, canonical(event), b"replacement")
        duplicated = canonical(event).replace(b'"formatVersion":1', b'"formatVersion":1,"formatVersion":1')
        with self.assertRaises(HostError):
            journal.append(self.directory, duplicated, b"original")
        self.assertEqual(0, journal.snapshot(self.directory)["checkpoint"]["sequence"])

    def test_version_unknown_fields_and_limits_fail_without_a_write(self):
        event = self.event("START", dict(invocationId="first", nodeId="operation", attempt=1, startedAt=START))
        for changed in [dict(event, formatVersion=2), dict(event, unexpected=True), dict(event, sourceSizeBytes=True)]:
            with self.assertRaises(HostError):
                journal.append(self.directory, canonical(changed))
        with patch.object(journal, "MAX_TOTAL", 1), self.assertRaises(HostError):
            journal.append(self.directory, canonical(event))
        with self.assertRaises(HostError):
            journal.append(self.directory, canonical(event), bytes(65537))
        self.assertEqual(0, journal.snapshot(self.directory)["checkpoint"]["sequence"])

    def test_changed_capture_missing_event_and_metadata_are_detected(self):
        self.start(); self.finish()
        original = (self.directory / "journal.sqlite3").read_bytes()
        for sql in ["UPDATE events SET capture=x'00' WHERE sequence=2", "DELETE FROM events WHERE sequence=1",
                    "UPDATE metadata SET value=x'7b20207d' WHERE name='source'"]:
            (self.directory / "journal.sqlite3").write_bytes(original)
            with sqlite3.connect(self.directory / "journal.sqlite3") as db:
                db.execute(sql)
            with self.assertRaises(HostError):
                journal.snapshot(self.directory, self.tip)

    def test_retained_checkpoint_detects_a_restored_valid_prefix(self):
        self.start()
        prefix = (self.directory / "journal.sqlite3").read_bytes()
        self.finish()
        (self.directory / "journal.sqlite3").write_bytes(prefix)
        with self.assertRaises(HostError):
            journal.snapshot(self.directory, self.tip)
        self.assertEqual("PENDING_INVOCATION", journal.snapshot(self.directory)["state"])

    def test_uncommitted_crash_and_lost_ack_have_different_durable_results(self):
        event = self.event("START", dict(invocationId="first", nodeId="operation", attempt=1, startedAt=START))
        path = self.root / "event.json"; path.write_bytes(canonical(event))
        script = '''import os,sys
from pathlib import Path
from observation_host import journal
original=journal._commit
def crash(db):
 if sys.argv[3]=='after': original(db)
 os._exit(31)
journal._commit=crash
journal.append(sys.argv[1],Path(sys.argv[2]).read_bytes())
'''
        before = self.child(script, path, "before"); before.communicate(timeout=15)
        self.assertEqual(31, before.returncode)
        self.assertEqual(0, journal.snapshot(self.directory, self.tip)["checkpoint"]["sequence"])
        after = self.child(script, path, "after"); after.communicate(timeout=15)
        self.assertEqual(31, after.returncode)
        self.tip = journal.append(self.directory, canonical(event))
        self.assertEqual(1, self.tip["sequence"])
        self.assertEqual("PENDING_INVOCATION", journal.snapshot(self.directory, self.tip)["state"])

    def test_restart_reconciles_a_result_without_dispatching_the_operation_again(self):
        self.start()
        marker = self.root / "actual-work"; marker.write_text("one actual invocation")
        # Simulate the observer dying after external work and before receiving its finish event.
        self.assertEqual("PENDING_INVOCATION", journal.snapshot(self.directory, self.tip)["state"])
        self.finish()
        self.assertEqual("one actual invocation", marker.read_text())
        self.assertEqual(1, len(journal.snapshot(self.directory)["completedInvocations"]))

    def test_two_process_writers_cannot_both_extend_the_same_checkpoint(self):
        files = []
        for name in ("one", "two"):
            path = self.root / (name + ".json")
            path.write_bytes(canonical(self.event("START", dict(invocationId=name, nodeId="operation", attempt=1, startedAt=START), event_id=name)))
            files.append(path)
        processes = [self.child('''import sys
from pathlib import Path
from observation_host.journal import append
from authoring_host.contracts import HostError
try: append(sys.argv[1],Path(sys.argv[2]).read_bytes())
except HostError: sys.exit(2)
''', path) for path in files]
        for process in processes:
            process.communicate(timeout=15)
        self.assertEqual([0, 2], sorted(p.returncode for p in processes))
        self.assertEqual(1, journal.snapshot(self.directory)["checkpoint"]["sequence"])

    def test_private_storage_and_symlink_replacement_are_rejected(self):
        database = self.directory / "journal.sqlite3"
        database.chmod(0o644)
        with self.assertRaises(HostError):
            journal.snapshot(self.directory)
        database.chmod(0o600)
        saved = self.root / "saved"; database.rename(saved); database.symlink_to(saved)
        with self.assertRaises(HostError):
            journal.snapshot(self.directory)

    def test_partial_export_preserves_pending_status_and_omits_execution_assertion(self):
        self.start()
        result = self.export()
        path = Path(result["directory"])
        self.assertEqual(3, result["exitCode"])
        self.assertNotIn("execution", json.loads((path / "observations.json").read_bytes()))
        state = json.loads((path / "observer-snapshot.json").read_bytes())
        self.assertEqual(["first"], state["pendingInvocations"])
        self.assertNotIn("_history", state)

    def test_closed_export_signatures_bind_original_captures_and_exact_context(self):
        self.start(); self.finish()
        self.append("OBSERVATION", self.claim(), b"raw-observed-result")
        self.close()
        result = self.export()
        path = Path(result["directory"])
        bundle = json.loads((path / "observations.json").read_bytes())
        for envelope, is_run in [(bundle["observations"][0], False), (bundle["execution"], True)]:
            payload = base64.b64decode(envelope["payload"]); source = base64.b64decode(envelope["source"])
            value = json.loads(payload)
            self.assertEqual(digest(source), value["sourceSha256"])
            self.assertEqual(digest(self.requirements), value["contractSha256"])
            domain = b"Horistum operation run observation\0v2\0" if is_run else b"Horistum operation observation\0v2\0"
            message = self.root / "message"; message.write_bytes(domain + (8).to_bytes(4, "big") + b"observer" + payload)
            signature = self.root / "signature"; signature.write_bytes(base64.b64decode(envelope["signature"]))
            public = self.root / "public.der"; public.write_bytes(self.public)
            check = subprocess.run(["openssl", "pkeyutl", "-verify", "-pubin", "-inkey", str(public), "-keyform", "DER", "-rawin",
                                    "-in", str(message), "-sigfile", str(signature)], capture_output=True)
            self.assertEqual(0, check.returncode)
        self.assertEqual(b"raw-observed-result", base64.b64decode(bundle["observations"][0]["source"]))
        self.assertEqual(self.tip, json.loads((path / "manifest.json").read_bytes())["checkpoint"])
        self.assertFalse(any("PRIVATE KEY" in p.read_text(errors="ignore") for p in path.iterdir()))

    def test_missing_scope_or_wrong_signer_cannot_publish(self):
        for field, value in [("workflows", []), ("scopes", ["other"]), ("resourceTypes", ["other"])]:
            trust = copy.deepcopy(self.trust); trust["grants"][0][field] = value
            with self.assertRaises(HostError):
                self.export(field, trust=trust)
            self.assertFalse((self.root / field).exists())
        other = self.root / "other.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(other)], check=True, capture_output=True)
        with self.assertRaises(HostError):
            self.export("wrong-key", key=other)

    def test_compiler_drift_prevents_export(self):
        with patch.object(session, "fingerprint", return_value="e" * 64), self.assertRaises(HostError):
            session.export(self.directory, self.tip, "/compiler", canonical(self.trust), self.key, "observer", self.root / "out")
        self.assertFalse((self.root / "out").exists())

    def test_failed_compiler_review_leaves_no_published_snapshot_or_changed_journal(self):
        self.start()
        with patch.object(session, "fingerprint", return_value="f" * 64), patch.object(session, "_review", side_effect=HostError("failed")), self.assertRaises(HostError):
            session.export(self.directory, self.tip, "/compiler", canonical(self.trust), self.key, "observer", self.root / "failed")
        self.assertFalse((self.root / "failed" / "evidence").exists())
        self.assertEqual(self.tip, journal.snapshot(self.directory, self.tip)["checkpoint"])

    def test_export_never_overwrites_an_existing_destination(self):
        self.export()
        original = (self.root / "export/evidence/manifest.json").read_bytes()
        with self.assertRaises(FileExistsError):
            self.export()
        self.assertEqual(original, (self.root / "export/evidence/manifest.json").read_bytes())

    def test_public_cli_errors_never_echo_capture_or_private_input(self):
        bad = self.root / "bad.json"; bad.write_text('{"sensitive-secret":"do-not-print"}')
        result = subprocess.run([sys.executable, str(TOOLS / "observe.py"), "append", "--journal", str(self.directory), "--event", str(bad)], capture_output=True)
        self.assertEqual(2, result.returncode)
        self.assertNotIn(b"sensitive-secret", result.stdout + result.stderr)
        self.assertIn(b"OBSERVER_INPUT_OR_STORAGE_REJECTED", result.stderr)


if __name__ == "__main__":
    unittest.main()
