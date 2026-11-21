#!/usr/bin/env python3
"""Register held-out planning cases and independently review completed provider turns."""
import argparse
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile

from agent_worker import WorkerError
from agent_worker.protocol import exclusive_lock

from authoring_host import discovery, planning_evaluation as evaluation
from authoring_host.contracts import HostError, canonical, digest, integer, keys, loads, nonblank, require, save
from authoring_host.inventory_provenance import PUBLIC_KEY_PREFIX, decode, read_regular
from authoring_host.planning_worker import read_artifact, recover_handoff


def versioned(document, fields):
    keys(document, ("formatVersion", *fields))
    require(type(document["formatVersion"]) is int and document["formatVersion"] == 1, "Unsupported document version")


def pin(path, expected):
    data = read_artifact(path)
    require(digest(data) == expected, "Document differs from its independently retained digest")
    return loads(data)


def private_paths(cases, paths):
    for case in cases:
        for path in paths:
            discovery.check_output(case["config"]["discovery"], path)


def validate_policy(policy, cases):
    versioned(policy, ("reviewerId", "providerId", "evidenceKind", "reviewerKey", "providerKey", "requiredDomains", "heldOutCases"))
    for field in ("reviewerId", "providerId"):
        nonblank(policy[field])
    require(policy["evidenceKind"] in ("live-provider", "scripted"), "Invalid registered provider class")
    for field in ("providerKey", "reviewerKey"):
        key = decode(policy[field], 44)
        require(len(key) == 44 and key.startswith(PUBLIC_KEY_PREFIX), "Expected Ed25519 public key")
    require(policy["reviewerKey"] != policy["providerKey"], "Provider and reviewer require independent keys")
    domains = {c["domain"] for c in cases}
    ids = {c["id"] for c in cases}
    for field, available in (("requiredDomains", domains), ("heldOutCases", ids)):
        values = policy[field]
        require(isinstance(values, list) and values and all(isinstance(v, str) and v in available for v in values)
                and len(values) == len(set(values)), "Invalid portfolio coverage or exposure policy")
    require(set(policy["requiredDomains"]) == domains, "Portfolio domains differ from independent scope")
    held_out = set(policy["heldOutCases"])
    known_requests = {canonical(c["config"]["planning"]["task"]["request"]) for c in cases if c["id"] not in held_out}
    require(all(canonical(c["config"]["planning"]["task"]["request"]) not in known_requests
                for c in cases if c["id"] in held_out), "The same request appears on both sides of the exposure split")


def verified_registration(raw):
    registration = loads(raw)
    versioned(registration, ("experimentId", "planSha256", "assessmentCodeSha256", "policy"))
    require(isinstance(registration["experimentId"], str) and re.fullmatch("[0-9a-f]{64}", registration["experimentId"])
            and registration["assessmentCodeSha256"] == digest(Path(__file__).read_bytes()),
            "Registered experiment identity or assessment code changed")
    return registration


def register(args):
    root = args.out.absolute()
    value, cases = evaluation._portfolio(loads(read_artifact(args.portfolio)), root, args.repetitions, args.max_turns)
    private_paths(cases, (args.policy, args.portfolio))
    policy = loads(read_artifact(args.policy))
    validate_policy(policy, cases)
    require(all(not p.is_symlink() for p in (root, *root.parents)), "Unsafe evaluation directory")
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    with exclusive_lock(root / "lock"):
        if (root / "plan.json").exists():
            plan = evaluation._verify_plan(value, cases, root, args.repetitions, args.max_turns)
        else:
            plan = evaluation._prepare(value, cases, root, args.repetitions, args.max_turns)
        plan_sha = digest(canonical(plan))
        path = root / "registration.json"
        if path.exists():
            raw = read_artifact(path)
            previous = verified_registration(raw)
            require(previous["planSha256"] == plan_sha and canonical(previous["policy"]) == canonical(policy),
                    "Registration already differs")
        else:
            require(not evaluation._reservations(root, plan)["trials"], "Registration must precede all reservations")
            raw = canonical(dict(formatVersion=1, experimentId=secrets.token_hex(32), planSha256=plan_sha,
                                 assessmentCodeSha256=digest(Path(__file__).read_bytes()), policy=policy))
            save(root, path.name, raw, {})
        return dict(status="registered", registrationSha256=digest(raw), plannedTurns=plan["plannedTurns"])


