#!/usr/bin/env python3
"""Installed planning, dispatch and outcome commissioning on a disposable Kubernetes control plane."""
import argparse
import base64
from copy import deepcopy
from datetime import timedelta
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile

from authoring_host.contracts import canonical, digest, loads, require
from kubernetes_provider.common import now
from tests_worker.kubernetes_cluster import start
from tests_worker.kubernetes_resources import CASES, CONFIG, SCALE, RETENTION
from tests_worker.test_planning_worker import configuration, commission_fixture, script, step

ROOT = Path(__file__).resolve().parents[1]
PROVIDER = str(ROOT / "tools/kubernetes_resource_dispatch.py")
LEGACY = str(ROOT / "tools/kubernetes_configmap_dispatch.py")


def write(path, document):
    path.write_bytes(canonical(document))
    return str(path)


def command(args, expected=0, document=None):
    proc = subprocess.run(args, input=canonical(document) if document is not None else None, capture_output=True, timeout=150)
    require(proc.returncode == expected, f"{Path(args[0]).name} {Path(args[1]).name}: expected {expected}, got {proc.returncode}. "
            + proc.stderr.decode()[-700:] + proc.stdout.decode()[-500:])
    return loads(proc.stdout) if proc.stdout.strip().startswith(b"{") else None


def prepare(work, cli, case):
    proposal = loads((ROOT / "conformance/operation-preconditions/infrastructure.proposal.json").read_bytes())
    source = work / "source.json"
    write(source, case.source)
    source_ref = "sha256:" + digest(source.read_bytes())
    request = dict(userText=f"Sync {source_ref} to {case.address} after approval using mode {case.mode}.", mode="DRAFT", context={})
    approval = dict(id="authorize", capability="APPROVE")
    sync = dict(id="update-" + case.name, capability="SYNC", requires=["authorize"],
                params=dict(destination=case.identity, mode=case.mode, source=source_ref))
    workflow = dict(name="change", kind="CUSTOM")
    if case.version == "2.0":
        workflow["steps"] = [approval, sync]
    else:
        workflow["body"] = [dict(item, kind="operation") for item in (approval, sync)]
    intent = dict(kind="FlowIntentDocument", name="commission-" + case.name, intentVersion=case.version, workflows=[workflow])
    if case.version == "2.0":
        workflow["kind"] = "SYNC"
        intent.update(description="Reconcile declared worker capacity.", policies=[], failure=dict(stopOnError=True, notify=False, rollback=False))
    proposal["response"]["normalizedIntent"] = intent
    cfg = configuration(work, [str(cli)])
    cfg["planning"]["task"]["request"] = request
    commission_fixture(cfg, work / "commission")
    script(work, [dict(step("flow_check_plan", response=proposal["response"]), id="checked")])
    worker = write(work / "worker.json", cfg)
    selector = dict(capability="SYNC")
    goal = write(work / "goal.json", dict(formatVersion=1, request=request, origin="REVIEWER_AUTHORED",
        confirmation="CONFIRMED", reviewerId="reviewer", requirements=[
            dict(id="update", sourceQuote="Sync", check=dict(kind="capability-count", selector=selector, minimum=1, maximum=1)),
            dict(id="source", sourceQuote=source_ref, check=dict(kind="literal-parameter", selector=selector, parameter="source", expected=source_ref)),
            dict(id="mode", sourceQuote=case.mode, check=dict(kind="literal-parameter", selector=selector, parameter="mode", expected=case.mode)),
            dict(id="approval", sourceQuote="after approval", check=dict(kind="before", before=dict(capability="APPROVE"), after=selector))]))
    run, review = str(work / "run"), str(work / "review")
    planning = [sys.executable, str(ROOT / "tools/agent_planning_worker.py"), "--config", worker, "--out", run]
    command(planning)
    command([*planning, "--review-requirements", goal, "--review-out", review])
    return ["--config", worker, "--run", run, "--review-requirements", goal, "--review-out", review]


