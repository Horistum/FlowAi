"""Operator-owned transport and evidence encoding for the existing ConfigMap profile."""
import base64
from datetime import datetime, timezone
import http.client
from pathlib import Path
import re
import ssl
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit

from authoring_host.contracts import MAX_BYTES, canonical, digest, loads, require
from authoring_host.inventory_provenance import read_regular

OWNER = "horistum.com/owner"
BINDING = "horistum.com/dispatch-binding"
PRECONDITION = b"Horistum operation precondition\0v1\0"
OUTCOME = b"Horistum operation observation\0v1\0"


def now():
    return datetime.now(timezone.utc)


def json_file(path):
    return loads(read_regular(path, MAX_BYTES))


def frame():
    data = sys.stdin.buffer.readline(MAX_BYTES + 1)
    require(data.endswith(b"\n"), "Incomplete protocol frame")
    return loads(data)


def emit(value):
    sys.stdout.buffer.write(canonical(value))
    sys.stdout.buffer.flush()


def binding(request, node, checked_at, decision_sha):
    return digest(canonical(dict(resource=request["resource"], nodeId=node, checkedAt=checked_at,
        dispatchId=request["dispatchId"], runtimeId=request["runtimeId"], graphDigest=request["graphDigest"],
        requestSha256=request["requestSha256"], decisionSha256=decision_sha)))


def sign(config, domain, source, **fields):
    payload = canonical(dict(formatVersion=1, status="SUCCEEDED", sourceSizeBytes=len(source),
                             sourceSha256=digest(source), **fields))
    key_id = config["keyId"].encode("ascii")
    with tempfile.TemporaryDirectory() as tmp:
        message = Path(tmp) / "message"
        message.write_bytes(domain + len(key_id).to_bytes(4, "big") + key_id + payload)
        key = Path(tmp) / "key"
        key.touch(mode=0o600)
        key.write_bytes(read_regular(config["signingKey"], 4096, private=True))
        sig = subprocess.run(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(key), "-in", str(message)],
                             capture_output=True, check=True, timeout=5).stdout
    def encode(data):
        return base64.b64encode(data).decode("ascii")
    return dict(keyId=config["keyId"], payload=encode(payload), signature=encode(sig), source=encode(source))


class ConfigMapAPI:
    """A verified TLS endpoint, one named resource and one operator-provisioned credential."""
    def __init__(self, config):
        self.config = config
        endpoint = urlsplit(config["server"])
        require(endpoint.scheme == "https" and endpoint.hostname and not endpoint.username and
                not endpoint.password and endpoint.path in ("", "/") and not endpoint.query and
                not endpoint.fragment, "Expected a fixed HTTPS API origin")
        self.host, self.port = endpoint.hostname, endpoint.port or 443
        self.tls = ssl.create_default_context(cadata=read_regular(config["caFile"], 65536).decode("ascii"))
        namespace, name = config["namespace"], config["name"]
        label = r"[a-z0-9](?:[-a-z0-9]*[a-z0-9])?"
        require(len(namespace) <= 63 and re.fullmatch(label, namespace) and len(name) <= 253 and
                all(len(part) <= 63 and re.fullmatch(label, part) for part in name.split(".")), "Invalid object address")
        self.path = f"/api/v1/namespaces/{namespace}/configmaps/{name}"
        self.resource = dict(scope=config["cluster"] + "/" + namespace, type="configmap", key=name)

    def call(self, method, document=None):
        token = read_regular(self.config["tokenFile"], 16384, private=True).decode("ascii").strip()
        require(token and not any(ch.isspace() for ch in token), "Invalid API credential")
        conn = http.client.HTTPSConnection(self.host, self.port, context=self.tls, timeout=5)
        try:
            conn.request(method, self.path, body=canonical(document) if document is not None else None,
                         headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
            response = conn.getresponse()
            data = response.read(MAX_BYTES + 1)
            return response.status, response.getheader("Audit-ID"), loads(data)
        finally:
            conn.close()

    def matches(self, obj):
        meta = obj.get("metadata", {})
        return (obj.get("kind") == "ConfigMap" and obj.get("apiVersion") == "v1" and
                meta.get("namespace") == self.config["namespace"] and meta.get("name") == self.config["name"] and
                bool(meta.get("uid")) and bool(meta.get("resourceVersion")) and not meta.get("deletionTimestamp"))
