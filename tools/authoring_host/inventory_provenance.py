"""Authenticated collection receipts, not an authority on remote system truth."""
from __future__ import annotations

import base64
from datetime import datetime, timedelta, timezone
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile

from .contracts import HostError, canonical, digest, integer, keys, loads, nonblank, require
from .transport import invoke

DOMAIN = b"Horistum managed inventory\0v1\0"
PUBLIC_KEY_PREFIX = bytes.fromhex("302a300506032b6570032100")
MAX_FILES = 128
MAX_TOTAL_BYTES = 262144
MAX_ATTESTATION_BYTES = 131072


def now():
    return datetime.now(timezone.utc)


def timestamp(value):
    require(isinstance(value, str) and re.fullmatch(
        r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?(?:Z|\+00:00)", value), "Inventory timestamp must be UTC")
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise HostError("Invalid inventory timestamp") from None


def sha(value):
    require(isinstance(value, str) and re.fullmatch(r"[a-f0-9]{64}", value), "Invalid inventory digest")


def decode(value, limit):
    require(isinstance(value, str) and len(value) <= 4 * ((limit + 2) // 3), "Inventory encoding exceeds limit")
    try:
        result = base64.b64decode(value, validate=True)
    except ValueError:
        raise HostError("Invalid inventory encoding") from None
    require(len(result) <= limit and base64.b64encode(result).decode() == value, "Invalid inventory encoding")
    return result


def validate_request(request):
    keys(request, ("formatVersion", "challenge", "requestSha256", "system", "scope", "issuedAt", "expiresAt"))
    require(type(request["formatVersion"]) is int and request["formatVersion"] == 1, "Unsupported inventory capture request")
    sha(request["challenge"]); sha(request["requestSha256"])
    nonblank(request["system"]); nonblank(request["scope"])
    lifetime = (timestamp(request["expiresAt"]) - timestamp(request["issuedAt"])).total_seconds()
    require(0 < lifetime <= 3600, "Inventory capture request must expire within one hour")


def validate_trust(trust):
    from .investigation import identifier
    keys(trust, ("issuerId", "keyId", "publicKey", "system", "scope"))
    identifier(trust["issuerId"]); identifier(trust["keyId"])
    nonblank(trust["system"]); nonblank(trust["scope"])
    public = decode(trust["publicKey"], 44)
    require(len(public) == 44 and public.startswith(PUBLIC_KEY_PREFIX), "Inventory trust requires an Ed25519 public key")
    return public


def validate_policy(policy):
    keys(policy, ("formatVersion", "attestationFile", "captureRequest", "trust", "maxAgeSeconds", "maxClockSkewSeconds"))
    require(type(policy["formatVersion"]) is int and policy["formatVersion"] == 1, "Unsupported inventory provenance policy")
    value = policy["attestationFile"]
    require(isinstance(value, str) and len(value) <= 4096 and Path(value).is_absolute() and
            str(Path(value)) == value and ".." not in Path(value).parts and not value.startswith("//") and "\0" not in value,
            "Inventory attestation requires a normalized absolute path")
    validate_request(policy["captureRequest"]); validate_trust(policy["trust"])
    require(all(policy["captureRequest"][k] == policy["trust"][k] for k in ("system", "scope")), "Inventory grant does not cover the capture scope")
    integer(policy["maxAgeSeconds"], 1, 3600)
    integer(policy["maxClockSkewSeconds"], 0, 60)


def validate_context(config, request):
    for root in config["roots"]:
        if "inventory" in root:
            require(root["inventory"]["captureRequest"]["requestSha256"] == digest(canonical(request)),
                    "Inventory capture belongs to another authoring request")


def read_regular(path, limit, *, private=False):
    """No symlinks, devices, FIFOs or hard links, including parent traversal."""
    from .discovery import _directory
    path = Path(path)
    descriptor = _directory(str(path.parent))
    try:
        fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
        with os.fdopen(fd, "rb") as stream:
            before = os.fstat(stream.fileno())
            require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1, "Inventory input must be a single-link regular file")
            require(not private or before.st_mode & 0o077 == 0, "Inventory signing key must be private")
            data = stream.read(limit + 1)
            after = os.fstat(stream.fileno())
        require(0 < len(data) <= limit, "Inventory input byte limit exceeded")
        require((before.st_size, before.st_mtime_ns, before.st_ctime_ns) ==
                (after.st_size, after.st_mtime_ns, after.st_ctime_ns), "Inventory input changed during read")
        return data
    finally:
        os.close(descriptor)


def message(key_id, encoded):
    key = key_id.encode("ascii")
    return DOMAIN + len(key).to_bytes(4, "big") + key + encoded


def openssl(args):
    executable = shutil.which("openssl")
    require(executable is not None, "OpenSSL is required for inventory provenance")
    result = invoke([executable, *args], b"", 15, {})
    require(not result["failure"] and result["returncode"] == 0, "Inventory signature verification failed")
    return result["stdout"]


def validate_entries(entries):
    from .discovery import relative
    require(isinstance(entries, list) and 0 < len(entries) <= MAX_FILES, "Invalid inventory file count")
    paths, total = {}, 0
    for entry in entries:
        keys(entry, ("path", "sha256", "sizeBytes"))
        parts = relative(entry["path"])
        sha(entry["sha256"]); integer(entry["sizeBytes"], 0, 65536)
        require(entry["path"] not in paths, "Duplicate inventory file")
        paths[entry["path"]] = parts
        total += entry["sizeBytes"]
    require(total <= MAX_TOTAL_BYTES, "Inventory total byte limit exceeded")
    require(not any("/".join(parts[:i]) in paths for parts in paths.values() for i in range(1, len(parts))),
            "Inventory file and directory paths conflict")


def verify_envelope(raw, policy):
    validate_policy(policy)
    require(len(raw) <= MAX_ATTESTATION_BYTES, "Inventory attestation exceeds byte limit")
    envelope = loads(raw)
    keys(envelope, ("formatVersion", "keyId", "payload", "signature"))
    require(type(envelope["formatVersion"]) is int and envelope["formatVersion"] == 1 and
            envelope["keyId"] == policy["trust"]["keyId"], "Inventory signer is not independently trusted")
    encoded = decode(envelope["payload"], 65536)
    signature = decode(envelope["signature"], 64)
    require(len(signature) == 64, "Invalid inventory signature length")
    payload = loads(encoded)
    require(canonical(payload) == encoded, "Inventory signed payload must be canonical JSON")
    keys(payload, ("formatVersion", "issuerId", "keyId", "system", "scope", "captureRequestSha256",
                   "collectionStartedAt", "collectionCompletedAt", "collectorConfigSha256", "responseSha256", "files"), ("sourceRevision",))
    require(type(payload["formatVersion"]) is int and payload["formatVersion"] == 1, "Unsupported signed inventory")
    require(all(payload[k] == policy["trust"][k] for k in ("issuerId", "keyId", "system", "scope")), "Inventory signer grant does not cover the claimed scope")
    require(payload["captureRequestSha256"] == digest(canonical(policy["captureRequest"])), "Inventory capture request mismatch")
    sha(payload["collectorConfigSha256"]); sha(payload["responseSha256"])
    if "sourceRevision" in payload:
        nonblank(payload["sourceRevision"])
    validate_entries(payload["files"])
    with tempfile.TemporaryDirectory(prefix="flow-inventory-verify-") as temp:
        directory = Path(temp)
        for name, data in {"public.der": validate_trust(policy["trust"]), "signature": signature,
                           "message": message(envelope["keyId"], encoded)}.items():
            (directory / name).write_bytes(data)
        openssl(["pkeyutl", "-verify", "-pubin", "-inkey", str(directory / "public.der"), "-keyform", "DER",
                 "-rawin", "-in", str(directory / "message"), "-sigfile", str(directory / "signature")])
    current = now()
    start, end = (timestamp(payload[k]) for k in ("collectionStartedAt", "collectionCompletedAt"))
    issued, expires = (timestamp(policy["captureRequest"][k]) for k in ("issuedAt", "expiresAt"))
    skew = policy["maxClockSkewSeconds"]
    require(start <= end and (issued - start).total_seconds() <= skew and end <= expires,
            "Inventory collection is outside its capture request window")
    require((end - current).total_seconds() <= skew and (issued - current).total_seconds() <= skew,
            "Inventory collection is in the future")
    require(current <= expires and (current - start).total_seconds() <= policy["maxAgeSeconds"], "Inventory collection expired")
    receipt = dict(attribution="trusted-collector-attestation", issuerId=payload["issuerId"], keyId=payload["keyId"],
        system=payload["system"], scope=payload["scope"], attestationSha256=digest(raw),
        trustSha256=digest(canonical(policy["trust"])), captureRequestSha256=payload["captureRequestSha256"],
        policySha256=digest(canonical(policy)), maxAgeSeconds=policy["maxAgeSeconds"], maxClockSkewSeconds=skew,
        collectionStartedAt=payload["collectionStartedAt"], collectionCompletedAt=payload["collectionCompletedAt"],
        verifiedAt=current.isoformat(), validUntil=min(expires, start + timedelta(seconds=policy["maxAgeSeconds"])).isoformat(),
        fileCount=len(payload["files"]), upstreamFreshnessEstablished=False)
    return payload, receipt


def directory_entries(payload):
    expected = {".": {}}
    for entry in payload["files"]:
        parts = entry["path"].split("/")
        for index, part in enumerate(parts):
            parent = "/".join(parts[:index]) or "."
            expected.setdefault(parent, {})[part] = "file" if index == len(parts)-1 else "directory"
    return {path: [dict(name=name, kind=kind) for name, kind in sorted(children.items())]
            for path, children in expected.items()}


def check_capture(selector, captured, ledger):
    """Compare delivered bytes directly with the immutable authenticated manifest.

    Re-reading files before/after capture alone cannot prove which bytes were delivered.
    The caller has already authenticated this retained envelope and its current policy.
    """
    raw = ledger.get("inventoryAttestations", {}).get(selector["root"])
    if raw is None:
        require(not any(r["id"] == selector["root"] and "provenance" in r for r in ledger["roots"]),
                "Managed root lost its retained inventory attestation")
        return
    payload = loads(decode(loads(raw)["payload"], 65536))
    if selector["kind"] == "read":
        entry = next((e for e in payload["files"] if e["path"] == selector["path"]), None)
        require(entry is not None and captured["sha256"] == entry["sha256"] and captured["sizeBytes"] == entry["sizeBytes"],
                "Captured bytes do not belong to the authenticated inventory")
    else:
        require(captured["entries"] == directory_entries(payload).get(selector["path"]),
                "Captured listing does not belong to the authenticated inventory")


def assess(root, config, environment):
    """Authenticate every exported file and its exact tree before exposing any value."""
    from .discovery import capture
    from .contracts import contains_credential
    raw = read_regular(root["inventory"]["attestationFile"], MAX_ATTESTATION_BYTES)
    require(not contains_credential(raw, environment), "A credential appears in inventory provenance")
    payload, receipt = verify_envelope(raw, root["inventory"])
    for entry in payload["files"]:
        observed = capture(dict(kind="read", root=root["id"], path=entry["path"]), config, environment)
        require(observed["sha256"] == entry["sha256"] and observed["sizeBytes"] == entry["sizeBytes"],
                "Inventory file differs from its attestation")
    for path, children in directory_entries(payload).items():
        observed = capture(dict(kind="list", root=root["id"], path=path), config, environment)
        require(observed["entries"] == children,
                "Inventory directory differs from its attestation")
    return raw, receipt