def identity(work, cluster, case, role):
    actor = case.actor_prefix + role
    key = work / (actor + ".pem")
    subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(key)], check=True, capture_output=True)
    pub = subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"], check=True, capture_output=True)
    grant = dict(keyId=actor, publicKey=base64.b64encode(pub.stdout).decode(),
                 scopes=[case.identity["scope"]], resourceTypes=[case.identity["type"]])
    if role == "observer":
        grant["kinds"] = ["RESULT", "OWNERSHIP", "STATE"]
    trust = write(work / (actor + "-trust.json"), dict(formatVersion=1, grants=[grant]))
    cfg = dict(cluster="loopback", namespace="commissioning", name=case.name, server=cluster.server,
               caFile=str(cluster.work / "server.crt"), tokenFile=str(cluster.work / (actor + ".token")), signingKey=str(key), keyId=actor)
    if case.profile_sha:
        cfg["profileFile"] = write(work / (role + "-profile.json"), case.profile)
    elif not case.legacy:
        cfg["profile"] = case.profile
    return cfg, trust


def current(cluster, case):
    return loads(cluster.kubectl("get", case.address, "-n", "commissioning", "-o", "json").stdout)


def commission(work, cluster, cli, case, meta):
    fidelity_raw = (work / "review/review.json").read_bytes()
    fidelity = loads(fidelity_raw)
    graph = fidelity["review"]["graphDigest"]
    resource = dict(id="destination", identity=case.identity, disposition="MANAGED", owner="platform")
    selection = dict(selector=dict(capability="SYNC"), resource="destination", parameter="destination")
    expiry = (now() + timedelta(minutes=5)).isoformat()
    before = dict(formatVersion=1, assessmentId="before-" + case.name, graphDigest=graph, challenge=secrets.token_hex(32),
        issuedAt=now().isoformat(), expiresAt=expiry, reviewerId="reviewer", resources=[dict(resource, instanceId=meta["uid"])],
        operations=[dict(selection, id="current", facts=dict(resourceVersion=meta["resourceVersion"]), maxAgeSeconds=120)])
    if case is RETENTION:
        before["operations"][0]["facts"]["valuePresent"] = False
    requirements = write(work / "before.json", before)
    proposal = str(work / "review/proposal.json")
    command([str(cli), "preconditions", proposal, "--source-kind", "proposal", "--contracts", str(ROOT),
             "--requirements", requirements, "--out", str(work / "selected")], 3)
    selected, = loads((work / "selected/operation-precondition-review.json").read_bytes())["results"]
    require(selected["resource"] == case.identity and selected["code"] == "PRECONDITION_OBSERVATION_MISSING",
            "Compiler did not select the expected destination")
    policy = write(work / "policy.json", dict(formatVersion=1, reviewerId="reviewer", resources=[resource],
        operations=[dict(selection, id="updated", postconditions=case.expected)]))
    dispatch = dict(id="change-" + case.name, runtimeId="loopback-" + case.name, authorizationRef="operator-" + case.name)
    pins = dict(dispatchId=dispatch["id"], runtimeId=dispatch["runtimeId"], authorizationRef=dispatch["authorizationRef"],
                candidateId=fidelity["candidateId"], compilerDigest=fidelity["compilerDigest"],
                canonicalPlanSha256=fidelity["artifacts"]["compiler/canonical-execution-plan.json"], proposalSha256=fidelity["proposalSha256"],
                fidelityReviewSha256=digest(fidelity_raw))
    grant = dict(pins=pins, deadlineSemantics="submission", graphDigest=graph, nodeId=selected["nodeId"], owner="platform",
                 uid=meta["uid"], resourceVersion=meta["resourceVersion"], contractSha256=digest(canonical(before)), expiresAt=expiry)
    if case.profile_sha:
        grant["profileSha256"] = case.profile_sha
    ledger = work / "reservations"
    ledger.mkdir(mode=0o700)
    writer, trust = identity(work, cluster, case, "writer")
    writer.update(grant=write(work / "grant.json", grant), requirements=requirements, source=str(work / "source.json"),
                  proposal=proposal, reservationDirectory=str(ledger))
    entry = LEGACY if case.legacy else PROVIDER
    runtime = [sys.executable, entry, "runtime", "--config", write(work / "runtime-config.json", writer)]
    observer, audit_trust = identity(work, cluster, case, "observer")
    observer.update(writerUsername=case.actor_prefix + "writer", runtimeId=dispatch["runtimeId"], auditFile=str(work / "audit.jsonl"))
    collect = [sys.executable, entry, "observer", "--config", write(work / "observer-config.json", observer)]
    settings = dict(formatVersion=1, requirements=requirements, trust=trust, dispatch=dispatch,
        runtime=dict(argv=runtime, credentialEnv=[], timeoutSeconds=90), outcomeReview=dict(policy=policy, trust=audit_trust))
    return settings, collect


