"""Repeated worker planning with private expectations and durable turn reservations.

This orchestrates existing boundaries; the compiler alone evaluates requirements.
An executable's identity is recorded, not treated as proof of live-model provenance.
"""
from __future__ import annotations

from pathlib import Path
import re

from agent_worker import WorkerError, WorkerIndeterminate
from agent_worker.protocol import atomic, exclusive_lock

from . import discovery, planning_review
from .contracts import HostError, canonical, digest, integer, keys, loads, public_contracts, require, save
from .planning_worker import authority_identity, bound_worker, configuration, read_artifact, recover_handoff, run
from .transport import capability_catalog, compiler_fingerprint


def _portfolio(value, root, repetitions, max_turns):
    integer(repetitions, 3, 10)
    integer(max_turns, 1, 160)
    value = loads(canonical(value))
    keys(value, ("formatVersion", "worker", "compiler", "limits", "cases"))
    require(type(value["formatVersion"]) is int and value["formatVersion"] == 1, "Unsupported evaluation portfolio")
    require(isinstance(value["cases"], list) and 1 <= len(value["cases"]) <= 16, "Invalid evaluation case count")
    require(len(value["cases"]) * repetitions <= max_turns, "Evaluation exceeds the explicit total turn allowance")
    cases, identifiers = [], set()
    for case in value["cases"]:
        keys(case, ("id", "domain", "task", "discovery", "requirements"))
        identifier = case["id"]
        require(isinstance(identifier, str) and re.fullmatch(r"[a-z][a-z0-9-]{0,63}", identifier) and
                identifier not in identifiers, "Invalid or duplicate evaluation case ID")
        identifiers.add(identifier)
        require(isinstance(case["domain"], str) and re.fullmatch(r"[a-z][a-z0-9-]{0,63}", case["domain"]),
                "Invalid evaluation domain")
        config = configuration(dict(formatVersion=1, worker=value["worker"], discovery=case["discovery"],
                                    planning=dict(formatVersion=1, compiler=value["compiler"],
                                                  limits=value["limits"], task=case["task"])))
        keys(case["requirements"], ("path", "sha256"))
        path = case["requirements"]["path"]
        require(isinstance(path, str) and Path(path).is_absolute(), "Requirements path must be absolute")
        data = read_artifact(path)
        require(digest(data) == case["requirements"]["sha256"], "Independent evaluation requirements changed")
        requirements = loads(data)
        require(isinstance(requirements, dict) and canonical(requirements.get("request")) ==
                canonical(case["task"]["request"]), "Evaluation requirements belong to another request")
        require(requirements.get("confirmation") == "CONFIRMED" and bool(requirements.get("requirements")),
                "Evaluation requires nonempty independently confirmed requirements")
        cases.append(dict(id=identifier, domain=case["domain"], config=config, requirements=data))
    # Check the entire portfolio, not merely each case against its own expectations.
    for case in cases:
        for private in [root, Path(value["worker"]["home"]), Path(value["worker"]["profile"]["smoke_attestation"]),
                        *[Path(c["requirements"]["path"]) for c in value["cases"]]]:
            discovery.check_output(case["config"]["discovery"], private)
        for grant in case["config"]["discovery"]["roots"]:
            require(root.resolve() not in Path(grant["path"]).resolve().parents,
                    "Discovery roots cannot be inside evaluation output")
            require(Path(value["worker"]["home"]).resolve() not in Path(grant["path"]).resolve().parents,
                    "Discovery roots cannot expose worker history or authentication")
    return value, cases


def _snapshot(config):
    """Freeze bounded exported trees without giving their contents to the model."""
    ledger = discovery.initial(config, {})
    pending = [dict(kind="list", root=r["id"], path=".") for r in config["roots"]]
    while pending:
        selector = pending.pop(0)
        ledger = discovery.apply(ledger, dict(kind="investigate", findings=[], unknowns=[], extractFacts=[],
                                             observe=[selector]), config, {}, "evaluation-snapshot")
        row = next(r for r in ledger["observations"] if r["selector"] == selector)
        if selector["kind"] == "list":
            for entry in row["entries"]:
                require(entry["kind"] in ("file", "directory"), "Evaluation exports contain an unsafe entry")
                path = entry["name"] if selector["path"] == "." else selector["path"] + "/" + entry["name"]
                pending.append(dict(kind="list" if entry["kind"] == "directory" else "read", root=selector["root"], path=path))
    discovery.verify(config, {}, ledger)
    return canonical(ledger)


