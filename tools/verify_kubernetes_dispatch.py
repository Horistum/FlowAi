#!/usr/bin/env python3
"""Commission the public dispatch/outcome CLIs against a disposable real Kubernetes API."""
import argparse
import base64
from datetime import timedelta
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile

from authoring_host.contracts import canonical, digest, loads, require
from kubernetes_configmap.common import now
from tests_worker.kubernetes_cluster import start
from tests_worker.test_planning_worker import configuration, commission_fixture, script, step

ROOT = Path(__file__).resolve().parents[1]
PROVIDER = str(ROOT / "tools/kubernetes_configmap_dispatch.py")
RESOURCE = dict(scope="loopback/commissioning", type="configmap", key="settings")


def write(path, document):
    path.write_bytes(canonical(document))
    return str(path)


def command(args, expected=0, document=None):
    proc = subprocess.run(args, input=canonical(document) if document is not None else None,
                          capture_output=True, timeout=150)
    require(proc.returncode == expected, f"{Path(args[1]).name}: expected {expected}, got {proc.returncode}. "
            + proc.stderr.decode()[-600:] + proc.stdout.decode()[-400:])
    return loads(proc.stdout)


def prepare(work, cli):
    proposal = loads((ROOT / "conformance/operation-preconditions/infrastructure.proposal.json").read_bytes())
    request = dict(userText="Update the existing settings ConfigMap after approval.", mode="DRAFT", context={})
    source = work / "desired-data.json"
    write(source, dict(data=dict(mode="reviewed")))
    intent = proposal["response"]["normalizedIntent"]
    intent["workflows"][0]["body"] = [
        dict(kind="operation", id="approval", capability="APPROVE"),
        dict(kind="operation", id="update", capability="SYNC", requires=["approval"],
             params=dict(destination=RESOURCE, mode="replace-text-data", source="sha256:" + digest(source.read_bytes())))]
    cfg = configuration(work, [str(cli)])
    cfg["planning"]["task"]["request"] = request
    commission_fixture(cfg, work / "commission")
    script(work, [dict(step("flow_check_plan", response=proposal["response"]), id="checked")])
    worker = write(work / "worker.json", cfg)
    goal = write(work / "goal.json", dict(formatVersion=1, request=request, origin="REVIEWER_AUTHORED",
        confirmation="CONFIRMED", reviewerId="reviewer", requirements=[dict(id="update", sourceQuote="Update the existing settings ConfigMap",
        check=dict(kind="capability-count", selector=dict(capability="SYNC"), minimum=1, maximum=1))]))
    run, review = str(work / "run"), str(work / "review")
    planning = [sys.executable, str(ROOT / "tools/agent_planning_worker.py"), "--config", worker, "--out", run]
    command(planning)
    command([*planning, "--review-requirements", goal, "--review-out", review])
    return ["--config", worker, "--run", run, "--review-requirements", goal, "--review-out", review]


def identity(work, cluster, actor):
    key = work / (actor + ".pem")
    subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(key)], check=True, capture_output=True)
    pub = subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"], check=True, capture_output=True)
    grant = dict(keyId=actor, publicKey=base64.b64encode(pub.stdout).decode(), scopes=[RESOURCE["scope"]], resourceTypes=["configmap"])
    if actor == "observer":
        grant["kinds"] = ["RESULT", "OWNERSHIP", "STATE"]
    trust = write(work / (actor + "-trust.json"), dict(formatVersion=1, grants=[grant]))
    cfg = dict(cluster="loopback", namespace="commissioning", name="settings", server=cluster.server,
               caFile=str(work / "server.crt"), tokenFile=str(work / (actor + ".token")), signingKey=str(key), keyId=actor)
    return cfg, trust


def current(cluster):
    return loads(cluster.kubectl("get", "cm", "settings", "-n", "commissioning", "-o", "json").stdout)


