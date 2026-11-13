"""Repeat task-driven discovery; keep compiler outcomes separate from caller assessment.

This tool never supplies candidate proposals, approves candidates, executes workflows
or certifies that an operator-declared held-out environment was unseen by a model.
"""
from __future__ import annotations

from contextlib import contextmanager
import copy
import fcntl
import os
from pathlib import Path
import uuid

from . import discovery_suite, investigation
from .contracts import (HostError, INSTRUCTIONS, MAX_BYTES, PROMPT_VERSION, canonical, checkpoint, contains_credential, digest, integer, keys, loads,
                        nonblank, public_contracts, read, require, save, validate_config, verify_inventory)
from .session import review_discovery, start
from .transport import capability_catalog, compiler_fingerprint, credentials

FATAL = {"provider-error", "provider-or-compiler-error", "provider-budget-overrun", "compiler-error",
         "caller-input-error", "source-changed"}
STOPPED = {"awaiting-user", "investigation-blocked", "discovery-binding-blocked", "unsupported", "target-blocked"}


@contextmanager
def locked(root):
    with (root / "lock").open("a+b") as lock:
        os.chmod(root / "lock", 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def _state(root):
    state = loads(read(root / "state.json"))
    require(state.get("formatVersion") == 1 and state.get("kind") == "discovery-evaluation", "Unsupported discovery evaluation")
    verify_inventory(root, state)
    for case in state["cases"]:
        for source in case["roots"]:
            files, directories = discovery_suite.snapshot(root / source["directory"])
            require(discovery_suite.inventory(files, directories) == source["inventory"], "Captured exposure changed")
    return state


def _unchanged_compiler(config, state):
    require(compiler_fingerprint(config) == state["compilerDigest"] and
            digest(canonical(public_contracts(config["compiler"]["contracts"]))) == state["publicContractsSha256"],
            "Evaluation compiler or public contracts changed")


def capture(config, suite_path, directory, *, repetitions=3, max_cost_microusd, target=None, render=False):
    integer(repetitions, 1, 10); integer(max_cost_microusd, 1)
    config = validate_config(loads(canonical(config)))
    require("investigation" not in config, "Evaluation exposure comes only from the reviewed suite")
    require(not render or target, "Rendering requires an explicit target")
    if target is not None:
        nonblank(target)
    raw, suite, cases = discovery_suite.load_suite(suite_path)
    count = len(cases) * repetitions
    require(count <= discovery_suite.MAX_RUNS, "Discovery evaluation run limit exceeded")
    reserved = count * config["budget"]["maxCostMicrousd"]
    require(reserved <= max_cost_microusd, "Suite budget cannot reserve every scheduled run")
    environment = credentials(config)
    inputs = [canonical(config), raw, *[c[k] for c in cases for k in ("request", "requirements")],
              *[data for c in cases for r in c["roots"] for data in r["files"].values()]]
    require(not any(contains_credential(data, environment) for data in inputs), "Evaluation inputs contain a credential")
    root = Path(directory).absolute()
    require(not root.exists(), "Evaluation output must not exist; interrupted calls are never replayed")
    require(all(Path(r["path"]) != root and Path(r["path"]) not in root.parents for c in cases for r in c["roots"]),
            "Evaluation output must be outside all source exposure roots")
    state = dict(formatVersion=1, kind="discovery-evaluation", evaluationId=uuid.uuid4().hex, status="preparing",
                 files={}, suiteId=suite["id"], repetitions=repetitions, expectedRuns=count,
                 evidenceKind=config["provider"]["evidenceKind"], provider=config["provider"],
                 compilerDigest=compiler_fingerprint(config), compilerCommit=config["compiler"]["commit"],
                 publicContractsSha256=digest(canonical(public_contracts(config["compiler"]["contracts"]))),
                 reservedSuiteCostMicrousd=reserved, maxSuiteCostMicrousd=max_cost_microusd,
                 target=target, render=render, cases=[], runs=[])
    contracts = public_contracts(config['compiler']['contracts'])
    contracts['capabilityCatalog'] = capability_catalog(config)
    instructions = (INSTRUCTIONS + '\n\n' + investigation.instructions(dict(formatVersion=3))).encode()
    require(not any(contains_credential(data, environment) for data in (canonical(contracts), instructions)),
            'Evaluation public inputs contain a credential')
    _unchanged_compiler(config, state)
    state['commonSettings'] = dict(contractsSha256=digest(canonical(contracts)), instructionsSha256=digest(instructions),
                                  compilerDigest=state['compilerDigest'], promptVersion=PROMPT_VERSION)
    root.mkdir(mode=0o700, parents=False, exist_ok=False)
    with locked(root):
        save(root, "suite.json", raw, state["files"])
        save(root, "base-config.json", canonical(config), state["files"])
        save(root, 'contracts.json', canonical(contracts), state['files'])
        save(root, 'instructions.txt', instructions, state['files'])
        for case in cases:
            meta = case["metadata"]
            captured = {k: meta[k] for k in ("id", "domain", "environment", "exposure")}
            captured["roots"] = []
            captured['exposureSha256'] = case['exposureSha256']
            for field in ("request", "requirements"):
                name = f"inputs/{meta['id']}/{field}.json"
                save(root, name, case[field], state["files"]); captured[field] = name
            host = copy.deepcopy(config)
            host["investigation"] = dict(formatVersion=3, roots=[], **suite["discoveryLimits"])
            for source in case["roots"]:
                name = f"exposure/{meta['id']}/{source['id']}"
                (root / name).mkdir(mode=0o700, parents=True)
                for subdir in source["directories"]:
                    (root / name / subdir).mkdir(mode=0o700, parents=True, exist_ok=True)
                for filename, data in source["files"].items():
                    save(root, name + "/" + filename, data, state["files"])
                captured["roots"].append(dict(id=source["id"], directory=name, inventory=source["inventory"]))
                host["investigation"]["roots"].append(dict(id=source["id"], path=str(root / name), scope=source["scope"]))
            captured["config"] = f"inputs/{meta['id']}/config.json"
            save(root, captured["config"], canonical(host), state["files"])
            state["cases"].append(captured)
        # Reserve the whole suite before the first model dispatch. No refund after uncertainty.
        for repetition in range(1, repetitions + 1):
            for case in state["cases"]:
                state["runs"].append(dict(id=f"repeat-{repetition}-{case['id']}", case=case["id"],
                                         repetition=repetition, phase="planned"))
        state["status"] = "capturing"; checkpoint(root, state)
        for run in state["runs"]:
            _state(root); _unchanged_compiler(config, state)
            case = next(c for c in state["cases"] if c["id"] == run["case"])
            run["phase"] = "capturing"; checkpoint(root, state)
            try:
                output = root / "runs" / run["id"]
                output.parent.mkdir(mode=0o700, exist_ok=True)
                result = start(loads(read(root / case["config"])), read(root / case["request"]),
                               read(root / case["requirements"]), output, target=target, render=render,
                               expected_settings=dict(state['commonSettings'], configSha256=state['files'][case['config']]))
                captured_state = read(output / 'state.json')
                captured_name = f"captures/{run['id']}.json"
                save(root, captured_name, captured_state, state['files'])
                run.update(phase="captured", stateSha256=digest(captured_state), capturedStateFile=captured_name)
                checkpoint(root, state)
                if not result['attempts'] or result["status"] in FATAL:
                    state["status"] = "capture-stopped"; checkpoint(root, state)
                    break
            except (HostError, OSError, ValueError, KeyError, TypeError):
                # Keep the uncertain run and reservation. A new directory is required for new calls.
                state["status"] = "capture-interrupted"; checkpoint(root, state)
                raise
        else:
            state["status"] = "capture-complete"; checkpoint(root, state)
        return _summarize(root, _state(root))


def _run_state(root, evaluation, run):
    case = next(c for c in evaluation["cases"] if c["id"] == run["case"])
    require(len(discovery_suite.relative(run['id'])) == 1 and run['id'] == f"repeat-{run['repetition']}-{case['id']}",
            'Invalid evaluation run directory')
    directory = root / "runs" / run["id"]
    data = read(directory / "state.json")
    require(digest(data) == run["stateSha256"], "Evaluation run changed outside its review channel")
    require(run['capturedStateFile'] in evaluation['files'] and
            evaluation['files'][run['capturedStateFile']] == run.get('assessedInputStateSha256', run['stateSha256']),
            'Captured evaluation state changed')
    state = loads(data)
    verify_inventory(directory, state)
    require(state["hostVersion"] == "bounded-authoring-host/5" and
            state['settings'] == dict(evaluation['commonSettings'], configSha256=evaluation['files'][case['config']]) and
            state['files']['contracts.json'] == evaluation['files']['contracts.json'] and
            state['files']['instructions.txt'] == evaluation['files']['instructions.txt'] and
            state["files"]["config.json"] == evaluation["files"][case["config"]] and
            state["files"]["request.json"] == evaluation["files"][case["request"]] and
            state["files"]["requirements-0.json"] == evaluation["files"][case["requirements"]] and
            state["requirementsFile"] == "requirements-0.json" and state["clarificationCount"] == 0 and
            state["compilerDigest"] == evaluation["compilerDigest"] and
            state["evidenceKind"] == evaluation["evidenceKind"] and state["target"] == evaluation["target"] and
            state["render"] == evaluation["render"], "Evaluation run does not match frozen inputs")
    return directory, state


def _assessment(value):
    keys(value, ("goal", "constraints", "discovery", "rationale"))
    require(value["goal"] in ("fulfilled", "appropriate-stop", "failed", "unassessed"), "Invalid goal assessment")
    require(value["constraints"] in ("respected", "violated", "unassessed"), "Invalid constraint assessment")
    require(value["discovery"] in ("sufficient", "insufficient", "unassessed"), "Invalid discovery assessment")
    nonblank(value["rationale"])


def review(directory, review_data):
    """Explicit caller review; neither an automatic judge nor another provider call."""
    root = Path(directory).absolute()
    with locked(root):
        state = _state(root)
        require(state["status"] in ("capture-complete", "capture-stopped", "reviewing", "reviewed") and
                not any(r["phase"] in ("capturing", "reviewing") for r in state["runs"]),
                "Interrupted evaluation cannot replay a capture or review")
        value = loads(review_data)
        keys(value, ("formatVersion", "evaluationId", "reviewerId", "runs"))
        require(type(value["formatVersion"]) is int and value["formatVersion"] == 1 and
                value["evaluationId"] == state["evaluationId"], "Review belongs to another evaluation")
        nonblank(value["reviewerId"])
        config = loads(read(root / "base-config.json"))
        require(not contains_credential(review_data, credentials(config)), "Evaluation review contains a credential")
        require(isinstance(value["runs"], list) and 1 <= len(value["runs"]) <= len(state["runs"]), "Invalid review run count")
        prepared, ids = [], set()
        for item in value["runs"]:
            keys(item, ("run", "stateSha256", "assessment"), ("decision",))
            require(isinstance(item["run"], str) and item["run"] not in ids, "Invalid or duplicate reviewed run")
            ids.add(item["run"])
            run = next((r for r in state["runs"] if r["id"] == item["run"]), None)
            require(run is not None and run["phase"] == "captured" and run["stateSha256"] == item["stateSha256"],
                    "Review must bind one unreviewed captured state")
            output, captured = _run_state(root, state, run)
            _assessment(item["assessment"])
            if captured["status"] == "awaiting-discovery-review":
                from .discovery_review import validate_decision
                require(isinstance(item.get('decision'), dict) and item["decision"].get("reviewerId") == value["reviewerId"],
                        "Separate discovery decision with matching reviewer attribution required")
                attempt = captured["attempts"][-1]
                validate_decision(item["decision"], loads(read(output / attempt["discoveryReviewPacket"])),
                                  attempt["discoveryReviewPacketSha256"])
            else:
                require("decision" not in item, "A blocked run has no candidate to approve")
            prepared.append((run, item, output, captured))
        name = f"reviews/{uuid.uuid4().hex}.json"
        save(root, name, review_data, state["files"])
        state["status"] = "reviewing"; checkpoint(root, state)
        for run, item, output, captured in prepared:
            _state(root)
            run.update(phase="reviewing", reviewFile=name); checkpoint(root, state)
            if "decision" in item:
                review_discovery(output, canonical(item["decision"]))
            run.update(phase="reviewed", stateSha256=digest(read(output / "state.json")),
                       assessment=item["assessment"], reviewerId=value["reviewerId"],
                       assessedInputStateSha256=item["stateSha256"])
            checkpoint(root, state)
        if all(r["phase"] == "reviewed" for r in state["runs"]):
            state["status"] = "reviewed"; checkpoint(root, state)
        return _summarize(root, _state(root))


def summarize(directory):
    root = Path(directory).absolute()
    with locked(root):
        return _summarize(root, _state(root))


def _summarize(root, evaluation):
    rows = []
    for run in evaluation["runs"]:
        case = next(c for c in evaluation["cases"] if c["id"] == run["case"])
        row = {k: run[k] for k in ("id", "case", "repetition", "phase")}
        row.update({k: case[k] for k in ("domain", "environment", "exposure")})
        row['exposureSha256'] = case['exposureSha256']
        if run["phase"] not in ("captured", "reviewed"):
            row.update(status="not-captured" if run["phase"] == "planned" else "interrupted",
                       compilerAccepted=False, callerAssessedSuccess=False)
            rows.append(row); continue
        directory, state = _run_state(root, evaluation, run)
        ledger = loads(read(directory / state["investigationFile"]))
        attempts = state["attempts"]
        reviews = [a["review"] for a in attempts if "review" in a]
        assessment = run.get("assessment")
        if assessment is not None:
            record = loads(read(root / run["reviewFile"]))
            entry = next(i for i in record["runs"] if i["run"] == run["id"])
            require(record["reviewerId"] == run["reviewerId"] and entry["assessment"] == assessment and
                    entry["stateSha256"] == run["assessedInputStateSha256"], "Independent assessment changed")
        accepted = state["status"] == "accepted"
        success = bool(assessment and assessment["constraints"] == "respected" and assessment["discovery"] == "sufficient" and
                       ((accepted and assessment["goal"] == "fulfilled") or
                        (state["status"] in STOPPED and assessment["goal"] == "appropriate-stop")))
        observed = [dict(selector=o["selector"], sha256=o["sha256"]) for o in ledger["observations"] if o["selector"]["kind"] == "read"]
        unknowns = [u for u in ledger['unknowns'] if u['status'] == 'open']
        estimates = [a["estimatedCostMicrousd"] for a in attempts if "estimatedCostMicrousd" in a]
        row.update(stateSha256=run["stateSha256"], status=state["status"], compilerAccepted=accepted,
                   capturedStateFile=run['capturedStateFile'],
                   callerAssessment=assessment, callerAssessedSuccess=success,
                   acceptedConstraintViolation=bool(accepted and assessment and assessment["constraints"] == "violated"),
                   investigationFile=f"runs/{run['id']}/" + state['investigationFile'],
                   readObservationCount=len(observed), readSetSha256=digest(canonical(sorted(observed, key=canonical))),
                   requestSha256=evaluation["files"][case["request"]],
                   openUnknownsByCategory={category: sum(u['category']==category for u in unknowns)
                                          for category in ('fact', 'decision', 'capability', 'conflict')},
                   blockingOpenUnknowns=sum(u['blocking'] for u in unknowns),
                   extractedFacts=len(ledger["facts"]), dispatchedCalls=len(attempts),
                   investigationSteps=state['investigationSteps'],
                   reportedProviderCalls=sum('provider' in a for a in attempts),
                   unreportedCalls=sum('provider' not in a for a in attempts),
                   graphDigest=reviews[-1].get("graphDigest") if reviews else None,
                   renderAuthorized=bool(reviews and reviews[-1].get("renderAuthorized")),
                   reservedTokens=state["reservedTokens"], reservedCostMicrousd=state["reservedCostMicrousd"],
                   reportedUsage=state["reportedUsage"], estimatedReportedCostMicrousd=sum(estimates) if estimates else None,
                   reportedCostComplete=bool(attempts) and len(estimates) == len(attempts),
                   providerLatencyMs=sum(a.get("latencyMs", 0) for a in attempts),
                   compilerLatencyMs=sum(a.get('compilerLatencyMs', 0) for a in attempts))
        if state["status"] == "awaiting-discovery-review":
            row["reviewPacket"] = f"runs/{run['id']}/" + attempts[-1]["discoveryReviewPacket"]
            row["reviewPacketSha256"] = attempts[-1]["discoveryReviewPacketSha256"]
            row["runId"] = state["runId"]
        rows.append(row)
    domains = {}
    for domain in sorted({r["domain"] for r in rows}):
        group = [r for r in rows if r["domain"] == domain]
        domains[domain] = dict(expectedRuns=len(group), compilerAccepted=sum(r["compilerAccepted"] for r in group),
                               callerAssessedSuccesses=sum(r["callerAssessedSuccess"] for r in group))
    comparisons = []
    for environment in sorted({r["environment"] for r in rows}):
        group = [r for r in rows if r["environment"] == environment and "requestSha256" in r]
        comparisons.append(dict(environment=environment, cases=sorted({r["case"] for r in group}),
            distinctRequests=len({r["requestSha256"] for r in group}),
            distinctReadSets=len({r['readSetSha256'] for r in group}),
            distinctAcceptedGraphs=len({r["graphDigest"] for r in group if r["compilerAccepted"]}),
            qualityRequiresIndependentAssessment=True))
    result = dict(formatVersion=1, evaluationId=evaluation["evaluationId"], status=evaluation["status"],
        evidenceKind=evaluation["evidenceKind"], providerAttribution="operator-configured-and-adapter-reported-not-attested",
        provider={k: evaluation['provider'][k] for k in ('id', 'model', 'version')},
        commonSettings=evaluation['commonSettings'],
        compilerCommit=evaluation["compilerCommit"], compilerDigest=evaluation["compilerDigest"],
        suiteSha256=evaluation["files"]["suite.json"], runs=rows, domains=domains, taskComparisons=comparisons,
        completedRuns=sum(r["phase"] in ("captured", "reviewed") for r in rows), expectedRuns=len(rows),
        reviewedRuns=sum(r["phase"] == "reviewed" for r in rows), compilerAccepted=sum(r["compilerAccepted"] for r in rows),
        callerAssessedSuccesses=sum(r["callerAssessedSuccess"] for r in rows),
        acceptedConstraintViolations=sum(r.get("acceptedConstraintViolation", False) for r in rows),
        reservedSuiteCostMicrousd=evaluation["reservedSuiteCostMicrousd"],
        recordedRunReservationsMicrousd=sum(r.get("reservedCostMicrousd", 0) for r in rows),
        reportedLiveCalls=sum(r.get("reportedProviderCalls", 0) for r in rows) if evaluation["evidenceKind"] == "live" else 0,
        uncertainRuns=sum(r["status"] == "interrupted" for r in rows),
        heldOutExposureAttribution="caller-declared-not-attested", independentQualityAttribution="caller-declared-not-authenticated",
        minimumThreeRepetitions=evaluation["repetitions"] >= 3, generalizationEstablished=False,
        workflowWorkExecuted=False, milestoneComplete=False)
    temp = root / "discovery-evaluation-report.tmp"
    report_data = canonical(result)
    require(len(report_data) <= MAX_BYTES, 'Evaluation report exceeds byte limit')
    with temp.open("wb") as output:
        os.chmod(temp, 0o600); output.write(report_data); output.flush(); os.fsync(output.fileno())
    os.replace(temp, root / "discovery-evaluation-report.json")
    return result
