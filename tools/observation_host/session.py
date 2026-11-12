"""Installed-compiler admission and signed snapshot publication for an external observer."""
from __future__ import annotations

import base64
import hmac
import os
from pathlib import Path
import shutil
import tempfile

from authoring_host.contracts import canonical, digest, keys, loads, require
from authoring_host.transport import compiler_environment, compiler_fingerprint, invoke
from .journal import MAX_CAPTURE, _create, identifier, private, public_snapshot, snapshot, sync_directory


def read_bytes(path, limit=2_000_000):
    with Path(path).open("rb") as stream:
        data = stream.read(limit + 1)
    require(0 < len(data) <= limit, "Observer input byte limit exceeded")
    return data


def write_bytes(path, data):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def fingerprint(cli):
    cli = Path(cli)
    require(cli.is_absolute() and cli.is_file(), "An absolute installed compiler executable is required")
    return compiler_fingerprint({"compiler": {"argv": [str(cli)]}})


def _review(cli, source_kind, directory, observations=False):
    root = Path(directory).absolute()
    args = [str(cli), "outcomes", str(root / "source.input"), "--source-kind", source_kind,
            "--requirements", str(root / "requirements.json"), "--out", str(root / "review")]
    if observations:
        args += ["--observations", str(root / "observations.json"), "--trust", str(root / "trust.json")]
    result = invoke(args, b"", 120, compiler_environment())
    require(not result["failure"] and result["returncode"] in (0, 3, 4), "Installed outcome assessment failed")
    review = read_bytes(root / "review" / "operation-outcome-review.json")
    value = loads(review)
    require(value.get("executionAuthorized") is False and value.get("formatVersion") == loads(read_bytes(root / "requirements.json"))["formatVersion"] and
            {"SATISFIED": 0, "UNRESOLVED": 3, "VIOLATED": 4}.get(value.get("state")) == result["returncode"],
            "Invalid installed outcome review")
    return review, result["returncode"]


def initialize(directory, cli, source_kind, source, requirements):
    require(source_kind in ("intent", "proposal") and loads(requirements).get("formatVersion") in (2, 3),
            "Observer requires a supported source and outcome requirements version 2 or 3")
    require(0 < len(source) <= 2_000_000 and 0 < len(requirements) <= 2_000_000, "Observer input limit exceeded")
    before = fingerprint(cli)
    with tempfile.TemporaryDirectory(prefix="flow-observer-admission-") as temporary:
        root = Path(temporary)
        write_bytes(root / "source.input", source)
        write_bytes(root / "requirements.json", requirements)
        review, code = _review(cli, source_kind, root)
        require(code == 3 and fingerprint(cli) == before, "Observer initialization needs a stable pending compiler assessment")
    return _create(directory, source_kind, source, requirements, review, before)


class Signer:
    """One independently granted Ed25519 key per export; the private key is never journaled."""
    def __init__(self, private_key, key_id, trust_bytes, requirements, claims):
        identifier(key_id)
        private(private_key)
        self.key = read_bytes(private_key, 16384)
        self.key_id = key_id
        self.version = requirements["formatVersion"]
        self.openssl = shutil.which("openssl")
        require(self.openssl is not None, "OpenSSL is required for Ed25519 signing")
        trust = loads(trust_bytes)
        keys(trust, ("formatVersion", "grants"))
        require(type(trust["formatVersion"]) is int and trust["formatVersion"] == self.version and self.version in (2, 3) and isinstance(trust["grants"], list)
                and 0 < len(trust["grants"]) <= 64, "Invalid caller-owned observer trust")
        grants = []
        for grant in trust["grants"]:
            keys(grant, ("keyId", "publicKey", "scopes", "resourceTypes", "kinds"), ("workflows",))
            identifier(grant["keyId"])
            if grant["keyId"] == key_id:
                grants.append(grant)
        require(len(grants) == 1, "Observer key has no unique independent grant")
        grant = grants[0]
        try:
            public = base64.b64decode(grant["publicKey"], validate=True)
        except (ValueError, TypeError):
            raise ValueError("Invalid observer public key") from None
        require(len(public) == 44 and public.startswith(bytes.fromhex("302a300506032b6570032100")), "An Ed25519 public key is required")
        require(requirements["execution"]["workflow"] in grant.get("workflows", []) and all(
            (r.get("identity") or r["allocation"])["scope"] in grant["scopes"] and (r.get("identity") or r["allocation"])["type"] in grant["resourceTypes"] for r in requirements["resources"]),
            "Observer grant does not cover the workflow and resources")
        require(all(c["kind"] in grant["kinds"] for c, _ in claims), "Observer grant does not cover the captured claim kinds")
        with tempfile.TemporaryDirectory(prefix="flow-observer-key-") as temporary:
            key = Path(temporary) / "key.pem"
            write_bytes(key, self.key)
            result = invoke([self.openssl, "pkey", "-in", str(key), "-passin", "pass:", "-pubout", "-outform", "DER"], b"", 15, {})
            require(not result["failure"] and result["returncode"] == 0 and hmac.compare_digest(result["stdout"], public),
                    "Signing key does not match caller-owned observer trust")

    def sign(self, payload, source, run=False):
        encoded = canonical(payload)
        require(0 < len(encoded) <= MAX_CAPTURE and 0 < len(source) <= MAX_CAPTURE, "Signed observation size exceeded")
        domain = (f"Horistum operation{' run' if run else ''} observation\0v{self.version}\0").encode("ascii")
        key_id = self.key_id.encode("ascii")
        message = domain + len(key_id).to_bytes(4, "big") + key_id + encoded
        with tempfile.TemporaryDirectory(prefix="flow-observer-sign-") as temporary:
            root = Path(temporary)
            write_bytes(root / "key.pem", self.key)
            write_bytes(root / "message", message)
            result = invoke([self.openssl, "pkeyutl", "-sign", "-inkey", str(root / "key.pem"), "-passin", "pass:", "-rawin", "-in", str(root / "message")], b"", 15, {})
            require(not result["failure"] and result["returncode"] == 0 and len(result["stdout"]) == 64, "Ed25519 observation signing failed")
        return dict(keyId=self.key_id, payload=base64.b64encode(encoded).decode(), signature=base64.b64encode(result["stdout"]).decode(),
                    source=base64.b64encode(source).decode())