def _expected(value, cases, repetitions, max_turns):
    return dict(formatVersion=1, portfolioSha256=digest(canonical(value)), authorityIdentity=authority_identity(),
                compilerDigest=compiler_fingerprint(cases[0]["config"]["planning"]),
                contractsSha256=digest(canonical(public_contracts(value["compiler"]["contracts"]))), repetitions=repetitions,
                maxTurns=max_turns, plannedTurns=len(cases) * repetitions,
                trials=[dict(id=f"repeat-{repeat}-{case['id']}", case=case["id"], repetition=repeat)
                        for repeat in range(1, repetitions + 1) for case in cases])


def _prepare(value, cases, root, repetitions, max_turns):
    """Create a new experiment only; verification paths never call preparation."""
    expected = _expected(value, cases, repetitions, max_turns)
    require(set(p.name for p in root.iterdir()) <= {"lock"}, "Incomplete evaluation preparation requires a new directory")
    inventory = {}
    save(root, "portfolio.json", canonical(value), inventory)
    for case in cases:
        prefix = "cases/" + case["id"] + "/"
        save(root, prefix + "config.json", canonical(case["config"]), inventory)
        save(root, prefix + "requirements.json", case["requirements"], inventory)
        save(root, prefix + "sources.json", _snapshot(case["config"]["discovery"]), inventory)
    plan_data = canonical(dict(expected, artifacts=inventory))
    atomic(root / "reservations.json", dict(formatVersion=1, planIdentity=digest(plan_data), trials=[]))
    save(root, "plan.json", plan_data, {})
    return _verify_plan(value, cases, root, repetitions, max_turns)


def _verify_plan(value, cases, root, repetitions, max_turns):
    """Validate retained experiment inputs without creating or repairing anything."""
    expected = _expected(value, cases, repetitions, max_turns)
    plan = loads(read_artifact(root / "plan.json"))
    keys(plan, (*expected, "artifacts"))
    require(canonical({k: plan[k] for k in expected}) == canonical(expected), "Evaluation configuration or authority changed")
    expected_paths = {"portfolio.json"} | {"cases/" + case["id"] + "/" + name for case in cases
                                         for name in ("config.json", "requirements.json", "sources.json")}
    require(isinstance(plan["artifacts"], dict) and set(plan["artifacts"]) == expected_paths,
            "Evaluation input inventory differs")
    for path, sha in plan["artifacts"].items():
        require(digest(read_artifact(root / path)) == sha, "Retained evaluation input changed")
    require(read_artifact(root / "portfolio.json") == canonical(value), "Retained evaluation portfolio changed")
    for case in cases:
        directory = root / "cases" / case["id"]
        original = next(c["requirements"]["path"] for c in value["cases"] if c["id"] == case["id"])
        require(read_artifact(original) == case["requirements"], "Independent evaluation requirements changed")
        require(read_artifact(directory / "config.json") == canonical(case["config"]) and
                read_artifact(directory / "requirements.json") == case["requirements"], "Retained evaluation case changed")
        discovery.verify(case["config"]["discovery"], {}, loads(read_artifact(directory / "sources.json")))
    return plan


def _reservations(root, plan):
    state = loads(read_artifact(root / "reservations.json"))
    keys(state, ("formatVersion", "planIdentity", "trials"))
    ids = [t["id"] for t in plan["trials"]]
    require(type(state["formatVersion"]) is int and state["formatVersion"] == 1 and
            state["planIdentity"] == digest(canonical(plan)) and isinstance(state["trials"], list) and
            state["trials"] == ids[:len(state["trials"])] and len(state["trials"]) <= len(ids),
            "Evaluation turn reservations are inconsistent")
    trials = root / "trials"
    if trials.exists():
        require(trials.is_dir() and not trials.is_symlink() and
                set(p.name for p in trials.iterdir()) <= set(state["trials"]), "Unreserved evaluation trial exists")
    return state