def race(work, common, cluster, case, settings):
    revision = current(cluster, case)["metadata"]["resourceVersion"]
    original = loads(Path(settings["requirements"]).read_bytes())
    op, = original["operations"]
    before = dict(original, challenge=secrets.token_hex(32), assessmentId="concurrent-update",
                  operations=[dict(op, facts=dict(resourceVersion=revision))])
    before_path = write(work / "race-before.json", before)
    runtime = settings["runtime"]["argv"]
    writer = loads(Path(runtime[-1]).read_bytes())
    grant = loads(Path(writer["grant"]).read_bytes())
    grant.update(contractSha256=digest(canonical(before)), resourceVersion=revision, pins=dict(grant["pins"], dispatchId="competing"))
    config = write(work / "race-writer.json", dict(writer, requirements=before_path, grant=write(work / "race-grant.json", grant)))
    relay = [sys.executable, str(ROOT / "tools/tests_worker/kubernetes_competitor.py"), runtime[1], config,
             str(cluster.bins / "kubectl"), str(cluster.work / "admin.json"), case.address, canonical(case.competitor).decode()]
    settings = dict(settings, requirements=before_path, dispatch=dict(settings["dispatch"], id="competing"),
                    runtime=dict(settings["runtime"], argv=relay))
    out = work / "race-dispatch"
    command([sys.executable, str(ROOT / "tools/dispatch_planning_worker.py"), *common, "--dispatch-config",
             write(work / "race-settings.json", settings), "--out", str(out)], 3)
    require(loads((out / "runtime-assessment.json").read_bytes())["currentState"] == "SATISFIED" and
            loads((out / "runtime-result.json").read_bytes())["status"] == "rejected", "Race missed the conditional write")
    records = map(loads, (cluster.work / "audit.log").read_bytes().splitlines())
    require(any(e.get("responseStatus", {}).get("code") == 409 and e["user"]["username"] == case.actor_prefix + "writer"
                for e in records), "No native conflict for the selected writer")


def assess(work, review, collect, request, name, status, code):
    bundle = command(collect, document=request)
    path = write(work / (name + ".json"), bundle)
    out = work / name
    command([*review, "assess", "--observations", path, "--out", str(out)], status)
    report = loads((out / "compiler/operation-outcome-review.json").read_bytes())
    require(report["results"][0]["code"] == code, f"Unexpected {name} outcome")