def export(directory, expected, cli, trust, private_key, key_id, output):
    captured = snapshot(directory, expected)
    metadata, history = captured["_metadata"], captured["_history"]
    binding, req = loads(metadata["binding"]), loads(metadata["requirements"])
    require(fingerprint(cli) == binding["compilerFingerprint"], "Installed compiler changed since observer initialization")
    signer = Signer(private_key, key_id, trust, req, history.claims)
    common = dict(formatVersion=req["formatVersion"], contractSha256=digest(metadata["requirements"]), graphDigest=req["graphDigest"],
                  runId=req["runId"], challenge=req["challenge"])
    observations = []
    for claim, source in history.claims:
        observations.append(signer.sign(dict(common, **claim, sourceSha256=digest(source), sourceSizeBytes=len(source)), source))
    bundle = dict(formatVersion=req["formatVersion"], observations=observations)
    # No synthetic completion time or truncated run assertion is invented for open runs.
    if history.closed:
        source = canonical(dict(observerProtocolVersion=2 if req["formatVersion"] == 3 else 1, checkpoint=expected, closed=True))
        run = dict(common, id="observer-run", workflow=req["execution"]["workflow"], startedAt=req["startedAt"],
                   complete=True, invocations=history.finished, sourceSha256=digest(source), sourceSizeBytes=len(source), **history.closed)
        bundle["execution"] = signer.sign(run, source, run=True)
    encoded = canonical(bundle)
    require(len(encoded) <= 2_000_000, "Observation bundle exceeds observer publication limit")
    output = Path(output).absolute()
    output.mkdir(mode=0o700)
    private(output, True)
    # A private reserved directory prevents replacement races. Only evidence/ is a published snapshot.
    with tempfile.TemporaryDirectory(prefix="pending-", dir=output) as temporary:
        pending = Path(temporary)
        for name, value in {"source.input": metadata["source"], "requirements.json": metadata["requirements"],
                            "trust.json": trust, "observations.json": encoded,
                            "observer-snapshot.json": canonical(public_snapshot(captured))}.items():
            write_bytes(pending / name, value)
        review, code = _review(cli, binding["sourceKind"], pending, observations=True)
        require(loads(review)["graphDigest"] == req["graphDigest"] and fingerprint(cli) == binding["compilerFingerprint"],
                "Compiler or graph changed while exporting observations")
        require(history.closed is not None or code == 3, "An open observer run cannot be assessed as complete")
        # Retain only the assessment; generated plans are not observer-owned authority.
        shutil.rmtree(pending / "review")
        write_bytes(pending / "operation-outcome-review.json", review)
        inventory = {p.name: digest(read_bytes(p)) for p in pending.iterdir()}
        write_bytes(pending / "manifest.json", canonical(dict(formatVersion=1, checkpoint=expected, files=inventory)))
        sync_directory(pending)
        os.rename(pending, output / "evidence")
        sync_directory(output)
        sync_directory(output.parent)
    return dict(checkpoint=expected, state=captured["state"], outcomeState=loads(review)["state"], exitCode=code,
                executionAuthorized=False, directory=str(output / "evidence"))