def race(work, common, cluster, original_settings, original_grant, original_contract, runtime):
    # Give the second dispatch its own current, independently pinned inputs.
    revision = current(cluster)["metadata"]["resourceVersion"]
    operation, = original_contract["operations"]
    before = dict(original_contract, challenge=secrets.token_hex(32), assessmentId="concurrent-update",
                  operations=[dict(operation, facts=dict(resourceVersion=revision))])
    before_path = write(work / "race-before.json", before)
    grant = dict(original_grant, contractSha256=digest(canonical(before)), resourceVersion=revision,
                 pins=dict(original_grant["pins"], dispatchId="concurrent-update"))
    writer = loads(Path(runtime[-1]).read_bytes())
    config = write(work / "race-writer.json", dict(writer, requirements=before_path,
                   grant=write(work / "race-grant.json", grant)))
    relay = [sys.executable, str(ROOT / "tools/tests_worker/kubernetes_competitor.py"), PROVIDER, config,
             str(cluster.bins / "kubectl"), str(work / "admin.json")]
    settings = dict(original_settings, requirements=before_path, dispatch=dict(original_settings["dispatch"], id="concurrent-update"),
                    runtime=dict(original_settings["runtime"], argv=relay))
    out = work / "race-dispatch"
    command([sys.executable, str(ROOT / "tools/dispatch_planning_worker.py"), *common, "--dispatch-config",
             write(work / "race-settings.json", settings), "--out", str(out)], 3)
    require(loads((out / "runtime-assessment.json").read_bytes())["currentState"] == "SATISFIED" and
            loads((out / "runtime-result.json").read_bytes())["status"] == "rejected", "Race missed the conditional write")
    require(current(cluster)["data"] == dict(mode="concurrent"), "Lost concurrent change")
    records = [loads(line) for line in (cluster.work / "audit.log").read_bytes().splitlines()]
    require(any(e.get("responseStatus", {}).get("code") == 409 and e["user"]["username"] == "writer" for e in records),
            "No native revision conflict")


