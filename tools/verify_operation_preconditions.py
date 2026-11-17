#!/usr/bin/env python3
"""Installed precondition assessment against independent local JSON/SQLite state."""
import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile

from authoring_host.contracts import canonical, loads, read, require


class LocalState:
    """A bounded test provider; observation never receives expected contract values."""
    def __init__(self, folder, domain):
        self.path = folder / (domain + (".sqlite3" if domain == "data-copy" else ".json"))
        self.domain = domain
        if domain == "infrastructure":
            self.original = [
                dict(resource=dict(scope="tenant-a/subscription-test", type="network", key="temporary"),
                     instanceId="native-temporary-generation-1", owner="platform-team", facts=dict(ready=True)),
                dict(resource=dict(scope="tenant-a/subscription-test", type="network", key="shared"),
                     instanceId="native-shared-generation-1", owner="network-team", facts=dict(reachable=True))]
        elif domain == "data-copy":
            self.original = [dict(resource=dict(scope="storage-test", type="dataset", key="isolated-copy"),
                instanceId="native-copy-generation-1", owner="data-team", facts=dict(writable=True, schemaVersion=1))]
        else:
            self.original = [dict(resource=dict(scope="service-test", type="credential", key="rotation-target"),
                instanceId="native-credential-generation-1", owner="identity-team", facts=dict(active=True, version=1))]
        self.write(self.original)

    def write(self, records):
        if self.domain == "data-copy":
            with sqlite3.connect(self.path) as db:
                db.execute("CREATE TABLE IF NOT EXISTS resources (scope TEXT, type TEXT, key TEXT, instance TEXT, owner TEXT, writable INTEGER, version INTEGER)")
                db.execute("DELETE FROM resources")
                for r in records:
                    db.execute("INSERT INTO resources VALUES (?, ?, ?, ?, ?, ?, ?)",
                        (r["resource"]["scope"], r["resource"]["type"], r["resource"]["key"], r["instanceId"],
                         r["owner"], r["facts"]["writable"], r["facts"]["schemaVersion"]))
        else:
            self.path.write_bytes(canonical(records))

    def capture(self, address):
        if self.domain == "data-copy":
            with sqlite3.connect(self.path.as_uri() + "?mode=ro", uri=True) as db:
                rows = db.execute("SELECT scope, type, key, instance, owner, writable, version FROM resources WHERE scope=? AND type=? AND key=?",
                                  (address["scope"], address["type"], address["key"])).fetchall()
            require(len(rows) == 1, "Provider resource is not unique")
            source = canonical(rows)
            scope, kind, key, instance, owner, writable, version = rows[0]
            observed = dict(resource=dict(scope=scope, type=kind, key=key), instanceId=instance,
                            owner=owner, facts=dict(writable=bool(writable), schemaVersion=version))
        else:
            source = self.path.read_bytes()
            records = [r for r in loads(source) if r["resource"] == address]
            require(len(records) == 1, "Provider resource is not unique")
            observed = records[0]
        return observed, source

    def mutate(self, variant):
        records = copy.deepcopy(self.original)
        first = records[0]
        if variant == "owner":
            first["owner"] = "transferred-owner"
        elif variant == "instance":
            first["instanceId"] = "replacement-at-the-same-address"
        elif variant == "state":
            first["facts"][next(iter(first["facts"]))] = False
        else:
            raise ValueError(variant)
        if variant == "instance" and self.domain == "data-copy":
            self.path.rename(self.path.with_suffix(".original.sqlite3"))
        self.write(records)


