#!/usr/bin/env python3
"""Selected worker plan -> independent review -> fresh observed resource assessment."""
import argparse
import base64
import copy
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
from unittest.mock import patch

from authoring_host import planning_preflight
from authoring_host.contracts import canonical, loads, read, require
from tests_worker.test_planning_worker import configuration, commission_fixture, script, step


def verify(root, cli):
    rows = []
    with tempfile.TemporaryDirectory(prefix="flow-planning-preflight-") as temporary:
        work = Path(temporary)
        config = configuration(work, [str(cli)])
        proposal = loads(read(root / "conformance/operation-preconditions/data-copy.proposal.json"))
        config["planning"]["task"]["request"] = proposal["request"]
        config["planning"]["compiler"]["contracts"] = str(root)
        commission_fixture(config, work / "commissioning")
        # Independent provider state, separate from the retained discovery snapshot.
        original = [dict(resource=dict(scope="storage-test", type="dataset", key="isolated-copy"),
                         instanceId="native-copy-generation-1", owner="data-team", facts=dict(writable=True, schemaVersion=1)),
                    dict(resource=dict(scope="storage-test", type="dataset", key="retained-snapshot"),
                         instanceId="native-snapshot-generation-1", owner="archive-team", facts=dict(writable=False, schemaVersion=1))]
        provider = work / "provider.json"; provider.write_bytes(canonical(original))
        (work / "inventory/resources.json").write_bytes(canonical(original))
        script(work, [step("flow_observe", kind="read", root="inventory", path="resources.json"),
                      dict(step("flow_check_plan", response=proposal["response"]), id="checked")])
        settings = work / "worker.json"; settings.write_bytes(canonical(config))
        run = work / "run"; goal = work / "goal.json"; review = work / "review"
        goal.write_bytes(canonical(dict(formatVersion=1, request=proposal["request"], origin="REVIEWER_AUTHORED",
            confirmation="CONFIRMED", reviewerId="independent-data-reviewer", requirements=[
                dict(id="keep-data", sourceQuote="Synchronize a retained snapshot", check=dict(kind="forbid-capability", capability="DEPROVISION")),
                dict(id="copy", sourceQuote="Synchronize a retained snapshot", check=dict(kind="capability-count", selector=dict(capability="DATA_SYNC"), minimum=1, maximum=1))])))
        base = [sys.executable, str(root / "tools/agent_planning_worker.py"), "--config", str(settings), "--out", str(run)]
        def command(args, expected):
            result = subprocess.run(args, capture_output=True, timeout=180)
            require(result.returncode == expected, f"Installed preflight CLI exit {result.returncode}, expected {expected}: " + result.stderr.decode()[-1000:] + result.stdout.decode()[-1000:])
            return loads(result.stdout)
        command(base, 0)
        independent = [*base, "--review-requirements", str(goal), "--review-out", str(review)]
        command(independent, 0)
        fidelity = loads(read(review / "review.json"))
        key = work / "observer.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(key)], capture_output=True, check=True)
        public = subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"], capture_output=True, check=True).stdout
        trust = work / "trust.json"
        trust.write_bytes(canonical(dict(formatVersion=1, grants=[dict(keyId="test-observer", publicKey=base64.b64encode(public).decode(),
            scopes=["storage-test"], resourceTypes=["dataset"])])))
        counter = work / "observer-count"
        template = loads(read(root / "conformance/operation-preconditions/data-copy.requirements.json"))
        for variant, expected in (("fresh-shared-source", 0), ("owner", 4), ("instance", 4), ("state", 4),
                                  ("source-only", 3), ("missing", 3), ("stale", 3)):
            state = copy.deepcopy(original)
            if variant == "owner": state[0]["owner"] = "transferred-team"
            if variant == "instance": state[0]["instanceId"] = "same-address-replacement"
            if variant == "state": state[0]["facts"]["writable"] = False
            provider.write_bytes(canonical(state))
            contract = copy.deepcopy(template)
            now = datetime.now(timezone.utc)
            contract.update(graphDigest=fidelity["review"]["graphDigest"], assessmentId="assessment-" + variant,
                challenge=secrets.token_urlsafe(32), issuedAt=(now - timedelta(seconds=5)).isoformat(), expiresAt=(now + timedelta(minutes=5)).isoformat())
            if variant == "source-only":
                contract["resources"] = [r for r in contract["resources"] if r["id"] == "source"]
                contract["resources"][0].update(disposition="MANAGED", owner="archive-team")
                contract["operations"] = [r for r in contract["operations"] if r["parameter"] == "source"]
            requirements = work / (variant + "-requirements.json"); requirements.write_bytes(canonical(contract))
            preflight_settings = work / (variant + "-settings.json")
            preflight_settings.write_bytes(canonical(dict(formatVersion=1, requirements=str(requirements), trust=str(trust),
                observer=dict(argv=[sys.executable, str(root / "tools/tests_worker/precondition_observer.py"),
                    "--state", str(provider), "--key", str(key), "--count", str(counter), "--mode", variant if variant in ("missing", "stale") else "fresh"],
                    credentialEnv=[], timeoutSeconds=30))))
            output = work / (variant + "-preflight")
            count_before = counter.read_text() if counter.exists() else "0"
            result = command([*independent, "--preflight-config", str(preflight_settings), "--preflight-out", str(output)], expected)
            require(result["currentState"] == {0: "SATISFIED", 3: "UNRESOLVED", 4: "VIOLATED"}[expected], "Wrong integrated result")
            receipt = loads(read(output / "preflight.json"))
            require(receipt["candidateId"] == "draft-001" and receipt["fidelityReviewSha256"] == planning_preflight.digest(canonical(fidelity)) and
                    receipt["executionAuthorized"] is False, "Integrated assessment lost candidate identity")
            rows.append(dict(case=variant, exitCode=expected))
            if variant == "source-only":
                require(counter.read_text() == count_before, "Incomplete coverage reached the observer")
                require("PRECONDITION_UNCOVERED_EFFECT_RESOURCE" in receipt["assessment"]["diagnostics"], "Missing coverage diagnostic")
            if variant == "fresh-shared-source":
                before = read(output / "preflight.json"); count = counter.read_text()
                command([*independent, "--preflight-config", str(preflight_settings), "--preflight-out", str(output)], 0)
                require(counter.read_text() == count, "Recovery repeated observation")
                rows.append(dict(case="process-free-recovery", exitCode=0))
                at = planning_preflight._instant(receipt["assessment"]["validUntil"]) + timedelta(microseconds=1)
                with patch.object(planning_preflight, "_now", return_value=at), \
                     patch.object(planning_preflight, "invoke", side_effect=AssertionError("Recovery must not start a process")):
                    expired = planning_preflight.run(config, run, goal, review, preflight_settings, output)
                require(expired["exitCode"] == 3 and expired["code"] == "PREFLIGHT_EXPIRED" and read(output / "preflight.json") == before,
                        "Expired evidence was reusable or historical evidence was rewritten")
                rows.append(dict(case="expired-retained-assessment", exitCode=3))
        require((work / "home/turn-count").read_text() == "1", "Preflight started an extra model turn")
    print(json.dumps(dict(status="PASS", evidenceKind="scripted-worker-independent-observer-installed-compiler",
                         cases=rows, liveModelEvaluated=False, workflowExecuted=False)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(Path(args.root).resolve(), Path(args.cli).resolve())