def challenge_evidence(work, review, collect, request, runtime):
    audit = work / "audit.jsonl"
    raw = audit.read_bytes()
    events = map(loads, raw.splitlines())
    event, = [e for e in events if e["auditID"] == request["receiptId"] and e["stage"] == "ResponseComplete"]
    for mutation in (dict(requestURI=event["requestURI"] + "?dryRun=All"), dict(user=dict(username="elsewhere")),
                     dict(auditID="other"), dict(impersonatedUser=dict(username="writer")),
                     dict(objectRef=dict(event["objectRef"], apiGroup="uncommissioned.example"))):
        write(audit, dict(event, **mutation))
        require(not command(collect, document=request)["observations"], "Uncorrelated audit event produced evidence")
    rewritten = dict(event["responseObject"], data=dict(mode="overridden-at-commit"))
    write(audit, dict(event, responseObject=rewritten))
    assess(work, review, collect, request, "commit-mismatch", 4, "OUTCOME_POSTCONDITION_FAILED")
    audit.write_bytes(b"")
    require(not command(collect, document=request)["observations"], "Empty export produced evidence")
    audit.write_bytes(raw + raw)
    assess(work, review, collect, request, "ambiguous", 3, "OUTCOME_OBSERVATION_MISSING")
    audit.write_bytes(raw)
    intent = loads((work / "dispatch/dispatch-intent.json").read_bytes())
    require(command(runtime, document=dict(intent, candidateId="unapproved")).get("reason") == "authorization",
            "Unapproved candidate admitted")
    forged = dict(intent, preconditions=dict(intent["preconditions"], challenge="changed"))
    require(command(runtime, document=forged).get("reason") == "authorization", "Changed observation challenge admitted")
    for path in (work / "source.json", work / "review/compiler/canonical-execution-plan.json"):
        saved = path.read_bytes()
        try:
            path.write_bytes(saved + b" ")
            require(command(runtime, document=intent).get("reason") == "unsupported", "Artifact substitution admitted")
        finally:
            path.write_bytes(saved)
    require(not command(collect, document=dict(request, decisionSha256="0" * 64))["observations"],
            "Substituted decision produced evidence")


def challenge_profile(work, case, collect, request, runtime):
    intent = loads((work / "dispatch/dispatch-intent.json").read_bytes())
    writer = loads(Path(runtime[-1]).read_bytes())
    alternate = dict(case.profile, fieldPath=["spec", "retention", "fallback"])
    paths = [Path(writer["profileFile"]), Path(writer["grant"]), work / "observer-profile.json"]
    originals = [path.read_bytes() for path in paths]
    writer_profile, grant_path, observer_profile = paths
    try:
        write(observer_profile, alternate)
        require(not command(collect, document=request)["observations"], "Another field reused the audit receipt")
        grant = loads(originals[1])
        write(grant_path, {key: value for key, value in grant.items() if key != "profileSha256"})
        require(command(runtime, document=intent).get("reason") == "authorization", "Missing field approval admitted")
        grant_path.write_bytes(originals[1])
        write(writer_profile, alternate)
        require(command(runtime, document=intent).get("reason") == "authorization", "Approval admitted another field")
        write(grant_path, dict(grant, profileSha256=digest(writer_profile.read_bytes())))
        require(command(runtime, document=intent).get("reason") == "unsupported", "Reviewed source admitted another field")
    finally:
        for path, raw in zip(paths, originals):
            path.write_bytes(raw)


def reject_substitutions(work, cli, case):
    # Same selected proposal and capability count. A different reviewer-approved
    # source must fail fidelity, and a different resource must fail effect binding.
    goal = loads((work / "goal.json").read_bytes())
    source = next(item for item in goal["requirements"] if item["id"] == "source")
    source["check"]["expected"] = "sha256:" + "f" * 64
    rejected = work / "wrong-source-review"
    command([sys.executable, str(ROOT / "tools/agent_planning_worker.py"), "--config", str(work / "worker.json"),
             "--out", str(work / "run"), "--review-requirements", write(work / "wrong-source.json", goal),
             "--review-out", str(rejected)], 4)
    report = loads((rejected / "compiler/request-fidelity-review.json").read_bytes())
    states = {item["id"]: item["state"] for item in report["requirements"]}
    require(states["update"] == "SATISFIED" and states["source"] == "VIOLATED", "Wrong source passed independent fidelity")
    before = loads((work / "before.json").read_bytes())
    before["resources"][0]["identity"] = dict(case.identity, key="another-object")
    out = work / "wrong-target-review"
    command([str(cli), "preconditions", str(work / "review/proposal.json"), "--source-kind", "proposal",
             "--contracts", str(ROOT), "--requirements", write(work / "wrong-target.json", before), "--out", str(out)], 4)
    result, = loads((out / "operation-precondition-review.json").read_bytes())["results"]
    require(result["code"] == "PRECONDITION_RESOURCE_MISMATCH", "Wrong target passed compiler binding")


