#!/usr/bin/env python3
"""Installed postcondition assessment proof using ephemeral local Ed25519 test observers."""
import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from authoring_host.contracts import canonical, loads, read, require


def verify(root, cli):
    root, cli = Path(root).resolve(), Path(cli).resolve()
    corpus = root / "conformance/operation-outcomes"
    rows = []
    with tempfile.TemporaryDirectory(prefix="flow-outcomes-proof-") as tmp:
        work = Path(tmp)
        key = work / "observer.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(key)], check=True, capture_output=True)
        public = subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"], check=True, capture_output=True).stdout

        def run(args, expected):
            result = subprocess.run([str(cli), *args], cwd=root, text=True, capture_output=True, timeout=120)
            require(result.returncode == expected, f"Outcome CLI exit {result.returncode}, expected {expected}: {result.stdout[-1800:]} {result.stderr[-500:]}")

        for case in loads(read(corpus / "corpus.json"))["cases"]:
            proposal = corpus / (case + ".proposal.json")
            planned = work / (case + "-plan")
            # No model extracts or changes the independent obligations.
            run(["proposal", str(proposal), "--out", str(planned)], 0)
            graph = loads(read(planned / "proposal-review.json"))["graphDigest"]
            contract = loads(read(corpus / (case + ".requirements.json")))
            contract["graphDigest"] = graph
            requirements = work / (case + "-requirements.json")
            requirements.write_bytes(canonical(contract))
            base_args = ["outcomes", str(proposal), "--source-kind", "proposal", "--requirements", str(requirements), "--evaluated-at", "2026-11-08T02:10:00Z"]
            pending = work / (case + "-pending")
            run([*base_args, "--out", str(pending)], 3)
            skeleton = loads(read(pending / "operation-outcome-review.json"))
            require(all(r["nodeId"] for r in skeleton["results"]), "Missing canonical operation selection")
            resources = {r["id"]: r for r in contract["resources"]}
            trust = work / (case + "-trust.json")
            trust.write_bytes(canonical({"formatVersion": 1, "grants": [{"keyId": "test-observer", "publicKey": base64.b64encode(public).decode(),
                "scopes": sorted({r["identity"]["scope"] for r in resources.values()}), "resourceTypes": sorted({r["identity"]["type"] for r in resources.values()}),
                "kinds": ["RESULT", "STATE", "CREATION", "OWNERSHIP", "BINDING"]}]}))
            claims = []
            source = b"Synthetic observer capture; no external operation executed."
            for op, result in zip(contract["operations"], skeleton["results"]):
                resource = resources[op["resource"]]
                kinds = ["RESULT", "STATE"] + ({"SHARED": [], "MANAGED": ["OWNERSHIP"], "CREATED_IN_RUN": ["CREATION"]}[resource["disposition"]])
                for kind in kinds:
                    claim = dict(formatVersion=1, id=op["id"] + "-" + kind.lower(), contractSha256=hashlib.sha256(requirements.read_bytes()).hexdigest(),
                                 graphDigest=graph, runId=contract["runId"], challenge=contract["challenge"], kind=kind, status="SUCCEEDED",
                                 nodeId=result["nodeId"], resource=resource["identity"], observedAt="2026-11-08T02:09:50Z", sourceSha256=hashlib.sha256(source).hexdigest(), sourceSizeBytes=len(source))
                    if kind == "STATE":
                        claim["facts"] = op["postconditions"]
                    elif kind == "CREATION":
                        claim["created"] = True
                    elif kind == "OWNERSHIP":
                        claim["owner"] = resource["owner"]
                    claims.append(claim)

            def assess(label, values, expected, code=None, corrupt=None, clock=None):
                signed = []
                for claim in values:
                    payload = canonical(claim)
                    kid = b"test-observer"
                    signing = work / "signing.bin"
                    signing.write_bytes(b"Horistum operation observation\0v1\0" + len(kid).to_bytes(4, "big") + kid + payload)
                    sig = subprocess.run(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(key), "-in", str(signing)], check=True, capture_output=True).stdout
                    signed.append(dict(keyId=kid.decode(), payload=base64.b64encode(payload).decode(), signature=base64.b64encode(sig).decode(), source=base64.b64encode(source).decode()))
                if corrupt:
                    corrupt(signed)
                bundle = work / (case + "-" + label + ".json")
                bundle.write_bytes(canonical({"formatVersion": 1, "observations": signed}))
                output = work / (case + "-" + label)
                args = list(base_args)
                if clock:
                    args[-1] = clock
                run([*args, "--observations", str(bundle), "--trust", str(trust), "--out", str(output)], expected)
                report = loads(read(output / "operation-outcome-review.json"))
                require(report["executionAuthorized"] is False and report["workflowExecutionCorrectness"] == "unassessed", "Assessment claimed execution authority")
                require(report["state"] == {0: "SATISFIED", 3: "UNRESOLVED", 4: "VIOLATED"}[expected], "Wrong report decision")
                if code:
                    require(code in report["diagnostics"] or any(r["code"] == code for r in report["results"]), "Missing negative result: " + code)
                rows.append({"case": case, "variant": label, "state": report["state"]})

            assess("baseline", claims, 0)
            assess("result-only", [x for x in claims if x["kind"] != "STATE"], 3, "OUTCOME_OBSERVATION_MISSING")
            changed = copy.deepcopy(claims)
            next(x for x in changed if x["kind"] == "RESULT")["status"] = "FAILED"
            assess("failed-operation", changed, 4, "OUTCOME_OPERATION_FAILED")
            changed = copy.deepcopy(claims)
            state = next(x for x in changed if x["kind"] == "STATE")
            fact = next(iter(state["facts"]))
            state["facts"][fact] = "wrong-value"
            assess("wrong-postcondition", changed, 4, "OUTCOME_POSTCONDITION_FAILED")
            assess("stale", claims, 3, "OUTCOME_OBSERVATION_STALE", clock="2026-11-08T02:20:00Z")
            assess("signature", claims, 3, "OUTCOME_INVALID_SIGNATURE", corrupt=lambda items: items[0].update(signature=base64.b64encode(bytes(64)).decode()))
            assess("source", claims, 3, "OUTCOME_SOURCE_CHANGED", corrupt=lambda items: items[0].update(source=base64.b64encode(b"replaced").decode()))
            changed = copy.deepcopy(claims)
            changed[0]["runId"] = "other-run"
            assess("run-context", changed, 3, "OUTCOME_OBSERVATION_CONTEXT")
            if case == "infrastructure":
                changed = copy.deepcopy(claims)
                next(x for x in changed if x["kind"] == "CREATION")["created"] = False
                assess("adopted-existing", changed, 4, "OUTCOME_RESOURCE_NOT_CREATED")
            else:
                changed = copy.deepcopy(claims)
                next(x for x in changed if x["kind"] == "OWNERSHIP")["owner"] = "other-team"
                assess("wrong-owner", changed, 4, "OUTCOME_OWNER_MISMATCH")
        print(json.dumps({"status": "PASS", "evidenceKind": "deterministic-observer-test", "cases": rows,
                          "externalWorkExecuted": False, "liveModelEvaluated": False, "executionAuthorized": False}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(args.root, args.cli)
