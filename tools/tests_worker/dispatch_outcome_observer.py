"""Independent test collector for committed SQLite copies. Opens the provider read-only."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile


def encoded(document):
    return (json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n").encode()


def bundle(request, database, key):
    with sqlite3.connect(database.as_uri() + "?mode=ro", uri=True) as db:
        # One read transaction correlates the audited commit with the observed
        # destination. A similarly named resource or another receipt is insufficient.
        db.execute("BEGIN")
        row = db.execute("""
            SELECT e.node_id, e.destination, e.owner_at_use, e.recorded_at, d.payload
              FROM effects AS e JOIN datasets AS d ON d.name = e.destination AND d.instance_id = e.instance_id
             WHERE e.dispatch_id = ? AND e.decision_sha = ? AND e.receipt = ?
        """, (request["dispatchId"], request["decisionSha256"], request["receiptId"])).fetchone()
        observed_at = datetime.now(timezone.utc).isoformat()
    signed = []
    if row:
        node, name, owner, recorded_at, payload = row
        resource = dict(scope="storage-test", type="dataset", key=name)
        source = encoded(dict(dispatchId=request["dispatchId"], receiptId=request["receiptId"], decisionSha256=request["decisionSha256"],
                              node=node, resource=resource, owner=owner, recordedAt=recorded_at, payload=payload, observedAt=observed_at))
        pins = {name: request[name] for name in ("graphDigest", "challenge", "runId", "contractSha256")}
        key_id = b"independent-audit"
        with tempfile.TemporaryDirectory() as tmp:
            for kind, at, fields in (("RESULT", recorded_at, {}), ("OWNERSHIP", recorded_at, dict(owner=owner)),
                                     ("STATE", observed_at, dict(facts=dict(payload=payload)))):
                claim = encoded(dict(formatVersion=1, kind=kind, id="copy-" + kind, observedAt=at, nodeId=node, resource=resource,
                                     status="SUCCEEDED", sourceSha256=hashlib.sha256(source).hexdigest(), sourceSizeBytes=len(source),
                                     **pins, **fields))
                message = Path(tmp) / "message"
                message.write_bytes(b"Horistum operation observation\0v1\0" + len(key_id).to_bytes(4, "big") + key_id + claim)
                signature = subprocess.run(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(key), "-in", str(message)],
                                           check=True, capture_output=True).stdout
                signed.append(dict(keyId=key_id.decode(), payload=base64.b64encode(claim).decode(),
                                   signature=base64.b64encode(signature).decode(), source=base64.b64encode(source).decode()))
    return dict(formatVersion=1, observations=signed)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True, type=Path)
    parser.add_argument("--key", required=True, type=Path)
    args = parser.parse_args()
    sys.stdout.buffer.write(encoded(bundle(json.load(sys.stdin), args.database, args.key)))