def correlate_trial(root, case, row):
    worker = root / "trials" / row["id"] / "worker"
    with exclusive_lock(worker / "lock"):
        receipt = recover_handoff(case["config"], worker)
        require(digest(canonical(receipt)) == row["planningReceiptSha256"], "Planning selection changed during review")
        artifacts = receipt["result"]["artifacts"]
        responses = []
        questions = False
        for number in range(1, row["checkedDrafts"] + 1):
            name = f"draft-{number:03d}/proposal.json"
            proposal = pin(worker / "planning" / name, artifacts[name])
            response = proposal["response"]
            responses.append(dict(responseSha256=digest(canonical(response)),
                                  observationsSha256=artifacts[f"draft-{number:03d}/observations.json"]))
            if row["candidateId"] == name.split("/")[0]:
                questions = any(isinstance(q, dict) and q.get("severity") == "REQUIRED" and
                                isinstance(q.get("question"), str) and q["question"].strip()
                                for q in response["report"].get("openQuestions", []))
    identity = receipt["worker"]
    nonblank(identity["thread_id"])
    nonblank(identity["turn_id"])
    turn = dict(threadId=identity["thread_id"], turnId=identity["turn_id"], model=case["config"]["worker"]["model"],
                taskSha256=artifacts["task.json"], drafts=responses, observationsSha256=artifacts["observations.json"],
                candidateId=row["candidateId"])
    candidate = receipt["result"]["candidate"]
    needs_clarification = bool(questions and candidate and candidate["review"]["exitCode"] == 3 and
                               candidate["review"].get("targetDisposition") == "withheld-for-review")
    return turn, needs_clarification


def review_request(root, registration_sha, external_paths):
    raw = read_artifact(root / "registration.json")
    require(digest(raw) == registration_sha, "Registration differs from its independently retained digest")
    registration = verified_registration(raw)
    plan = pin(root / "plan.json", registration["planSha256"])
    value, cases = evaluation._portfolio(loads(read_artifact(root / "portfolio.json")), root,
                                         plan["repetitions"], plan["maxTurns"])
    private_paths(cases, external_paths)
    policy = registration["policy"]
    validate_policy(policy, cases)
    report = evaluation._summary(value, cases, root, plan)
    if report["status"] != "complete":
        return dict(status="incomplete", registrationSha256=registration_sha, completedRuns=report["completedRuns"],
                    plannedTurns=report["plannedTurns"])
    by_case = {c["id"]: c for c in cases}
    rows = []
    for row in report["runs"]:
        turn, questions = correlate_trial(root, by_case[row["case"]], row)
        rows.append(dict(id=row["id"], case=row["case"], domain=row["domain"], repetition=row["repetition"],
                         heldOut=row["case"] in policy["heldOutCases"], compilerAccepted=row["compilerAccepted"],
                         requestFidelity=row["requestFidelity"], planningReceiptSha256=row["planningReceiptSha256"],
                         reviewSha256=row["reviewSha256"], requiredQuestion=questions, turn=turn))
    require(len({r["turn"]["threadId"] for r in rows}) == len(rows), "Trials reused a planning session")
    evaluation._verify_inputs(value, cases, root, plan)
    require(len(evaluation._reservations(root, plan)["trials"]) == len(rows), "Reservation journal changed")
    return dict(formatVersion=1, status="ready-for-review", registrationSha256=registration_sha,
                policy=policy, model=report["model"], evaluationReportSha256=digest(canonical(report)), trials=rows)


def index_rows(rows, key, expected):
    require(isinstance(rows, list) and len(rows) == len(expected), "Evidence must cover every trial exactly once")
    indexed = {}
    for row in rows:
        require(isinstance(row, dict) and key in row and isinstance(row[key], str)
                and row[key] in expected and row[key] not in indexed, "Unknown or repeated evidence record")
        indexed[row[key]] = row
    return indexed


ASSESSMENTS = ("faithful", "appropriate-clarification", "appropriate-unsupported", "hard-constraint-violation",
               "wrong-goal", "unassessed")


def signed_document(path, signature_path, public_key):
    raw = read_artifact(path)
    signature = read_regular(signature_path.absolute(), 64)
    require(len(signature) == 64, "Invalid Ed25519 signature length")
    with tempfile.TemporaryDirectory() as temp:
        directory = Path(temp)
        for name, data in (("public.der", decode(public_key, 44)), ("message", raw), ("signature", signature)):
            (directory / name).write_bytes(data)
        result = subprocess.run(["openssl", "pkeyutl", "-verify", "-pubin", "-keyform", "DER",
                                 "-inkey", str(directory / "public.der"), "-rawin", "-in", str(directory / "message"),
                                 "-sigfile", str(directory / "signature")], capture_output=True, timeout=10)
        require(result.returncode == 0, "Evidence failed verification under its registered role key")
    return loads(raw), digest(raw)


