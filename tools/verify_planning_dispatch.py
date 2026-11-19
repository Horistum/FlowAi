#!/usr/bin/env python3
"""Installed compiler gate with an independently authorized transactional runtime."""
import argparse
import base64
import copy
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import secrets
import sqlite3
import subprocess
import sys
import tempfile

from authoring_host.contracts import canonical, digest, loads, read, require
from verify_dispatch_outcomes import outcome_settings, verify_outcomes
from tests_worker.test_planning_worker import configuration, commission_fixture, script, step


def verify(root, cli):
    rows = []
    with tempfile.TemporaryDirectory(prefix="flow-planning-dispatch-") as temporary:
        work = Path(temporary)
        config = configuration(work, [str(cli)])
        proposal = loads(read(root / "conformance/operation-preconditions/data-copy.proposal.json"))
        config["planning"]["task"]["request"] = proposal["request"]
        config["planning"]["compiler"]["contracts"] = str(root)
        commission_fixture(config, work / "commissioning")
        script(work, [dict(step("flow_check_plan", response=proposal["response"]), id="checked")])
        settings = work / "worker.json"; settings.write_bytes(canonical(config))
        run = work / "run"; goal = work / "goal.json"; review = work / "review"
        goal.write_bytes(canonical(dict(formatVersion=1, request=proposal["request"], origin="REVIEWER_AUTHORED",
            confirmation="CONFIRMED", reviewerId="independent-data-reviewer", requirements=[
                dict(id="keep-data", sourceQuote="Synchronize a retained snapshot", check=dict(kind="forbid-capability", capability="DEPROVISION")),
                dict(id="copy", sourceQuote="Synchronize a retained snapshot", check=dict(kind="capability-count", selector=dict(capability="DATA_SYNC"), minimum=1, maximum=1))])))

        def command(args, expected):
            result = subprocess.run(args, capture_output=True, timeout=180)
            target = args[args.index("--out") + 1] if "--out" in args else args[-1]
            require(result.returncode == expected, f"Installed dispatch CLI {target}: exit {result.returncode}, expected {expected}: "
                    + result.stderr.decode()[-800:] + result.stdout.decode()[-800:])
            return loads(result.stdout) if expected != 2 else None

        planning = [sys.executable, str(root / "tools/agent_planning_worker.py"), "--config", str(settings), "--out", str(run)]
        command(planning, 0)
        command([*planning, "--review-requirements", str(goal), "--review-out", str(review)], 0)
        fidelity = loads(read(review / "review.json"))
        key = work / "observer.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(key)], capture_output=True, check=True)
        public = subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"], capture_output=True, check=True).stdout
        trust = work / "trust.json"
        trust.write_bytes(canonical(dict(formatVersion=1, grants=[dict(keyId="test-observer", publicKey=base64.b64encode(public).decode(),
            scopes=["storage-test"], resourceTypes=["dataset"])])))
        template = loads(read(root / "conformance/operation-preconditions/data-copy.requirements.json"))
        outcome_review, audit_key = outcome_settings(root, work)
        variants = (("fresh", 0), ("concurrent", 0), ("concurrent-runtime", 0), ("owner", 4), ("instance", 4), ("state", 4),
            ("unauthorized", 3), ("source-only", 3), ("missing", 3), ("stale", 3), ("expires-before-use", 3),
            ("crash-before-decision", 2), ("crash-after-commit", 2), ("false-completion", 0), ("invalid-outcome-selector", 3))
        for variant, expected in variants:
            case = work / variant; case.mkdir()
            database = case / "provider.sqlite"
            with sqlite3.connect(database) as db:
                db.execute("CREATE TABLE datasets(name TEXT PRIMARY KEY, instance_id TEXT, owner TEXT, writable INTEGER, schema_version INTEGER, payload TEXT)")
                db.execute("""CREATE TABLE effects(dispatch_id TEXT PRIMARY KEY, decision_sha TEXT, receipt TEXT, node_id TEXT,
                                  destination TEXT, instance_id TEXT, owner_at_use TEXT, recorded_at TEXT)""")
                db.execute("CREATE TABLE authorizations(reference TEXT PRIMARY KEY, document TEXT)")
                db.executemany("INSERT INTO datasets VALUES (?,?,?,?,?,?)", [
                    ("isolated-copy", "native-copy-generation-1", "data-team", 1, 1, "previous-data"),
                    ("retained-snapshot", "native-snapshot-generation-1", "archive-team", 0, 1, "retained-data")])
                if variant == "owner": db.execute("UPDATE datasets SET owner='foreign-owner' WHERE name='isolated-copy'")
                if variant == "instance": db.execute("UPDATE datasets SET instance_id='replacement' WHERE name='isolated-copy'")
                if variant == "state": db.execute("UPDATE datasets SET writable=0 WHERE name='isolated-copy'")
            now = datetime.now(timezone.utc)
            contract = copy.deepcopy(template)
            contract.update(graphDigest=fidelity["review"]["graphDigest"], assessmentId="dispatch-" + variant,
                challenge=secrets.token_urlsafe(32), issuedAt=(now - timedelta(seconds=5)).isoformat(),
                expiresAt=(now + timedelta(minutes=5)).isoformat())
            if variant == "source-only":
                contract["resources"] = [r for r in contract["resources"] if r["id"] == "source"]
                contract["resources"][0].update(disposition="MANAGED", owner="archive-team")
                contract["operations"] = [r for r in contract["operations"] if r["parameter"] == "source"]
            if variant == "expires-before-use":
                for operation in contract["operations"]: operation["maxAgeSeconds"] = 20
            requirements = case / "requirements.json"; requirements.write_bytes(canonical(contract))
            dispatch = dict(id="dispatch-" + variant, runtimeId="test-sqlite", authorizationRef="approved-" + variant)
            approval = dict(dispatchId=dispatch["id"], runtimeId=dispatch["runtimeId"], authorizationRef=dispatch["authorizationRef"],
                candidateId=fidelity["candidateId"], proposalSha256=fidelity["proposalSha256"],
                canonicalPlanSha256=fidelity["artifacts"]["compiler/canonical-execution-plan.json"],
                fidelityReviewSha256=digest(canonical(fidelity)), compilerDigest=fidelity["compilerDigest"],
                contractSha256=digest(read(requirements)), source="retained-snapshot", destination="isolated-copy")
            if variant == "unauthorized": approval["canonicalPlanSha256"] = "b" * 64
            with sqlite3.connect(database) as db:
                db.execute("INSERT INTO authorizations VALUES (?,?)", (dispatch["authorizationRef"], canonical(approval).decode()))
            counter = case / "runtime-count"
            runtime_settings = case / "dispatch-settings.json"
            optional = dict(outcomeReview=outcome_review) if variant in ("fresh", "false-completion", "crash-after-commit", "invalid-outcome-selector") else {}
            if variant == "invalid-outcome-selector":
                bad_policy = case / "policy.json"
                policy = loads(read(outcome_review["policy"]))
                policy["operations"][0]["selector"]["capability"] = "DEPROVISION"
                bad_policy.write_bytes(canonical(policy))
                optional = dict(outcomeReview=dict(outcome_review, policy=str(bad_policy)))
            runtime_settings.write_bytes(canonical(dict(formatVersion=1, **optional, requirements=str(requirements), trust=str(trust),
                dispatch=dispatch, runtime=dict(argv=[sys.executable, str(root / "tools/tests_worker/dispatch_runtime.py"),
                    "--database", str(database), "--key", str(key), "--count", str(counter),
                    "--mode", variant], credentialEnv=[], timeoutSeconds=100))))
            output = case / "dispatch"
            base = [sys.executable, str(root / "tools/dispatch_planning_worker.py"), "--config", str(settings),
                "--run", str(run), "--review-requirements", str(goal), "--review-out", str(review),
                "--dispatch-config", str(runtime_settings)]
            result = command([*base, "--out", str(output)], expected)
            def effect_count():
                with sqlite3.connect(database) as db:
                    return db.execute("SELECT COUNT(*) FROM effects").fetchone()[0], db.execute(
                        "SELECT payload FROM datasets WHERE name='isolated-copy'").fetchone()[0]
            copied = variant in ("fresh", "concurrent", "concurrent-runtime", "crash-after-commit")
            expected_events = int(copied or variant == "false-completion")
            event_count, destination_payload = effect_count()
            require((event_count, destination_payload) == (expected_events, "retained-data" if copied else "previous-data"),
                    "Actual runtime effects disagree with the independent expectation")
            rows.append(dict(case=variant, exitCode=expected, committedEffects=event_count, destinationMatchesSnapshot=copied))
            if variant == "source-only":
                require(not counter.exists(), "Uncovered target reached external runtime")
            if variant == "expires-before-use":
                require(loads(read(output / "runtime-assessment.json"))["currentState"] == "SATISFIED",
                        "Expiry case did not reach the external runtime use boundary with a positive assessment")
            if variant == "concurrent":
                require((case / "concurrent-blocked").read_text() == "true", "Concurrent writer was not exercised")
                # After commit, a new transaction must be able to write. The
                # contender's failure was a held lock, not permanently bad access.
                with sqlite3.connect(database) as db:
                    db.execute("UPDATE datasets SET owner='after-commit' WHERE name='isolated-copy'")
            if variant == "concurrent-runtime":
                require((case / "duplicate-blocked").read_text() == "true" and len(counter.read_text().splitlines()) == 2,
                        "Concurrent external invocations were not exercised")
            if variant in ("crash-before-decision", "crash-after-commit"):
                require(not (output / "dispatch.json").exists(), "Uncertain process became completed")
                count = counter.read_text()
                command([*base, "--out", str(output)], 2)
                require(counter.read_text() == count, "Uncertain runtime invocation was repeated")
                rows.append(dict(case=variant + "-no-retry", exitCode=2, committedEffects=effect_count()[0]))
            if variant == "fresh":
                before = read(output / "dispatch.json"); count = counter.read_text()
                command([*base, "--out", str(output)], 0)
                require(counter.read_text() == count and read(output / "dispatch.json") == before and effect_count()[0] == 1,
                        "Completed recovery repeated work")
                rows.append(dict(case="completed-recovery", exitCode=0, committedEffects=1))
                command([*base, "--out", str(case / "different-output-same-dispatch")], 3)
                require(effect_count()[0] == 1, "External dispatch identity allowed duplicate work")
                rows.append(dict(case="external-duplicate-rejected", exitCode=3, committedEffects=1))
            if variant == "invalid-outcome-selector":
                command([*base, "--out", str(output)], 3)
                require(result["status"] == "outcome-policy-blocked" and not counter.exists() and
                        not (output / "dispatch-intent.json").exists(),
                        "Unresolvable outcome selection reached the external runtime")
            elif optional or variant == "unauthorized":
                outcome_cli = [sys.executable, str(root / "tools/review_dispatch_outcomes.py"),
                               "--config", str(settings), "--run", str(run), "--review-requirements", str(goal),
                               "--review-out", str(review), "--dispatch-config", str(runtime_settings), "--dispatch-out", str(output)]
                before = counter.read_bytes()
                rows.extend(verify_outcomes(root, work, case, database, audit_key, variant, outcome_cli, command))
                require(counter.read_bytes() == before, "Outcome review launched the runtime")
            if result:
                require(result["executionAuthorized"] is False and result["outcomeIndependentlyVerified"] is False,
                        "Runtime report was promoted to compiler authority")
        require((work / "home/turn-count").read_text() == "1", "Dispatch made an additional model call")
    print(json.dumps(dict(status="PASS", evidenceKind="scripted-worker-installed-compiler-transactional-external-runtime",
                         cases=rows, liveModelEvaluated=False, localFixtureEffectsExecuted=True,
                         cloudWorkExecuted=False, targetSupportPromoted=False)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(Path(args.root).resolve(), Path(args.cli).resolve())