def verify(work, cli, binaries):
    common = prepare(work, cli)
    with start(work, binaries) as cluster:
        original = current(cluster)
        meta = original["metadata"]
        writer, trust = identity(work, cluster, "writer")
        observer, audit_trust = identity(work, cluster, "observer")
        check = cluster.kubectl("auth", "can-i", "update", "configmaps/settings", "-n", "commissioning",
                                actor="observer", check=False)
        require(check.stdout.strip() == "no", "Observer identity can update the resource")
        fidelity_raw = (work / "review/review.json").read_bytes()
        fidelity = loads(fidelity_raw)
        graph = fidelity["review"]["graphDigest"]
        resource = dict(id="settings", identity=RESOURCE, disposition="MANAGED", owner="platform")
        selection = dict(selector=dict(capability="SYNC"), resource="settings", parameter="destination")
        expiry = (now() + timedelta(minutes=5)).isoformat()
        before = dict(formatVersion=1, assessmentId="approved-settings-change", graphDigest=graph, challenge=secrets.token_hex(32),
            issuedAt=now().isoformat(), expiresAt=expiry, reviewerId="reviewer", resources=[dict(resource, instanceId=meta["uid"])],
            operations=[dict(selection, id="current-settings", facts=dict(resourceVersion=meta["resourceVersion"]), maxAgeSeconds=120)])
        requirements = write(work / "before.json", before)
        desired_sha = digest(canonical(dict(mode="reviewed")))
        policy = write(work / "policy.json", dict(formatVersion=1, reviewerId="reviewer", resources=[resource],
            operations=[dict(selection, id="updated", postconditions=dict(dataSha256=desired_sha, committedDataSha256=desired_sha))]))
        dispatch = dict(id="change-001", runtimeId="loopback-runtime", authorizationRef="operator-change-001")
        pins = dict(dispatchId=dispatch["id"], runtimeId=dispatch["runtimeId"], authorizationRef=dispatch["authorizationRef"],
                    candidateId=fidelity["candidateId"], compilerDigest=fidelity["compilerDigest"],
                    canonicalPlanSha256=fidelity["artifacts"]["compiler/canonical-execution-plan.json"],
                    proposalSha256=fidelity["proposalSha256"], fidelityReviewSha256=digest(fidelity_raw))
        grant_path = work / "grant.json"
        write(grant_path, dict(pins=pins, deadlineSemantics="submission", graphDigest=graph, nodeId="source:6:update", owner="platform", uid=meta["uid"],
            resourceVersion=meta["resourceVersion"], contractSha256=digest(canonical(before)), expiresAt=expiry))
        ledger = work / "reservations"
        ledger.mkdir(mode=0o700)
        writer.update(grant=str(grant_path), requirements=requirements, source=str(work / "desired-data.json"), proposal=str(work / "review/proposal.json"), reservationDirectory=str(ledger))
        runtime = [sys.executable, PROVIDER, "runtime", "--config", write(work / "runtime-config.json", writer)]
        settings = write(work / "dispatch-config.json", dict(formatVersion=1, requirements=requirements, trust=trust, dispatch=dispatch,
            runtime=dict(argv=runtime, credentialEnv=[], timeoutSeconds=90), outcomeReview=dict(policy=policy, trust=audit_trust)))
        args = [*common, "--dispatch-config", settings]
        output = work / "dispatch"
        execute = [sys.executable, str(ROOT / "tools/dispatch_planning_worker.py"), *args]
        command([*execute, "--out", str(output)])
        applied = current(cluster)
        require(applied["data"] == dict(mode="reviewed") and applied["binaryData"] == original["binaryData"] and
                applied["metadata"]["labels"] == meta["labels"] and
                applied["metadata"]["annotations"]["example.com/retain"] == "yes", "Update changed the wrong fields")
        command([*execute, "--out", str(output)])
        require(current(cluster)["metadata"]["resourceVersion"] == applied["metadata"]["resourceVersion"], "Recovery wrote again")
        review = [sys.executable, str(ROOT / "tools/review_dispatch_outcomes.py"), *args, "--dispatch-out", str(output)]
        request = command([*review, "request"])
        audit = work / "audit-export.jsonl"
        audit.write_bytes((work / "audit.log").read_bytes())
        observer.update(writerUsername="writer", runtimeId=dispatch["runtimeId"], auditFile=str(audit))
        collect = [sys.executable, PROVIDER, "observer", "--config", write(work / "observer-config.json", observer)]

        def assess(name, status, code):
            bundle = command(collect, document=request)
            path = write(work / (name + ".json"), bundle)
            out = work / name
            command([*review, "assess", "--observations", path, "--out", str(out)], status)
            report = loads((out / "compiler/operation-outcome-review.json").read_bytes())
            require(report["results"][0]["code"] == code, f"Unexpected {name} outcome")

        assess("committed", 0, "OUTCOME_SATISFIED")
        events = map(loads, audit.read_bytes().splitlines())
        event, = [e for e in events if e["auditID"] == request["receiptId"] and e["stage"] == "ResponseComplete"]
        raw = audit.read_bytes()
        for mutation in (dict(requestURI=event["requestURI"] + "?dryRun=All"), dict(user=dict(username="elsewhere")),
                         dict(auditID="other"), dict(impersonatedUser=dict(username="writer"))):
            write(audit, dict(event, **mutation))
            require(not command(collect, document=request)["observations"], "Uncorrelated audit event produced evidence")
        # Falsify an admission-time rewrite with current data still matching the reviewer expectation.
        rewritten = dict(event["responseObject"], data=dict(mode="overridden-at-commit"))
        write(audit, dict(event, responseObject=rewritten))
        assess("commit-mismatch", 4, "OUTCOME_POSTCONDITION_FAILED")
        audit.write_bytes(b"")
        require(not command(collect, document=request)["observations"], "Empty export produced evidence")
        audit.write_bytes(raw + raw)
        assess("ambiguous", 3, "OUTCOME_OBSERVATION_MISSING")
        audit.write_bytes(raw)
        intent = loads((output / "dispatch-intent.json").read_bytes())
        require(command(runtime, document=dict(intent, candidateId="unapproved")).get("reason") == "authorization",
                "Unapproved candidate reached the provider")
        forged = dict(intent, preconditions=dict(intent["preconditions"], challenge="changed"))
        require(command(runtime, document=forged).get("reason") == "authorization", "Changed observation challenge admitted")
        desired = Path(writer["source"])
        saved = desired.read_bytes()
        try:
            desired.write_bytes(saved + b" ")
            require(command(runtime, document=intent).get("reason") == "unsupported", "Source digest mismatch admitted")
        finally:
            desired.write_bytes(saved)
        require(not command(collect, document=dict(request, decisionSha256="0" * 64))["observations"],
                "Substituted decision produced evidence")
        race(work, common, cluster, loads(Path(settings).read_bytes()), loads(grant_path.read_bytes()), before, runtime)
        assess("drift", 4, "OUTCOME_POSTCONDITION_FAILED")
        cluster.kubectl("delete", "cm", "settings", "-n", "commissioning")
        cluster.create(dict(apiVersion="v1", kind="ConfigMap", metadata=dict(name="settings", namespace="commissioning"),
                            data=dict(mode="reviewed")))
        assess("replacement", 3, "OUTCOME_OBSERVATION_MISSING")
        print("PASS: real API update, unchanged unrelated fields, replay, separate observer RBAC, native audit, "
              "ambiguous and unrelated evidence, grant mismatch, concurrent reservation, native 409, drift and replacement.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli", required=True, type=Path)
    parser.add_argument("--binaries", required=True, type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="flow-kubernetes-") as temporary:
        verify(Path(temporary), args.cli.resolve(), args.binaries.resolve())