def verify(root, cli):
    root, cli = Path(root).resolve(), Path(cli).resolve()
    corpus = root / "conformance/operation-preconditions"
    rows = []
    with tempfile.TemporaryDirectory(prefix="flow-preconditions-") as tmp:
        work = Path(tmp)
        key = work / "observer.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(key)], check=True, capture_output=True)
        public = subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"], check=True, capture_output=True).stdout
        def run(args, expected):
            result = subprocess.run([str(cli), *args], cwd=root, capture_output=True, text=True, timeout=120)
            require(result.returncode == expected,
                    f"Precondition CLI exit {result.returncode}, expected {expected}: {result.stdout[-1500:]} {result.stderr[-500:]}")

        for case in ("infrastructure", "data-copy", "credential-rotation"):
            provider = LocalState(work, case)
            proposal = corpus / (case + ".proposal.json")
            planned = work / (case + "-plan")
            run(["proposal", str(proposal), "--out", str(planned)], 0)
            graph = loads(read(planned / "proposal-review.json"))["graphDigest"]
            contract = loads(read(corpus / (case + ".requirements.json")))
            contract["graphDigest"] = graph
            requirements = work / (case + "-requirements.json")
            frozen = canonical(contract)
            requirements.write_bytes(frozen)
            base = ["preconditions", str(proposal), "--source-kind", "proposal", "--requirements", str(requirements)]
            pending = work / (case + "-pending")
            run([*base, "--evaluated-at", "2026-11-17T01:00:30Z", "--out", str(pending)], 3)
            skeleton = loads(read(pending / "operation-precondition-review.json"))
            nodes = {r["id"]: r["nodeId"] for r in skeleton["results"]}
            require(all(nodes.values()), "Missing canonical selection")
            resources = {r["id"]: r["identity"] for r in contract["resources"]}
            trust = work / (case + "-trust.json")
            trust.write_bytes(canonical(dict(formatVersion=1, grants=[dict(keyId="test-observer",
                publicKey=base64.b64encode(public).decode(), scopes=sorted({r["scope"] for r in resources.values()}),
                resourceTypes=sorted({r["type"] for r in resources.values()}))])))

            def observe():
                claims = []
                for op in contract["operations"]:
                    # Only the address is passed to the independent observer, never expected state.
                    observed, source = provider.capture(resources[op["resource"]])
                    claim = dict(formatVersion=1, id=op["id"] + "-snapshot", contractSha256=hashlib.sha256(frozen).hexdigest(),
                        graphDigest=graph, assessmentId=contract["assessmentId"], challenge=contract["challenge"],
                        requirementId=op["id"], nodeId=nodes[op["id"]], status="SUCCEEDED", observedAt="2026-11-17T01:00:20Z",
                        sourceSha256=hashlib.sha256(source).hexdigest(), sourceSizeBytes=len(source), **observed)
                    claims.append((claim, source))
                return claims

            def assess(label, claims, expected, diagnostic=None, clock="2026-11-17T01:00:30Z", corrupt=None):
                signed = []
                for claim, source in claims:
                    payload, kid = canonical(claim), b"test-observer"
                    signing = work / "signing.bin"
                    signing.write_bytes(b"Horistum operation precondition\0v1\0" + len(kid).to_bytes(4, "big") + kid + payload)
                    signature = subprocess.run(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(key), "-in", str(signing)],
                                               check=True, capture_output=True).stdout
                    signed.append(dict(keyId=kid.decode(), payload=base64.b64encode(payload).decode(),
                        signature=base64.b64encode(signature).decode(), source=base64.b64encode(source).decode()))
                if corrupt:
                    corrupt(signed)
                bundle = work / (case + "-" + label + ".json")
                bundle.write_bytes(canonical(dict(formatVersion=1, observations=signed)))
                output = work / (case + "-" + label)
                run([*base, "--observations", str(bundle), "--trust", str(trust), "--evaluated-at", clock, "--out", str(output)], expected)
                review = loads(read(output / "operation-precondition-review.json"))
                require(review["state"] == {0: "SATISFIED", 3: "UNRESOLVED", 4: "VIOLATED"}[expected], "Wrong decision")
                require(review["executionAuthorized"] is False and review["snapshotAtomicity"] == "not-established", "Unauthorized authority claim")
                require(review["validUntil"] == ("2026-11-17T01:01:20Z" if expected == 0 else None), "Wrong freshness boundary")
                require(review["requirementsSource"]["sha256"] == hashlib.sha256(frozen).hexdigest()
                        and requirements.read_bytes() == frozen, "Evidence changed independent requirements")
                if diagnostic:
                    require(diagnostic in review["diagnostics"] or any(r["code"] == diagnostic for r in review["results"]),
                            "Missing diagnostic: " + diagnostic)
                rows.append(dict(case=case, variant=label, state=review["state"]))

            baseline = observe()
            assess("baseline", baseline, 0)
            for variant, code in (("owner", "OWNER_CHANGED"), ("instance", "INSTANCE_CHANGED"), ("state", "STATE_CHANGED")):
                provider.mutate(variant)
                assess("actual-provider-" + variant, observe(), 4, "PRECONDITION_" + code)
                provider.write(provider.original)
            assess("missing", baseline[1:], 3, "PRECONDITION_OBSERVATION_MISSING")
            assess("stale", baseline, 3, "PRECONDITION_OBSERVATION_STALE", clock="2026-11-17T01:01:21Z")
            replay = [(dict(p, challenge="z" * 32), s) for p, s in baseline]
            assess("replay", replay, 3, "PRECONDITION_OBSERVATION_CONTEXT")
            assess("signature", baseline, 3, "PRECONDITION_INVALID_SIGNATURE",
                   corrupt=lambda items: items[0].update(signature=base64.b64encode(bytes(64)).decode()))
            assess("source", baseline, 3, "PRECONDITION_SOURCE_CHANGED",
                   corrupt=lambda items: items[0].update(source=base64.b64encode(b"substituted").decode()))
        print(json.dumps(dict(status="PASS", evidenceKind="local-precondition-observer-test", cases=rows,
                             workflowExecuted=False, liveModelEvaluated=False, executionAuthorized=False)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(args.root, args.cli)
