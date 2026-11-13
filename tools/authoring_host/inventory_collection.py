"""One explicitly configured inventory collection; never a model-selected command."""
from __future__ import annotations

import base64
from datetime import timedelta
import os
from pathlib import Path
import re
import secrets
import tempfile

from . import inventory_provenance as provenance
from .contracts import (argv, canonical, contains_credential, digest, integer, keys, loads, nonblank,
                        require, save, checkpoint)
from .transport import invoke


def prepare(request, system, scope, lifetime_seconds=900):
    keys(request, ("userText",), ("context", "mode"))
    require(isinstance(request["userText"], str) and bool(request["userText"].strip()), "Empty original request")
    nonblank(system); nonblank(scope)
    integer(lifetime_seconds, 1, 3600)
    issued = provenance.now()
    return dict(formatVersion=1, challenge=secrets.token_hex(32), requestSha256=digest(canonical(request)),
        system=system, scope=scope, issuedAt=issued.isoformat(), expiresAt=(issued + timedelta(seconds=lifetime_seconds)).isoformat())


def collector_environment(config):
    keys(config, ("formatVersion", "argv", "credentialEnv", "timeoutSeconds"))
    require(type(config["formatVersion"]) is int and config["formatVersion"] == 1, "Unsupported inventory collector configuration")
    argv(config["argv"]); integer(config["timeoutSeconds"], 1, 120)
    names = config["credentialEnv"]
    require(isinstance(names, list) and len(names) <= 10 and all(isinstance(n, str) for n in names) and
            len(names) == len(set(names)), "Invalid inventory credential names")
    environment = {}
    for name in names:
        require(re.fullmatch(r"[A-Z][A-Z0-9_]*_(API_KEY|TOKEN|SECRET)", name) and
                not name.startswith(("GIT_", "SSH_", "CODEX_", "PYTHON", "LD_", "DYLD_")), "Forbidden inventory credential target")
        value = os.environ.get(name, "")
        require(value and "\n" not in value and "\0" not in value, "Missing or invalid inventory credential")
        environment[name] = value
    return environment


def signing_key(path, trust):
    key = provenance.read_regular(str(Path(path).absolute()), 16384, private=True)
    with tempfile.TemporaryDirectory(prefix="flow-inventory-key-") as temporary:
        file = Path(temporary) / "key.pem"
        file.write_bytes(key); file.chmod(0o600)
        public = provenance.openssl(["pkey", "-in", str(file), "-passin", "pass:", "-pubout", "-outform", "DER"])
    require(public == provenance.validate_trust(trust), "Inventory signing key does not match independent trust")
    return key


def sign(payload, key):
    encoded = canonical(payload)
    require(len(encoded) <= 65536, "Inventory signed payload exceeds byte limit")
    with tempfile.TemporaryDirectory(prefix="flow-inventory-sign-") as temporary:
        root = Path(temporary)
        (root / "key.pem").write_bytes(key); (root / "key.pem").chmod(0o600)
        (root / "message").write_bytes(provenance.message(payload["keyId"], encoded))
        signature = provenance.openssl(["pkeyutl", "-sign", "-inkey", str(root / "key.pem"), "-passin", "pass:",
                                        "-rawin", "-in", str(root / "message")])
    require(len(signature) == 64, "Invalid inventory signature")
    return canonical(dict(formatVersion=1, keyId=payload["keyId"], payload=base64.b64encode(encoded).decode(),
                          signature=base64.b64encode(signature).decode()))