def assess(request, review, provider, provider_sha):
    versioned(review, ("reviewerId", "requestSha256", "providerRecordsSha256", "trials"))
    policy = request["policy"]
    require(review["reviewerId"] == policy["reviewerId"] and review["providerRecordsSha256"] == provider_sha,
            "Review does not cover the registered reviewer and exact provider records")
    require(review["requestSha256"] == digest(canonical(request)), "Review belongs to another request")
    versioned(provider, ("registrationSha256", "providerId", "evidenceKind", "turns"))
    require(provider["registrationSha256"] == request["registrationSha256"] and
            all(provider[k] == policy[k] for k in ("providerId", "evidenceKind")), "Provider differs from registration")
    trials = request["trials"]
    decisions = index_rows(review["trials"], "id", {r["id"] for r in trials})
    records = index_rows(provider["turns"], "threadId", {r["turn"]["threadId"] for r in trials})
    blockers = []
    faithful = {domain: 0 for domain in policy["requiredDomains"]}
    outcomes = []
    for trial in trials:
        turn = trial["turn"]
        record = records[turn["threadId"]]
        keys(record, (*turn, "latencyMs", "costMicrousd"))
        require(canonical({k: record[k] for k in turn}) == canonical(turn),
                "Provider evidence differs from the retained request, drafts, selection or turn")
        for field in ("latencyMs", "costMicrousd"):
            if record[field] is not None:
                integer(record[field], 0, 2**53 - 1)
        decision = decisions[trial["id"]]
        keys(decision, ("id", "assessment", "reason"))
        nonblank(decision["reason"])
        assessment = decision["assessment"]
        require(assessment in ASSESSMENTS, "Unknown independent assessment")
        fidelity = trial["requestFidelity"]
        matched = (assessment == "faithful" and fidelity == "requirements-satisfied" and trial["compilerAccepted"])
        # A reviewer cannot waive a compiler-detected violation or convert an unsupported plan into a success.
        appropriate = (assessment == "appropriate-unsupported" and fidelity == "unsupported" or
                       assessment == "appropriate-clarification" and trial["requiredQuestion"] and
                       fidelity in ("review-required", "unassessed"))
        invalid_assessment = assessment == "unassessed" or (
            assessment not in ("wrong-goal", "hard-constraint-violation") and not (matched or appropriate))
        accepted_violation = trial["compilerAccepted"] and (
            fidelity == "violated" or assessment in ("wrong-goal", "hard-constraint-violation"))
        if invalid_assessment or accepted_violation:
            blockers.append(trial["id"])
        if matched:
            faithful[trial["domain"]] += 1
        outcomes.append(dict(id=trial["id"], case=trial["case"], domain=trial["domain"], repetition=trial["repetition"],
                             heldOut=trial["heldOut"], compilerAccepted=trial["compilerAccepted"],
                             assessment=assessment, reason=decision["reason"], requestFidelity=fidelity,
                             faithful=matched, passed=matched or appropriate,
                             latencyMs=record["latencyMs"], costMicrousd=record["costMicrousd"]))
    held_out_pass = any(r["heldOut"] and r["faithful"] for r in outcomes)
    quality = all(faithful.values()) and held_out_pass and not blockers
    status = "accepted" if quality else "quality-gates-failed"
    if provider["evidenceKind"] != "live-provider":
        status = "test-evidence"
    costs = [r["costMicrousd"] for r in outcomes]
    return dict(formatVersion=1, status=status, registrationSha256=request["registrationSha256"],
                requestSha256=review["requestSha256"], providerRecordsSha256=review["providerRecordsSha256"],
                reviewerId=review["reviewerId"], providerId=provider["providerId"], evidenceKind=provider["evidenceKind"],
                model=request["model"], evaluatedTurns=len(outcomes), evaluationReportSha256=request["evaluationReportSha256"],
                qualityGatesPassed=quality, blockingTrials=blockers, faithfulPerDomain=faithful,
                faithfulHeldOut=held_out_pass, trials=outcomes,
                totalCostMicrousd=sum(costs) if all(c is not None for c in costs) else None, milestoneComplete=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    registration = commands.add_parser("register")
    registration.add_argument("--portfolio", required=True, type=Path)
    registration.add_argument("--policy", required=True, type=Path)
    registration.add_argument("--repetitions", type=int, default=3)
    registration.add_argument("--max-turns", required=True, type=int)
    request = commands.add_parser("request")
    assessment = commands.add_parser("assess")
    for sub in (registration, request, assessment):
        sub.add_argument("--out", type=Path, required=True, help="Existing evaluator's private output directory")
    for sub in (request, assessment):
        sub.add_argument("--registration-sha256", required=True)
    assessment.add_argument("--review", type=Path, required=True)
    assessment.add_argument("--review-signature", required=True, type=Path)
    assessment.add_argument("--provider-signature", required=True, type=Path)
    assessment.add_argument("--provider-records", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "register":
            result = register(args)
        else:
            root = args.out.absolute()
            external = (args.review, args.provider_records, args.provider_signature, args.review_signature) if args.command == "assess" else ()
            with exclusive_lock(root / "lock"):
                result = review_request(root, args.registration_sha256, external)
                if args.command == "assess" and result["status"] == "ready-for-review":
                    policy = result["policy"]
                    review, review_sha = signed_document(args.review, args.review_signature, policy["reviewerKey"])
                    records, provider_sha = signed_document(args.provider_records, args.provider_signature, policy["providerKey"])
                    result = dict(assess(result, review, records, provider_sha), reviewSha256=review_sha)
        sys.stdout.buffer.write(canonical(result))
        return 0 if result["status"] in ("registered", "ready-for-review", "accepted") else 3
    except (HostError, WorkerError, OSError, KeyError, TypeError, ValueError, subprocess.TimeoutExpired) as error:
        print(str(error) if isinstance(error, HostError) else "Invalid or unavailable portfolio evidence", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
