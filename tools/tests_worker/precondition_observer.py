"""Test-only process observer: read actual local state and sign fresh snapshots.

This peer receives addresses and assessment pins, never expected facts, owners or
instance IDs. It is not a production observer or evidence of live model reasoning.
"""
import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def snapshot_bundle(request, source, key, mode="fresh"):
    """Sign actual fixture state; independent requirements are never provided."""
    assert set(request) == {"formatVersion", "contractSha256", "graphDigest", "assessmentId", "challenge", "issuedAt", "expiresAt", "operations"}
    assert all(set(op) == {"requirementId", "nodeId", "resource"} for op in request["operations"])
    actual = json.loads(source)
    signed = []
    for operation in request["operations"]:
        matches = [r for r in actual if r["resource"] == operation["resource"]]
        assert len(matches) == 1
        observed = matches[0]
        at = datetime.now(timezone.utc) - (timedelta(minutes=20) if mode == "stale" else timedelta())
        claim = dict(formatVersion=1, id=operation["requirementId"] + "-snapshot",
            **{k: request[k] for k in ("contractSha256", "graphDigest", "assessmentId", "challenge")},
            requirementId=operation["requirementId"], nodeId=operation["nodeId"], status="SUCCEEDED",
            observedAt=at.isoformat().replace("+00:00", "Z"), sourceSha256=hashlib.sha256(source).hexdigest(),
            sourceSizeBytes=len(source), **observed)
        payload = encoded(claim); key_id = b"test-observer"
        with tempfile.TemporaryDirectory() as tmp:
            message = Path(tmp) / "message"
            message.write_bytes(b"Horistum operation precondition\0v1\0" + len(key_id).to_bytes(4, "big") + key_id + payload)
            signature = subprocess.run(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(key), "-in", str(message)],
                                       capture_output=True, check=True).stdout
        signed.append(dict(keyId=key_id.decode(), payload=base64.b64encode(payload).decode(),
            signature=base64.b64encode(signature).decode(), source=base64.b64encode(source).decode()))
    return dict(formatVersion=1, observations=[] if mode == "missing" else signed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True, type=Path)
    parser.add_argument("--key", required=True, type=Path)
    parser.add_argument("--count", required=True, type=Path)
    parser.add_argument("--mode", choices=("fresh", "stale", "missing", "failed"), default="fresh")
    args = parser.parse_args()
    request = json.load(sys.stdin)
    args.count.write_text(str(int(args.count.read_text()) + 1 if args.count.exists() else 1))
    if args.mode == "failed":
        return 2
    print(json.dumps(snapshot_bundle(request, args.state.read_bytes(), args.key, args.mode)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