def _reserve(root, plan, trial):
    state = _reservations(root, plan)
    require(len(state["trials"]) < len(plan["trials"]) and
            plan["trials"][len(state["trials"])]["id"] == trial["id"], "Evaluation reservation is out of order")
    # Do not mutate an in-memory counter before durable publication succeeds.
    # If fsync fails after replacement, recovery reads the actual journal again.
    atomic(root / "reservations.json", dict(state, trials=[*state["trials"], trial["id"]]))


def _completed(case, root, trial):
    directory = root / "trials" / trial["id"]
    worker_root = directory / "worker"
    require(worker_root.is_dir(), "Reserved evaluation trial has no worker receipt")
    with exclusive_lock(worker_root / "lock"):
        receipt = recover_handoff(case["config"], worker_root)
    result = receipt["result"]
    selected = result["candidate"]
    independent = None
    if result["status"] == "awaiting-independent-review":
        independent = planning_review.recover_review(case["config"], worker_root,
            root / "cases" / case["id"] / "requirements.json", directory / "review")
        require(independent["planningReceiptSha256"] == digest(canonical(receipt)), "Review belongs to another planning receipt")
    def artifact(name):
        data = read_artifact(worker_root / "planning" / name)
        require(digest(data) == result["artifacts"][name], "Evaluation metric evidence changed")
        return loads(data)
    drafts = {name.split("/")[0] for name in result["artifacts"] if name.endswith("/result.json")}
    codes = [artifact(name + "/result.json")["review"]["exitCode"]
             for name in sorted(drafts)]
    observations = artifact("observations.json")["observations"]
    invalid_review = independent is not None and independent["review"]["exitCode"] == 2
    status = "blocked"
    if invalid_review:
        status = "incomplete-or-invalid"
    elif independent and independent["status"] == "requirements-satisfied":
        status = "requirements-satisfied"
    row = dict(**trial, domain=case["domain"], status=status,
                compilerAccepted=result["status"] == "awaiting-independent-review",
                requestFidelity=independent["review"]["requestFidelity"] if independent else "unassessed",
                reviewExitCode=independent["review"]["exitCode"] if independent else None,
                candidateId=selected["candidateId"] if selected else None, checkedDrafts=len(drafts),
                firstDraftCompilerAccepted=bool(codes and codes[0] == 0),
                rejectedDrafts=sum(code in (2, 4) for code in codes), additionalDrafts=max(0, len(codes) - 1),
                observationCount=len(observations), toolCalls=receipt["worker"]["tool_calls"],
                reportedUsage=receipt["usage"], planningReceiptSha256=digest(canonical(receipt)),
                reviewSha256=digest(canonical(independent)) if independent else None)
    if invalid_review:
        row["diagnostic"] = dict(code="INDEPENDENT_REVIEW_INPUT_ERROR",
                                 message="The canonical review could not assess this trial; it is not a measured rejection")
    return row


def _diagnostic(error):
    if isinstance(error, WorkerIndeterminate):
        return dict(code="WORKER_INCOMPLETE", message="The reserved worker turn has no completed receipt; automatic replay is forbidden")
    return dict(code="EVALUATION_EVIDENCE_INVALID", message=str(error) if isinstance(error, HostError) else
                "Worker preparation or retained evidence could not be verified; inspect the private records")


def _verify_inputs(value, cases, root, plan):
    current = _verify_plan(value, cases, root, plan["repetitions"], plan["maxTurns"])
    require(canonical(current) == canonical(plan), "Evaluation plan changed")


