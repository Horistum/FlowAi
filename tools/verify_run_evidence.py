#!/usr/bin/env python3
"""Installed outcome assessment using actual isolated local work, never cloud support certification."""
import argparse
import base64
import copy
from datetime import datetime, timezone
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import secrets
import socket
import sqlite3
import subprocess
import tempfile
import threading
from urllib.request import urlopen

from authoring_host.contracts import canonical, loads, read, require


def timestamp():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class LocalWork:
    """Test fixtures, deliberately outside production, adapters and capability admission."""
    def __init__(self, case, root):
        self.case, self.root = case, root
        self.server = self.thread = self.reservation = None
        if case == "service-readiness":
            self.reservation = socket.socket()
            self.reservation.bind(("127.0.0.1", 0))
            self.port = self.reservation.getsockname()[1]
        elif case == "data-recovery":
            for name, value in [("baseline.db", 42), ("live.db", 0)]:
                with sqlite3.connect(root / name) as connection:
                    connection.execute("create table records (value integer not null)")
                    connection.execute("insert into records values (?)", (value,))
        elif case == "credential-recovery":
            self.expected_key = secrets.token_bytes(32)
            self.challenge = secrets.token_bytes(32)
            (root / "credential").write_bytes(secrets.token_bytes(32))
            (root / "credential").chmod(0o600)
        else:
            raise AssertionError("Unknown test fixture")

    def inspect(self):
        if self.case == "service-readiness":
            with urlopen(f"http://127.0.0.1:{self.port}/ready", timeout=2) as response:
                ready = response.status == 200 and response.read() == b"ready"
        elif self.case == "data-recovery":
            with sqlite3.connect(self.root / "live.db") as connection:
                ready = connection.execute("select value from records").fetchall() == [(42,)]
        else:
            actual = hmac.digest((self.root / "credential").read_bytes(), self.challenge, "sha256")
            ready = hmac.compare_digest(actual, hmac.digest(self.expected_key, self.challenge, "sha256"))
        if not ready:
            raise RuntimeError("Observed local postcondition is false")
        return {"ready": True}

    def repair(self):
        if self.case == "service-readiness":
            class Handler(BaseHTTPRequestHandler):
                def do_GET(self):
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b"ready")
                def log_message(self, *_):
                    pass
            self.reservation.close()
            self.reservation = None
            self.server = HTTPServer(("127.0.0.1", self.port), Handler)
            self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
            self.thread.start()
        elif self.case == "data-recovery":
            with sqlite3.connect(self.root / "baseline.db") as source, sqlite3.connect(self.root / "live.db") as target:
                source.backup(target)
        else:
            temporary = self.root / "credential.tmp"
            temporary.write_bytes(self.expected_key)
            temporary.chmod(0o600)
            temporary.replace(self.root / "credential")
        return {"changed": True}

    def close(self):
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
            self.thread.join(timeout=5)
        if self.reservation is not None:
            self.reservation.close()


