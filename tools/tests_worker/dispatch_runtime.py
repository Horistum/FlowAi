"""Test-only external SQLite runtime, independently authorized and transactional.

The runtime owns the implementation of its preapproved data copy. It keeps an
IMMEDIATE transaction from actual observation to conditional commit. Production
host code contains none of this operation, database, approval or locking logic.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import signal
import sqlite3
import subprocess
import sys
import time

from precondition_observer import snapshot_bundle


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n").encode()


def sha(value):
    return hashlib.sha256(encoded(value)).hexdigest()


def emit(value):
    sys.stdout.buffer.write(encoded(value)); sys.stdout.buffer.flush()


def receive():
    data = sys.stdin.buffer.readline(2_000_001)
    assert data.endswith(b"\n") and len(data) <= 2_000_000
    return json.loads(data)


def instant(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def contender(database):
    with sqlite3.connect(database, timeout=0.2) as db:
        try:
            db.execute("UPDATE datasets SET owner='concurrent-owner' WHERE name='isolated-copy'")
        except sqlite3.OperationalError as error:
            assert "locked" in str(error)
            return 0
    return 7


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True, type=Path)
    parser.add_argument("--key", type=Path)
    parser.add_argument("--count", type=Path)
    parser.add_argument("--mode", default="fresh")
    args = parser.parse_args()
    if args.mode == "contender": return contender(args.database)
    # Independently bounded lifetime: even a broken caller cannot hold this
    # transaction indefinitely. Process death rolls back uncommitted SQLite work.
    signal.alarm(110)
    with args.count.open("a") as counter:
        counter.write("invoked\n")
    request = receive()
    reply = dict(formatVersion=1, dispatchId=request["dispatchId"], requestSha256=sha(request))
    with sqlite3.connect(args.database, timeout=2) as db:
        try:
            db.execute("BEGIN IMMEDIATE")
        except sqlite3.OperationalError as error:
            if "locked" not in str(error): raise
            emit(dict(reply, kind="rejected", reason="conflict")); return 0
        stored = db.execute("SELECT document FROM authorizations WHERE reference=?", (request["authorizationRef"],)).fetchone()
        approval = json.loads(stored[0]) if stored else {}
        pins = ("dispatchId", "runtimeId", "authorizationRef", "candidateId", "proposalSha256",
                "canonicalPlanSha256", "fidelityReviewSha256", "compilerDigest")
        if any(request.get(name) != approval.get(name) for name in pins) or \
                request["preconditions"]["contractSha256"] != approval.get("contractSha256"):
            emit(dict(reply, kind="rejected", reason="authorization")); return 0
        if db.execute("SELECT 1 FROM effects WHERE dispatch_id=?", (request["dispatchId"],)).fetchone():
            emit(dict(reply, kind="rejected", reason="conflict")); return 0
        records = [dict(resource=dict(scope="storage-test", type="dataset", key=row[0]), instanceId=row[1],
            owner=row[2], facts=dict(writable=bool(row[3]), schemaVersion=row[4])) for row in db.execute(
                "SELECT name, instance_id, owner, writable, schema_version FROM datasets ORDER BY name")]
        snapshots = snapshot_bundle(request["preconditions"], encoded(records), args.key,
            args.mode if args.mode in ("missing", "stale") else "fresh")
        snapshots_text = encoded(snapshots).decode()
        emit(dict(reply, kind="observations", snapshots=snapshots_text))
        if args.mode == "concurrent":
            competitor = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--database", str(args.database),
                "--mode", "contender"], timeout=5, capture_output=True)
            assert competitor.returncode == 0, "Concurrent writer bypassed the transaction"
            (args.database.parent / "concurrent-blocked").write_text("true")
        if args.mode == "concurrent-runtime":
            competitor = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--database", str(args.database),
                "--key", str(args.key), "--count", str(args.count), "--mode", "fresh"],
                input=encoded(request), timeout=6, capture_output=True)
            assert competitor.returncode == 0
            assert json.loads(competitor.stdout) == dict(reply, kind="rejected", reason="conflict")
            (args.database.parent / "duplicate-blocked").write_text("true")
        if args.mode == "crash-before-decision": return 2
        decision = receive()
        assert decision["kind"] == "assessment" and decision["requestSha256"] == sha(request)
        assert decision["dispatchId"] == request["dispatchId"]
        assert decision["snapshotsSha256"] == hashlib.sha256(snapshots_text.encode()).hexdigest()
        assert decision["executionAuthorized"] is False
        if args.mode == "expires-before-use" and decision["currentState"] == "SATISFIED":
            time.sleep(max(0, (instant(decision["validUntil"]) - datetime.now(timezone.utc)).total_seconds()) + 0.02)
        now = datetime.now(timezone.utc)
        allowed = decision["currentState"] == "SATISFIED" and instant(decision["checkedAt"]) <= now <= instant(decision["validUntil"])
        # Authorization is independent, and remains checked in the same critical
        # section. The compiler assessment alone never supplies this permission.
        allowed = allowed and db.execute("SELECT document FROM authorizations WHERE reference=?",
                                        (request["authorizationRef"],)).fetchone() == stored
        if allowed:
            if args.mode != "false-completion":
                db.execute("UPDATE datasets SET payload=(SELECT payload FROM datasets WHERE name=?) WHERE name=?",
                           (approval["source"], approval["destination"]))
            destination = approval["destination"]
            operation = next(op for op in request["preconditions"]["operations"] if op["resource"]["key"] == destination)
            db.execute("""INSERT INTO effects
                          SELECT ?, ?, ?, ?, name, instance_id, owner, ? FROM datasets WHERE name=?""",
                       (request["dispatchId"], sha(decision), "sqlite:" + request["dispatchId"], operation["nodeId"],
                        now.isoformat(), destination))
            db.commit()
            if args.mode == "crash-after-commit": return 2
        else:
            db.rollback()
        emit(dict(reply, kind="result", decisionSha256=sha(decision),
                  status="completed" if allowed else "rejected", receiptId="sqlite:" + request["dispatchId"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
