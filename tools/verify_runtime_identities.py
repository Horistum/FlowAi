#!/usr/bin/env python3
"""Actual post-planning allocation and provider instance capture through retained observers."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sqlite3
import tempfile
import threading
import urllib.request
import uuid

from authoring_host.contracts import canonical, loads, require


class AllocatedResource:
    """Test provider. The kernel/file allocator supplies addresses only inside create()."""
    def __init__(self, domain, root):
        self.domain, self.root = domain, root
        self.scope, self.type = "isolated-allocation-test", "local-" + domain
        self.request = dict(scope=self.scope, type=self.type)
        self.key_prefix = "http://127.0.0.1:" if domain == "service" else str(root) + "/allocated-"
        self.identity = self.last_instance = self.last_capture = None
        self.creations = self.probes = 0
        self.server = self.thread = None

    def create(self):
        require(self.identity is None and self.creations == 0, "Allocation was dispatched twice")
        self.creations += 1
        instance = str(uuid.uuid4())
        if self.domain == "service":
            class Handler(BaseHTTPRequestHandler):
                attempts = 0

                def do_GET(self):
                    if self.path == "/ready":
                        Handler.attempts += 1
                    self.send_response(503 if self.path == "/ready" and Handler.attempts == 1 else 200)
                    self.end_headers()
                    self.wfile.write(canonical(dict(instanceId=instance, owner="fixture-tenant", ready=True)))

                def log_message(self, *args):
                    pass

            self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            key = "http://127.0.0.1:" + str(self.server.server_address[1])
            self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
            self.thread.start()
        else:
            import os
            fd, key = tempfile.mkstemp(prefix="allocated-", suffix=".sqlite3", dir=self.root)
            os.close(fd)
            with sqlite3.connect(key) as connection:
                connection.execute("CREATE TABLE instance (id TEXT PRIMARY KEY, owner TEXT NOT NULL)")
                connection.execute("INSERT INTO instance VALUES (?, 'fixture-tenant')", (instance,))
                connection.execute("CREATE TRIGGER immutable_update BEFORE UPDATE ON instance BEGIN SELECT RAISE(ABORT, 'immutable instance'); END")
                connection.execute("CREATE TRIGGER immutable_delete BEFORE DELETE ON instance BEGIN SELECT RAISE(ABORT, 'immutable instance'); END")
                connection.execute("CREATE TRIGGER immutable_insert BEFORE INSERT ON instance BEGIN SELECT RAISE(ABORT, 'immutable instance'); END")
                connection.execute("CREATE TABLE records (payload TEXT NOT NULL)")
                connection.execute("INSERT INTO records VALUES ('independent-baseline')")
        self.identity = dict(self.request, key=key)
        # Capture the identity from the actual running provider, not the local UUID variable.
        return dict(handle=self.identity.copy(), **self.read_instance(self.identity))

    def read_instance(self, handle, ready=False):
        if self.domain == "service":
            with urllib.request.urlopen(handle["key"] + ("/ready" if ready else "/identity"), timeout=3) as response:
                value = loads(response.read())
        else:
            with sqlite3.connect(Path(handle["key"]).as_uri() + "?mode=ro", uri=True, timeout=0) as connection:
                rows = connection.execute("SELECT id, owner FROM instance").fetchall()
                require(len(rows) == 1, "Provider has no unique stable instance")
                value = dict(instanceId=rows[0][0], owner=rows[0][1], ready=connection.execute("SELECT payload FROM records").fetchall() == [("independent-baseline",)])
        self.last_instance = value["instanceId"]
        self.last_capture = dict(value)
        return value

    def resolve(self, handle):
        return dict(handle=dict(handle), **self.read_instance(handle))

    def probe(self, handle):
        self.probes += 1
        # Independently read the instance before the attempted operation, including its failed attempt.
        self.read_instance(handle)
        lock = None
        try:
            if self.domain == "database" and self.probes == 1:
                lock = sqlite3.connect(handle["key"])
                lock.execute("BEGIN EXCLUSIVE")
            value = self.read_instance(handle, ready=True)
            require(value["ready"], "Provider readiness failed")
            return value
        finally:
            if lock:
                lock.rollback(); lock.close()

    def close(self):
        if self.server:
            self.server.shutdown(); self.server.server_close()
            self.thread.join(timeout=3)


def verify(root, cli, deployed=True):
    from verify_resource_lineage import verify as lineage
    from verify_deployed_observer import Services
    return lineage(root, cli, deployment_factory=Services if deployed else None, allocation_factory=AllocatedResource)


def falsify(root, cli, folder, local, key, frozen_requirements):
    """Reassess independent evidence mutations without repeating the allocation operation."""
    import base64
    import copy
    import subprocess
    from observation_host.session import Signer
    baseline = folder / "late-lineage-and-creation/evidence"
    bundle = loads((baseline / "observations.json").read_bytes())
    trust = loads((baseline / "trust.json").read_bytes())
    original = [(loads(base64.b64decode(o["payload"])), base64.b64decode(o["source"])) for o in bundle["observations"]]
    requirements = loads(frozen_requirements)
    variants = []
    def change(kind, **fields):
        return [(dict(p, **fields) if p["kind"] == kind else p, capture) for p, capture in original]
    allocation = next((p, c) for p, c in original if p["kind"] == "ALLOCATION")
    variants.extend([
        ("missing-allocation", [(p, c) for p, c in original if p["kind"] != "ALLOCATION"], 3, "OUTCOME_ALLOCATION_MISSING"),
        ("unknown-instance", [(dict((k, v) for k, v in p.items() if k != "instanceId"), c) for p, c in original], 3, "OUTCOME_INSTANCE_MISSING"),
        ("missing-earlier-attempt", [(p, c) for p, c in original if p["id"] != "binding-resolve-1"], 3, "OUTCOME_RESOURCE_BINDING_MISSING_OR_AMBIGUOUS"),
        ("foreign-run", change("ALLOCATION", runId="foreign"), 3, "OUTCOME_OBSERVATION_CONTEXT"),
        ("foreign-challenge", change("ALLOCATION", challenge="z" * 32), 3, "OUTCOME_OBSERVATION_CONTEXT"),
        ("changed-requirements", change("ALLOCATION", contractSha256="0" * 64), 3, "OUTCOME_OBSERVATION_CONTEXT"),
        ("wrong-output", change("ALLOCATION", binding="invented"), 4, "OUTCOME_ALLOCATION_PRODUCER_SLOT"),
        ("wrong-path", change("ALLOCATION", path=["other"]), 4, "OUTCOME_ALLOCATION_PRODUCER_SLOT"),
        ("conflicting-allocation", original + [(dict(allocation[0], id="conflict", instanceId="other-instance"), allocation[1])], 4, "OUTCOME_ALLOCATION_CONFLICT"),
        ("replaced-instance", change("STATE", instanceId="other-instance"), 4, "OUTCOME_RESOURCE_INSTANCE_MISMATCH"),
        ("late-allocation-time", change("ALLOCATION", observedAt=next(p["observedAt"] for p, _ in original if p["kind"] == "STATE")), 3, "OUTCOME_ALLOCATION_COMPLETION"),
        ("stale-final-state", change("STATE", observedAt=allocation[0]["observedAt"]), 3, "OUTCOME_STATE_BEFORE_RUN_COMPLETION"),
        ("missing-allocation-grant", original, 3, "OUTCOME_OBSERVER_SCOPE"),
        ("authenticated-wrong-scope", change("ALLOCATION", resource=dict(local.identity, scope="other-scope")), 4, "OUTCOME_ALLOCATION_SCOPE"),
        ("authenticated-wrong-type", change("ALLOCATION", resource=dict(local.identity, type="other-type")), 4, "OUTCOME_ALLOCATION_SCOPE"),
    ])
    if local.domain == "database":
        # Replace the actual database at the same address with a different native instance record.
        address = Path(local.identity["key"])
        address.rename(address.with_suffix(".original"))
        with sqlite3.connect(address) as connection:
            connection.execute("CREATE TABLE instance (id TEXT PRIMARY KEY, owner TEXT NOT NULL)")
            connection.execute("INSERT INTO instance VALUES (?, 'fixture-tenant')", (str(uuid.uuid4()),))
            connection.execute("CREATE TABLE records (payload TEXT NOT NULL)")
            connection.execute("INSERT INTO records VALUES ('independent-baseline')")
        native = local.read_instance(local.identity)
        claims = [(dict(p, instanceId=native["instanceId"]) if p["kind"] == "STATE" else p,
                   canonical(native) if p["kind"] == "STATE" else capture) for p, capture in original]
        variants.append(("actual-database-replacement", claims, 4, "OUTCOME_RESOURCE_INSTANCE_MISMATCH"))
    rows = []
    for name, claims, code, diagnostic in variants:
        out = folder / ("falsify-" + name); out.mkdir()
        granted = copy.deepcopy(trust)
        granted["grants"][0]["scopes"].append("other-scope")
        granted["grants"][0]["resourceTypes"].append("other-type")
        signer = Signer(key, "observer", canonical(granted), requirements, claims)
        signed = []
        for claim, capture in claims:
            from authoring_host.contracts import digest
            signed.append(signer.sign(dict(claim, sourceSha256=digest(capture), sourceSizeBytes=len(capture)), capture))
        if name == "missing-allocation-grant":
            granted["grants"][0]["kinds"].remove("ALLOCATION")
        (out / "observations.json").write_bytes(canonical(dict(bundle, observations=signed)))
        (out / "trust.json").write_bytes(canonical(granted))
        result = subprocess.run([str(cli), "outcomes", str(baseline / "source.input"), "--source-kind", "proposal",
            "--requirements", str(baseline / "requirements.json"), "--observations", str(out / "observations.json"),
            "--trust", str(out / "trust.json"), "--out", str(out / "review")], cwd=root, capture_output=True, timeout=120)
        require(result.returncode == code, f"Runtime identity falsification {name} returned {result.returncode}, expected {code}")
        review = loads((out / "review/operation-outcome-review.json").read_bytes())
        require(diagnostic in review["diagnostics"] or any(r["code"] == diagnostic for r in review["results"]),
                f"Runtime identity falsification {name} lost its diagnostic")
        require((baseline / "requirements.json").read_bytes() == frozen_requirements, "Evidence changed frozen requirements")
        rows.append(dict(case=local.domain, variant=name, state=review["state"], **({"replacementExecutions": 1} if name == "actual-database-replacement" else {})))
    return rows


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument("--cli", required=True)
    parser.add_argument("--direct", action="store_true", help="Assess local allocations without the separate Unix-socket services")
    args = parser.parse_args()
    verify(args.root, args.cli, deployed=not args.direct)