def verify(root, cli):
    root, cli = Path(root).resolve(), Path(cli).resolve()
    rows = []
    with tempfile.TemporaryDirectory(prefix="flow-run-evidence-") as temporary:
        work = Path(temporary)
        key = work / "observer.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(key)], check=True, capture_output=True)
        public = subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"], check=True, capture_output=True).stdout

        def cli_run(args, expected):
            result = subprocess.run([str(cli), *args], cwd=root, text=True, capture_output=True, timeout=120)
            require(result.returncode == expected, f"Run-evidence CLI exit {result.returncode}, expected {expected}: {result.stdout[-2200:]} {result.stderr[-500:]}")

        def sign(payload, source, is_run=False):
            encoded = canonical(payload)
            domain = b"Horistum operation run observation\0v2\0" if is_run else b"Horistum operation observation\0v2\0"
            identifier = b"test-observer"
            message = work / "signed-message"
            message.write_bytes(domain + len(identifier).to_bytes(4, "big") + identifier + encoded)
            signature = subprocess.run(["openssl", "pkeyutl", "-sign", "-inkey", str(key), "-rawin", "-in", str(message)], check=True, capture_output=True).stdout
            return dict(keyId=identifier.decode(), payload=base64.b64encode(encoded).decode(), signature=base64.b64encode(signature).decode(), source=base64.b64encode(source).decode())

        for case in loads(read(root / "conformance/run-evidence/corpus.json"))["cases"]:
            folder = work / case
            folder.mkdir()
            proposal = root / "conformance/run-evidence" / (case + ".proposal.json")
            planned = folder / "plan"
            cli_run(["proposal", str(proposal), "--out", str(planned)], 0)
            digest = loads(read(planned / "proposal-review.json"))["graphDigest"]
            contract = loads(read(root / "conformance/run-evidence" / (case + ".requirements.json")))
            contract.update(graphDigest=digest, challenge=secrets.token_urlsafe(32), startedAt=timestamp())
            requirements = folder / "requirements.json"
            requirements.write_bytes(canonical(contract))
            common = dict(formatVersion=2, contractSha256=hashlib.sha256(requirements.read_bytes()).hexdigest(), graphDigest=digest,
                          runId=contract["runId"], challenge=contract["challenge"])
            args = ["outcomes", str(proposal), "--source-kind", "proposal", "--requirements", str(requirements)]
            pending = folder / "pending"
            cli_run([*args, "--out", str(pending)], 3)
            skeleton = loads(read(pending / "operation-outcome-review.json"))
            nodes = {row["id"]: row["nodeId"] for row in skeleton["results"]}
            approvals = [row["nodeId"] for row in skeleton["execution"]["invocationTargets"] if row["capability"] == "APPROVE"]
            require(len(approvals) == 1, "Expected one actual canonical approval target")
            resource = contract["resources"][0]["identity"]
            trust = folder / "trust.json"
            trust.write_bytes(canonical(dict(formatVersion=2, grants=[dict(keyId="test-observer", publicKey=base64.b64encode(public).decode(),
                scopes=[resource["scope"]], resourceTypes=[resource["type"]], kinds=["RESULT", "STATE", "OWNERSHIP"], workflows=["main"])])))
            events, claims, captures = [], [], {}
            local = LocalWork(case, folder)
            try:
                def observe(identifier, node, attempt, action, cause=None):
                    start = timestamp()
                    try:
                        capture = dict(status="SUCCEEDED", result=action())
                    except (OSError, RuntimeError) as error:
                        capture = dict(status="FAILED", errorType=type(error).__name__, message=str(error))
                    end = timestamp()
                    source = canonical(capture)
                    captures[identifier] = source
                    event = dict(id=identifier, nodeId=node, attempt=attempt, startedAt=start, completedAt=end, status=capture["status"])
                    if cause:
                        event["causedBy"] = cause
                    if capture["status"] == "FAILED":
                        event["errorId"] = "error-" + identifier
                    events.append(event)
                    if identifier != "approval":
                        claims.append((dict(common, id="result-" + identifier, kind="RESULT", status=capture["status"], nodeId=node,
                            resource=resource, invocationId=identifier, observedAt=end, sourceSha256=hashlib.sha256(source).hexdigest(), sourceSizeBytes=len(source)), source))
                    return event

                observe("approval", approvals[0], 1, lambda: {"fixtureApproval": True, "humanApprovalClaimed": False})
                for attempt in (1, 2):
                    event = observe("probe-" + str(attempt), nodes["probe"], attempt, local.inspect)
                    require(event["status"] == "FAILED", "The local pre-recovery probe must really fail")
                ownership_at = timestamp()
                ownership = canonical({"owner": "local-test-harness", "resource": resource, "basis": "isolated test resource held by this process"})
                claims.append((dict(common, id="ownership", kind="OWNERSHIP", status="SUCCEEDED", nodeId=nodes["repair"], resource=resource,
                    invocationId="repair", observedAt=ownership_at, owner="local-test-harness", sourceSha256=hashlib.sha256(ownership).hexdigest(), sourceSizeBytes=len(ownership)), ownership))
                require(observe("repair", nodes["repair"], 1, local.repair, "probe-2")["status"] == "SUCCEEDED", "Local repair failed")
                require(observe("verify", nodes["verify"], 1, local.inspect, "probe-2")["status"] == "SUCCEEDED", "Local verification failed")
                completed = timestamp()
                # Independently capture the actual state again after the complete run, never copy expected facts.
                facts = local.inspect()
                state_source = canonical(facts)
                claims.append((dict(common, id="final-state", kind="STATE", status="SUCCEEDED", nodeId=nodes["verify"], resource=resource,
                    invocationId="verify", observedAt=timestamp(), facts=facts, sourceSha256=hashlib.sha256(state_source).hexdigest(), sourceSizeBytes=len(state_source)), state_source))
                trace_source = canonical({"events": events, "captures": {identifier: hashlib.sha256(source).hexdigest() for identifier, source in captures.items()}, "closed": True})
                run = dict(common, id="run-history", workflow="main", startedAt=contract["startedAt"], completedAt=completed, complete=True, status="SUCCEEDED",
                           sourceSha256=hashlib.sha256(trace_source).hexdigest(), sourceSizeBytes=len(trace_source), invocations=events)

                def assess(name, run_value, claim_values, exit_code, expected_code=None, corrupt_signature=False):
                    envelope = sign(run_value, trace_source, True)
                    if corrupt_signature:
                        envelope["signature"] = base64.b64encode(bytes(64)).decode()
                    bundle = dict(formatVersion=2, execution=envelope, observations=[sign(claim, source) for claim, source in claim_values])
                    observations = folder / (name + ".json")
                    observations.write_bytes(canonical(bundle))
                    output = folder / (name + "-review")
                    cli_run([*args, "--observations", str(observations), "--trust", str(trust), "--out", str(output)], exit_code)
                    review = loads(read(output / "operation-outcome-review.json"))
                    require(not review["executionAuthorized"], "Evidence must not authorize execution")
                    if expected_code:
                        codes = review["diagnostics"] + [row["code"] for row in review["results"]] + [row["code"] for row in review["execution"]["recoveries"]]
                        require(expected_code in codes, f"Missing expected failure: {expected_code}")
                    rows.append(dict(case=case, variant=name, state=review["state"]))
                    return review

                accepted = assess("observed-recovery", run, claims, 0)
                require(accepted["results"][0]["observedStatus"] == "FAILED" and len(accepted["execution"]["invocations"]) == 5, "Earlier failures were lost")
                assess("missing-failed-attempt", run, [(c, s) for c, s in claims if c["invocationId"] != "probe-1"], 3, "OUTCOME_OBSERVATION_MISSING")
                assess("incomplete-run", dict(run, complete=False), claims, 3, "OUTCOME_RUN_INCOMPLETE")
                stale = [(dict(c, observedAt=events[-1]["completedAt"]), s) if c["kind"] == "STATE" else (c, s) for c, s in claims]
                assess("pre-completion-state", run, stale, 3, "OUTCOME_STATE_BEFORE_RUN_COMPLETION")
                wrong = copy.deepcopy(run)
                wrong["invocations"][3]["causedBy"] = "probe-1"
                assess("wrong-trigger", wrong, claims, 3, "OUTCOME_INVOCATION_PATH")
                assess("invalid-signature", run, claims, 3, "OUTCOME_INVALID_SIGNATURE", corrupt_signature=True)
                false_facts = [(dict(c, facts={"ready": False}), s) if c["kind"] == "STATE" else (c, s) for c, s in claims]
                assess("observer-reports-failed-state", run, false_facts, 4, "OUTCOME_RECOVERY_POSTCONDITION_FAILED")
            finally:
                local.close()
    result = dict(status="PASS", evidenceKind="local-runtime-observer-test", cases=rows, localWorkExecuted=True,
                  cloudWorkExecuted=False, liveModelEvaluated=False, targetSupportPromoted=False)
    print(json.dumps(result))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--cli", required=True)
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    args = parser.parse_args()
    verify(args.root, args.cli)