def collect(request, config, trust, private_key, directory, *, max_age_seconds=900, max_clock_skew_seconds=5):
    """Record a single actual process response, sign exact bytes and publish atomically.

    The operator is responsible for configuring a read-only collector and assigning
    its least-privilege credentials. The collector never receives the signing key.
    No process failure or interrupted collection can be retried in this directory.
    """
    provenance.validate_request(request); provenance.validate_trust(trust)
    integer(max_age_seconds, 1, 3600); integer(max_clock_skew_seconds, 0, 60)
    require(all(request[k] == trust[k] for k in ("system", "scope")), "Inventory collector grant does not cover request")
    current = provenance.now()
    require(provenance.timestamp(request["issuedAt"]) <= current <= provenance.timestamp(request["expiresAt"]),
            "Inventory capture request is not current")
    environment = collector_environment(config)
    require(not contains_credential(canonical([request, config, trust]), environment), "Inventory inputs contain a credential")
    key = signing_key(private_key, trust)
    root = Path(directory).absolute()
    root.mkdir(mode=0o700, parents=False, exist_ok=False)
    fd = os.open(root.parent, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
    state = dict(formatVersion=1, status="collecting", files={}, captureRequestSha256=digest(canonical(request)))
    for name, data in {"capture-request.json": canonical(request), "collector-config.json": canonical(config),
                       "trust.json": canonical(trust)}.items():
        save(root, name, data, state["files"])
    checkpoint(root, state)  # Durable single-attempt reservation before running a collector.
    try:
        started = provenance.now()
        result = invoke(config["argv"], canonical(request), config["timeoutSeconds"], environment)
        completed = provenance.now()
        require(not result["failure"] and result["returncode"] == 0, "Inventory collector did not complete successfully")
        require(not contains_credential(result["stdout"], environment), "Inventory collector returned a credential")
        response = loads(result["stdout"])
        keys(response, ("formatVersion", "captureRequestSha256", "system", "scope", "files"), ("sourceRevision",))
        require(type(response["formatVersion"]) is int and response["formatVersion"] == 1 and
                response["captureRequestSha256"] == state["captureRequestSha256"] and
                all(response[k] == request[k] for k in ("system", "scope")), "Inventory collector response belongs to another request or scope")
        require(isinstance(response["files"], list) and 0 < len(response["files"]) <= provenance.MAX_FILES, "Invalid inventory collector file count")
        entries = []
        for file in response["files"]:
            keys(file, ("path", "text"))
            require(isinstance(file["text"], str), "Inventory collector requires UTF-8 text")
            data = file["text"].encode("utf-8")
            entries.append(dict(path=file["path"], sha256=digest(data), sizeBytes=len(data)))
        provenance.validate_entries(entries)
        files = {file["path"]: file["text"].encode("utf-8") for file in response["files"]}
        payload = dict(formatVersion=1, issuerId=trust["issuerId"], keyId=trust["keyId"], system=request["system"], scope=request["scope"],
            captureRequestSha256=state["captureRequestSha256"], collectionStartedAt=started.isoformat(),
            collectionCompletedAt=completed.isoformat(), collectorConfigSha256=digest(canonical(config)),
            responseSha256=digest(result["stdout"]), files=sorted(entries, key=lambda e: e["path"]))
        if "sourceRevision" in response:
            nonblank(response["sourceRevision"]); payload["sourceRevision"] = response["sourceRevision"]
        attestation = sign(payload, key)
        policy = dict(formatVersion=1, attestationFile=str(root / "evidence" / "attestation.json"), captureRequest=request,
                      trust=trust, maxAgeSeconds=max_age_seconds, maxClockSkewSeconds=max_clock_skew_seconds)
        provenance.verify_envelope(attestation, policy)  # Collection may have expired while the process ran.
        with tempfile.TemporaryDirectory(prefix="pending-", dir=root) as temporary:
            pending = Path(temporary)
            inventory = {}
            for name, data in files.items():
                save(pending, "inventory/" + name, data, inventory)
            for name, data in {"attestation.json": attestation, "collector-response.json": result["stdout"],
                               "inventory-policy.json": canonical(policy)}.items():
                save(pending, name, data, inventory)
            save(pending, "manifest.json", canonical(dict(formatVersion=1, files=inventory)), {})
            os.rename(pending, root / "evidence")
        state["files"].update({"evidence/" + name: value for name, value in inventory.items()})
        state["files"]["evidence/manifest.json"] = digest((root / "evidence/manifest.json").read_bytes())
        fd = os.open(root, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
        state.update(status="collected", attestationSha256=digest(attestation), completedAt=completed.isoformat())
        checkpoint(root, state)
        return dict(status="collected", inventoryRoot=str(root / "evidence" / "inventory"), inventory=policy,
                    executionAuthorized=False, upstreamFreshnessEstablished=False)
    except BaseException:
        state["status"] = "failed"
        checkpoint(root, state)
        raise