def _summary(value, cases, root, plan, failure=None):
    _verify_inputs(value, cases, root, plan)
    state = _reservations(root, plan)
    by_id = {case["id"]: case for case in cases}
    rows = []
    for trial in plan["trials"]:
        if trial["id"] not in state["trials"]:
            rows.append(dict(**trial, domain=by_id[trial["case"]]["domain"], status="not-started"))
            continue
        try:
            rows.append(_completed(by_id[trial["case"]], root, trial))
        except (HostError, WorkerError, OSError, ValueError, TypeError, KeyError) as error:
            rows.append(dict(**trial, domain=by_id[trial["case"]]["domain"], status="incomplete-or-invalid",
                             diagnostic=_diagnostic(error)))
    completed = sum(r["status"] in ("blocked", "requirements-satisfied") for r in rows)
    accepted = sum(r["status"] == "requirements-satisfied" for r in rows)
    domains = {}
    for domain in sorted({r["domain"] for r in rows}):
        selected = [r for r in rows if r["domain"] == domain]
        domains[domain] = dict(plannedRuns=len(selected),
                              completedRuns=sum(r["status"] in ("blocked", "requirements-satisfied") for r in selected),
                              requirementsSatisfied=sum(r["status"] == "requirements-satisfied" for r in selected))
    result = dict(formatVersion=1, evidenceKind="configured-worker-evaluation", planSha256=digest(read_artifact(root / "plan.json")),
                  status="complete" if completed == len(rows) else "incomplete", plannedTurns=len(rows),
                  reservedTurns=len(state["trials"]), completedRuns=completed, requirementsSatisfied=accepted,
                  requirementsSatisfiedRate=accepted / len(rows), domains=domains, runs=rows,
                  model=cases[0]["config"]["worker"]["model"],
                  liveModelProvenance="requires-independent-verification", naturalLanguageCompleteness="unassessed",
                  sourceInterpretationQuality="requires-independent-review",
                  preparationDiagnostic=_diagnostic(failure[1]) if failure and failure[0] not in state["trials"] else None,
                  workflowExecution=False, milestoneComplete=False)
    # The full experiment includes unread exports and the journal, not just the
    # files touched while recovering each worker. Revalidate after aggregation.
    _verify_inputs(value, cases, root, plan)
    require(canonical(_reservations(root, plan)) == canonical(state), "Evaluation reservations changed during summary")
    atomic(root / "evaluation-report.json", result)
    return result


def evaluate(value, directory, *, repetitions, max_turns, summary_only=False):
    """Resume completed receipts, but never replace a reserved uncertain turn."""
    root = Path(directory).absolute()
    value, cases = _portfolio(value, root, repetitions, max_turns)
    require(all(not p.is_symlink() for p in (root, *root.parents)), "Evaluation output path is unsafe")
    if summary_only:
        require((root / "plan.json").is_file(), "No retained evaluation exists")
    else:
        root.mkdir(parents=True, mode=0o700, exist_ok=True)
    with exclusive_lock(root / "lock"):
        if (root / "plan.json").exists():
            plan = _verify_plan(value, cases, root, repetitions, max_turns)
        else:
            require(not summary_only, "No retained evaluation exists")
            plan = _prepare(value, cases, root, repetitions, max_turns)
        if summary_only:
            return _summary(value, cases, root, plan)
        by_id = {case["id"]: case for case in cases}
        prepared = False
        for trial in plan["trials"]:
            case = by_id[trial["case"]]
            directory = root / "trials" / trial["id"]
            reserved = trial["id"] in _reservations(root, plan)["trials"]
            try:
                # Every case export is frozen across the whole experiment, including
                # files the agent has not yet chosen to inspect.
                _verify_inputs(value, cases, root, plan)
                if reserved:
                    # No intent means dispatch is ambiguous at the outer crash boundary.
                    require((directory / "worker/intent.json").is_file(), "Reserved trial cannot be dispatched again")
                else:
                    if not prepared:
                        worker, _ = bound_worker(case["config"], directory / "worker")
                        worker.preflight(model=case["config"]["worker"]["model"])
                        capability_catalog(dict(case["config"]["planning"], budget=case["config"]["planning"]["limits"]))
                        _verify_inputs(value, cases, root, plan)
                        prepared = True
                    _reserve(root, plan, trial)
                receipt = run(case["config"], directory / "worker")
                if receipt["result"]["status"] == "awaiting-independent-review":
                    requirements = root / "cases" / case["id"] / "requirements.json"
                    if (directory / "review").exists():
                        planning_review.recover_review(case["config"], directory / "worker", requirements,
                                                       directory / "review", complete=True)
                    else:
                        # A partial output is deliberately not overwritten or retried.
                        planning_review.review(case["config"], directory / "worker", requirements, directory / "review")
                row = _completed(case, root, trial)
                if row["status"] == "incomplete-or-invalid":
                    return _summary(value, cases, root, plan)
            except (HostError, WorkerError, OSError, ValueError, TypeError, KeyError) as error:
                return _summary(value, cases, root, plan, failure=(trial["id"], error))
        return _summary(value, cases, root, plan)