def verify(work, cluster, cli, case):
    common = prepare(work, cli, case)
    original = current(cluster, case)
    settings, collect = commission(work, cluster, cli, case, original["metadata"])
    denied = cluster.kubectl("auth", "can-i", "update", case.address, "-n", "commissioning", actor=case.actor_prefix + "observer", check=False)
    require(denied.stdout.strip() == "no", "Observer can update the resource")
    if case == SCALE:
        reject_substitutions(work, cli, case)
        command([sys.executable, LEGACY, "observer", "--config", collect[-1]], 2)
        require(current(cluster, case)["metadata"]["resourceVersion"] == original["metadata"]["resourceVersion"],
                "Review changed the resource")
    args = [*common, "--dispatch-config", write(work / "dispatch-config.json", settings)]
    output = work / "dispatch"
    execute = [sys.executable, str(ROOT / "tools/dispatch_planning_worker.py"), *args, "--out", str(output)]
    command(execute)
    applied = current(cluster, case)
    applied_revision = applied["metadata"]["resourceVersion"]
    expected = deepcopy(original)
    container = expected
    for key in case.field[:-1]:
        container = container[key]
    container[case.field[-1]] = case.desired
    for obj in (applied, expected):
        # Compare the complete served object, excluding native accounting and our correlation marker.
        meta = obj["metadata"]
        for key in ("resourceVersion", "generation", "managedFields"):
            meta.pop(key, None)
        meta["annotations"].pop("horistum.com/dispatch-binding", None)
    require(applied == expected, "Update changed the wrong field or lost unrelated state")
    command(execute)
    require(current(cluster, case)["metadata"]["resourceVersion"] == applied_revision, "Recovery wrote again")
    review = [sys.executable, str(ROOT / "tools/review_dispatch_outcomes.py"), *args, "--dispatch-out", str(output)]
    request = command([*review, "request"])
    (work / "audit.jsonl").write_bytes((cluster.work / "audit.log").read_bytes())
    assess(work, review, collect, request, "committed", 0, "OUTCOME_SATISFIED")
    if case == CONFIG:
        challenge_evidence(work, review, collect, request, settings["runtime"]["argv"])
    if case is RETENTION:
        challenge_profile(work, case, collect, request, settings["runtime"]["argv"])
    race(work, common, cluster, case, settings)
    after = current(cluster, case)
    for key in case.field:
        after = after[key]
    require(after == case.competing, "Lost concurrent change")
    assess(work, review, collect, request, "drift", 4, "OUTCOME_POSTCONDITION_FAILED")
    if case == CONFIG:
        cluster.kubectl("delete", case.address, "-n", "commissioning")
        cluster.create(dict(apiVersion="v1", kind="ConfigMap", metadata=dict(name=case.name, namespace="commissioning"), data=case.source["data"]))
        assess(work, review, collect, request, "replacement", 3, "OUTCOME_OBSERVATION_MISSING")
    print(f"PASS {case.address}: Intent {case.version}, approved projection, native audit, recovery, conflict and independent drift assessment", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli", required=True, type=Path)
    parser.add_argument("--binaries", required=True, type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="flow-kubernetes-") as temporary:
        root = Path(temporary)
        with start(root, args.binaries.resolve()) as server:
            for scenario in CASES:
                directory = root / scenario.kind
                directory.mkdir()
                verify(directory, server, args.cli.resolve(), scenario)
