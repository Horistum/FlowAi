#!/usr/bin/env python3
"""Fresh observer processes capture real local work and reconcile without executing it twice."""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import sqlite3
import subprocess
import sys
import tempfile

from authoring_host.contracts import canonical, loads, read, require
from observation_host.journal import event_for
from verify_run_evidence import LocalWork, timestamp


def verify(root, cli):
    root, cli = Path(root).resolve(), Path(cli).resolve()
    rows = []
    with tempfile.TemporaryDirectory(prefix="flow-durable-observer-proof-") as temporary:
        work = Path(temporary)
        key = work / "observer.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(key)], check=True, capture_output=True)
        public = subprocess.check_output(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"])

        def cli_run(args, expected=0):
            result = subprocess.run([str(cli), *args], cwd=root, capture_output=True, timeout=120)
            require(result.returncode == expected, "Installed compiler proof failed")

        for case in loads(read(root / "conformance/run-evidence/corpus.json"))["cases"]:
            folder = work / case; folder.mkdir()
            proposal = root / "conformance/run-evidence" / (case + ".proposal.json")
            cli_run(["proposal", str(proposal), "--out", str(folder / "plan")])
            graph = loads(read(folder / "plan/proposal-review.json"))["graphDigest"]
            contract = loads(read(root / "conformance/run-evidence" / (case + ".requirements.json")))
            contract.update(graphDigest=graph, challenge=secrets.token_urlsafe(32), startedAt=timestamp())
            requirements = folder / "requirements.json"; requirements.write_bytes(canonical(contract))
            resource = contract["resources"][0]["identity"]
            trust = folder / "trust.json"
            trust.write_bytes(canonical(dict(formatVersion=2, grants=[dict(keyId="observer", publicKey=base64.b64encode(public).decode(),
                scopes=[resource["scope"]], resourceTypes=[resource["type"]], kinds=["RESULT", "STATE", "OWNERSHIP"], workflows=["main"])])))
            journal = folder / "journal"

            def host(command, *args, expected=0, directory=journal):
                result = subprocess.run([sys.executable, str(root / "tools/observe.py"), command, "--journal", str(directory),
                                         *map(str, args)], capture_output=True, timeout=150)
                require(result.returncode == expected, f"Observer {command} exit {result.returncode}, expected {expected}")
                return json.loads(result.stdout or result.stderr)

            tip = host("init", "--source", proposal, "--source-kind", "proposal", "--requirements", requirements, "--cli", cli)
            status = host("status")
            nodes = {r["id"]: r["nodeId"] for r in status["operationTargets"]}
            approval = next(r["nodeId"] for r in status["invocationTargets"] if r["capability"] == "APPROVE")
            counter = 0

            def prepare(kind, data, source=b""):
                nonlocal counter
                counter += 1
                event = event_for(tip, "capture-" + str(counter), kind, data, source)
                event_path, capture_path = folder / f"event-{counter}.json", folder / f"capture-{counter}.bin"
                event_path.write_bytes(canonical(event))
                capture_path.write_bytes(source)
                return event_path, capture_path if source else None

            def deliver(paths):
                nonlocal tip
                event_path, capture_path = paths
                args = ["--event", event_path] + (["--capture", capture_path] if capture_path else [])
                tip = host("append", *args)
                return tip

            def append(kind, data, source=b""):
                paths = prepare(kind, data, source)
                deliver(paths)
                return paths

            def claim(identifier, kind, node, invocation, at, source, **values):
                append("OBSERVATION", dict(id=identifier, kind=kind, status=values.pop("status", "SUCCEEDED"), nodeId=node,
                       invocationId=invocation, resource=resource, observedAt=at, **values), source)

            def export(name, expected, retained=None, directory=journal):
                checkpoint = folder / (name + "-checkpoint.json"); checkpoint.write_bytes(canonical(retained or tip))
                result = host("export", "--checkpoint", checkpoint, "--cli", cli, "--trust", trust,
                              "--private-key", key, "--key-id", "observer", "--out", folder / name,
                              expected=expected, directory=directory)
                if expected != 2:
                    review = loads(read(Path(result["directory"]) / "operation-outcome-review.json"))
                    require(review["executionAuthorized"] is False, "Observer assessment authorized execution")
                    return review
                require(not (folder / name / "evidence").exists(), "Rejected snapshot was published")
                return result

            local = LocalWork(case, folder)
            repairs = 0
            deferred_claim = None
            try:
                def observe(identifier, node, attempt, action, cause=None, suspend=False, ownership=None, defer_result=False):
                    nonlocal repairs, deferred_claim
                    start = timestamp()
                    data = dict(invocationId=identifier, nodeId=node, attempt=attempt, startedAt=start)
                    if cause:
                        data["causedBy"] = cause
                    append("START", data)
                    if ownership:
                        claim("ownership", "OWNERSHIP", node, identifier, ownership[0], ownership[1], owner="local-test-harness")
                    try:
                        value = action()
                        capture = dict(status="SUCCEEDED", result=value)
                    except (OSError, RuntimeError) as failure:
                        capture = dict(status="FAILED", errorType=type(failure).__name__, message=str(failure))
                    end = timestamp(); source = canonical(capture)
                    finish = dict(invocationId=identifier, completedAt=end, status=capture["status"])
                    if capture["status"] == "FAILED":
                        finish["errorId"] = "error-" + identifier
                    delivery = prepare("FINISH", finish, source)
                    if suspend:
                        # External work happened once. Its captured reply survives independently of the collector process.
                        repairs += 1
                        pending = host("status")
                        require(pending["state"] == "PENDING_INVOCATION" and pending["pendingInvocations"] == [identifier], "Lost in-flight invocation")
                        partial = export("interrupted-capture", 3)
                        require("OUTCOME_RUN_MISSING" in partial["diagnostics"], "Open journal invented a complete run")
                        rows.append(dict(case=case, variant="interrupted-capture", state=partial["state"]))
                    deliver(delivery)
                    receipt = tip.copy()
                    deliver(delivery)  # Lost acknowledgement: same persisted finish, no operation re-execution.
                    require(tip == receipt, "Duplicate delivery changed the journal")
                    if identifier != "approval":
                        if defer_result:
                            deferred_claim = ("result-" + identifier, "RESULT", node, identifier, end, source)
                        else:
                            claim("result-" + identifier, "RESULT", node, identifier, end, source, status=capture["status"])
                    return capture["status"]

                observe("approval", approval, 1, lambda: {"fixtureApproval": True, "humanApprovalClaimed": False})
                for number in (1, 2):
                    require(observe("probe-" + str(number), nodes["probe"], number, local.inspect) == "FAILED", "Initial work did not fail")
                ownership = (timestamp(), canonical({"owner": "local-test-harness", "basis": "isolated fixture owned by this process"}))
                require(observe("repair", nodes["repair"], 1, local.repair, "probe-2", suspend=True, ownership=ownership) == "SUCCEEDED", "Actual repair failed")
                observe("verify", nodes["verify"], 1, local.inspect, "probe-2", defer_result=True)
                append("CLOSE", dict(completedAt=timestamp(), status="SUCCEEDED"), canonical({"observerClosedRun": True}))
                missing = export("closed-with-late-result", 3)
                require(any(r["id"] == "verify" and r["code"] == "OUTCOME_OBSERVATION_MISSING" for r in missing["results"]),
                        "Closure hid a missing result capture")
                rows.append(dict(case=case, variant="closed-with-late-result", state=missing["state"]))
                claim(*deferred_claim)
                before_state = tip.copy()
                # Preserve a valid pre-state journal for a separate adverse final-state capture.
                adverse = folder / "adverse"; shutil.copytree(journal, adverse)
                facts = local.inspect()
                state_source = canonical(facts)
                claim("final-probe", "STATE", nodes["probe"], "probe-2", timestamp(), state_source, facts=facts)
                claim("final-verify", "STATE", nodes["verify"], "verify", timestamp(), state_source, facts=facts)
                accepted = export("resumed-recovery", 0)
                require(repairs == 1 and len(accepted["execution"]["invocations"]) == 5
                        and accepted["execution"]["recoveries"][0]["observation"]["path"] == "RECOVERED", "Reconciliation repeated work or lost recovery")
                rows.append(dict(case=case, variant="resumed-recovery", state=accepted["state"], repairExecutions=repairs))
                export("stale-checkpoint", 2, retained=before_state)
                rows.append(dict(case=case, variant="stale-checkpoint", state="REJECTED"))

                adverse_tip = before_state
                for identifier, node, invocation in [("false-probe", nodes["probe"], "probe-2"), ("false-verify", nodes["verify"], "verify")]:
                    source = canonical({"ready": False})
                    data = dict(id=identifier, kind="STATE", status="SUCCEEDED", nodeId=node, invocationId=invocation,
                                resource=resource, observedAt=timestamp(), facts={"ready": False})
                    event = event_for(adverse_tip, identifier, "OBSERVATION", data, source)
                    event_path, source_path = folder / (identifier + ".json"), folder / (identifier + ".bin")
                    event_path.write_bytes(canonical(event)); source_path.write_bytes(source)
                    adverse_tip = host("append", "--event", event_path, "--capture", source_path, directory=adverse)
                failed = export("reported-failed-state", 4, retained=adverse_tip, directory=adverse)
                rows.append(dict(case=case, variant="reported-failed-state", state=failed["state"]))

                damaged = folder / "damaged"; shutil.copytree(journal, damaged)
                with sqlite3.connect(damaged / "journal.sqlite3") as connection:
                    connection.execute("UPDATE events SET capture=? WHERE sequence=2", (b"changed-capture",))
                export("altered-journal", 2, directory=damaged)
                rows.append(dict(case=case, variant="altered-journal", state="REJECTED"))
            finally:
                local.close()
    result = dict(status="PASS", evidenceKind="durable-observer-integration-test", cases=rows, localWorkExecuted=True,
                  cloudWorkExecuted=False, liveModelEvaluated=False, targetSupportPromoted=False)
    print(json.dumps(result))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(args.root, args.cli)
