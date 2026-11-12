#!/usr/bin/env python3
"""Actual local creations and read retries prove invocation-specific resource output lineage."""
import argparse
import base64
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import secrets
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import urllib.request

from authoring_host.contracts import canonical, loads, read, require
from observation_host.journal import event_for
from verify_run_evidence import timestamp


class LocalResource:
    """Test-only workers. Handles, not fixture constants, address actual consumer I/O."""
    def __init__(self, domain, root):
        self.domain, self.root = domain, root
        self.creations, self.probes = 0, 0
        self.server = self.thread = self.reserved = None
        self.secret = secrets.token_bytes(32)
        self.challenge = b"independent-lineage-authentication-probe"
        self.expected_mac = hmac.new(self.secret, self.challenge, hashlib.sha256).digest()
        if domain == "service":
            # Reserve the independently declared address, but publish no listener before CREATE.
            self.reserved = socket.socket()
            self.reserved.bind(("127.0.0.1", 0))
            key = "http://127.0.0.1:" + str(self.reserved.getsockname()[1]) + "/ready"
        else:
            key = str(root / ("created.sqlite3" if domain == "database" else "created.credential"))
        self.identity = dict(scope="isolated-lineage-test", type="local-" + domain, key=key)

    def create(self):
        require(self.creations == 0, "Creation was dispatched twice")
        self.creations += 1
        if self.domain == "service":
            class Handler(BaseHTTPRequestHandler):
                attempts = 0

                def do_HEAD(self):
                    self.send_response(200); self.end_headers()

                def do_GET(self):
                    Handler.attempts += 1
                    # Real HTTP failure on the first readiness request, then actual readiness.
                    self.send_response(503 if Handler.attempts == 1 else 200)
                    self.end_headers()
                    self.wfile.write(b"ready")

                def log_message(self, *args):
                    pass

            self.server = ThreadingHTTPServer(self.reserved.getsockname(), Handler, bind_and_activate=False)
            self.server.socket.close()
            self.server.socket = self.reserved
            self.server.server_activate()
            self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
            self.thread.start()
        elif self.domain == "database":
            with sqlite3.connect(self.identity["key"]) as connection:
                connection.execute("CREATE TABLE records (payload TEXT NOT NULL)")
                connection.execute("INSERT INTO records VALUES ('independent-baseline')")
        else:
            with Path(self.identity["key"]).open("xb") as output:
                Path(self.identity["key"]).chmod(0o600)
                output.write(self.secret)
        return {"handle": self.identity.copy()}

    def resolve(self, handle):
        if self.domain == "service":
            with urllib.request.urlopen(urllib.request.Request(handle["key"], method="HEAD"), timeout=3) as response:
                require(response.status == 200, "Resource cannot be resolved")
        else:
            require(Path(handle["key"]).is_file(), "Resource cannot be resolved")
        return {"handle": dict(handle)}

    def probe(self, handle):
        self.probes += 1
        if self.domain == "service":
            with urllib.request.urlopen(handle["key"], timeout=3) as response:
                ready = response.status == 200 and response.read() == b"ready"
        elif self.domain == "database":
            lock = None
            try:
                if self.probes == 1:
                    # External fixture contention causes a real SQLite read failure; data does not change.
                    lock = sqlite3.connect(handle["key"])
                    lock.execute("BEGIN EXCLUSIVE")
                with sqlite3.connect(Path(handle["key"]).as_uri() + "?mode=ro", uri=True, timeout=0) as connection:
                    ready = connection.execute("SELECT payload FROM records").fetchall() == [("independent-baseline",)]
            finally:
                if lock:
                    lock.rollback(); lock.close()
        else:
            key = Path(handle["key"]).read_bytes()
            # A real invalid credential presentation precedes a valid one, without rotating or recreating it.
            presented = b"invalid-test-credential" if self.probes == 1 else key
            ready = hmac.compare_digest(hmac.new(presented, self.challenge, hashlib.sha256).digest(), self.expected_mac)
        if not ready:
            raise RuntimeError("Native resource verification failed")
        return {"ready": True}

    def close(self):
        if self.server:
            self.server.shutdown(); self.server.server_close()
            self.thread.join(timeout=3)
        elif self.reserved:
            self.reserved.close()


def verify(root, cli, deployment_factory=None, allocation_factory=None):
    root, cli = Path(root).resolve(), Path(cli).resolve()
    rows = []
    with tempfile.TemporaryDirectory(prefix="flow-resource-lineage-") as temporary:
        work = Path(temporary)
        key = work / "observer.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(key)], check=True, capture_output=True)
        public = subprocess.check_output(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"])
        for domain in (("service", "database") if allocation_factory else ("service", "database", "credential")):
            folder = work / domain; folder.mkdir()
            local = (allocation_factory or LocalResource)(domain, folder)
            deployed = None
            try:
                address = local.identity
                request = local.request if allocation_factory else address
                version = 3 if allocation_factory else 2
                def operation(identifier, capability, resource, **params):
                    return dict(kind="operation", id=identifier, capability=capability, params=dict(resource=resource, **params))

                create = operation("create", "PROVISION", request); create["requires"] = ["approval"]
                resolve = operation("resolve", "VERIFY", {"target": "ref:create.handle"}, criteria="resolve")
                probe = operation("probe", "VERIFY", {"target": "ref:resolve.handle"}, criteria="ready")
                intent = dict(intentVersion="3.1", kind="FlowIntentDocument", name="resource-lineage-" + domain,
                    workflows=[dict(name="main", kind="CUSTOM", body=[dict(kind="operation", id="approval", capability="APPROVE"),
                        create, dict(kind="retry", id="read", maxAttempts=2, delaySeconds=0, body=[resolve, probe])])])
                # Obtain the existing graph through the public frontend before creating independent outcome requirements.
                proposal = dict(formatVersion=1, providerId="manual-lineage-test", request=dict(userText="Observe resource lineage", context={}, mode="DRAFT"),
                    response=dict(normalizedIntent=intent, report=dict(mode="DRAFT", classification=dict(type="manual-lineage-test", confidence=0),
                    confidence=dict(overall=0, intentType=0, entities=0, dependencies=0, safety=0), explanation=["Independent local worker proof."],
                    guardrails=["No cloud or human approval claimed."])))
                proposal_path = folder / "proposal.json"; proposal_path.write_bytes(canonical(proposal))
                compile_result = subprocess.run([str(cli), "proposal", str(proposal_path), "--out", str(folder / "compiled")],
                    cwd=root, capture_output=True, timeout=120)
                require(compile_result.returncode == 0, "Lineage fixture did not compile")
                graph = loads(read(folder / "compiled/proposal-review.json"))["graphDigest"]
                selector = lambda capability, **params: dict(capability=capability, parameters=params)
                create_selector = selector("PROVISION")
                requirements = dict(formatVersion=version, graphDigest=graph, runId="lineage-" + domain, challenge=secrets.token_urlsafe(32),
                    startedAt=timestamp(), reviewerId="independent-lineage-test", resources=[dict(id="created", identity=address,
                        disposition="CREATED_IN_RUN", creator=create_selector)], operations=[
                        dict(id="create", selector=create_selector, resource="created", parameter="resource", postconditions={}, evidence=dict(status="SUCCEEDED")),
                        dict(id="resolve", selector=selector("VERIFY", criteria="resolve"), resource="created", parameter="resource", path=["target"], postconditions={}, evidence=dict(status="SUCCEEDED")),
                        dict(id="probe", selector=selector("VERIFY", criteria="ready"), resource="created", parameter="resource", path=["target"], postconditions={"ready": True}, evidence=dict(status="SUCCEEDED"))],
                    execution=dict(workflow="main", status="SUCCEEDED"))
                if allocation_factory:
                    resource = requirements["resources"][0]
                    del resource["identity"]
                    resource.update(owner="fixture-tenant", allocation=dict(scope=local.scope, type=local.type, binding="create",
                        path=["handle"], keyPrefix=local.key_prefix, maxKeyLength=1000))
                req = folder / "requirements.json"; req.write_bytes(canonical(requirements))
                frozen_requirements = req.read_bytes()
                trust = folder / "trust.json"; trust.write_bytes(canonical(dict(formatVersion=version, grants=[dict(keyId="observer", publicKey=base64.b64encode(public).decode(),
                    scopes=[request["scope"]], resourceTypes=[request["type"]], kinds=["RESULT", "STATE", "CREATION", "BINDING"] + (["ALLOCATION", "OWNERSHIP"] if allocation_factory else []), workflows=["main"])])))
                journal = folder / "journal"

                def direct_host(command, *args, directory=journal, expected=0):
                    result = subprocess.run([sys.executable, str(root / "tools/observe.py"), command, "--journal", str(directory), *map(str, args)],
                        capture_output=True, timeout=150)
                    require(result.returncode == expected, f"Lineage observer {command} exit {result.returncode}, expected {expected}")
                    return json.loads(result.stdout or result.stderr)

                tip = direct_host("init", "--cli", cli, "--source", proposal_path, "--source-kind", "proposal", "--requirements", req)
                if deployment_factory:
                    deployed = deployment_factory(root, folder, journal, tip, direct_host)

                def host(command, *args, directory=journal, expected=0):
                    if deployed and directory == journal:
                        return deployed.host(command, *args, expected=expected)
                    return direct_host(command, *args, directory=directory, expected=expected)

                targets = host("status")
                nodes = {target["id"]: target["nodeId"] for target in targets["operationTargets"]}
                approval = next(t["nodeId"] for t in targets["invocationTargets"] if t["capability"] == "APPROVE")
                require(all(name in next(t for t in targets["invocationTargets"] if t["nodeId"] == node).get("outputs", [])
                    for name, node in nodes.items()), "Compiler correlation omitted output bindings")
                counter = 0

                def append(kind, data, capture=b"", directory=journal, retained=None, expected=0):
                    nonlocal tip, counter
                    counter += 1
                    event = event_for(retained or tip, "event-" + str(counter), kind, data, capture, format_version=2 if allocation_factory else 1)
                    event_path, captured = folder / "event.json", folder / "capture.bin"
                    event_path.write_bytes(canonical(event)); captured.write_bytes(capture)
                    result = host("append", "--event", event_path, *(["--capture", captured] if capture else []), directory=directory, expected=expected)
                    if directory == journal and expected == 0:
                        tip = result
                    return result

                def claim(kind, invocation, name, at, **values):
                    return dict(id=kind.lower() + "-" + invocation, kind=kind, status="SUCCEEDED", invocationId=invocation,
                        nodeId=nodes[name], resource=address, observedAt=at, **(dict(resourceId="created", instanceId=local.last_instance) if allocation_factory else {}), **values)

                def observe(invocation, name, attempt, action):
                    nonlocal address
                    node = approval if name == "approval" else nodes[name]
                    append("START", dict(invocationId=invocation, nodeId=node, attempt=attempt, startedAt=timestamp()))
                    try:
                        value = action(); status = "SUCCEEDED"; capture = canonical(dict(status=status, result=value))
                    except (OSError, RuntimeError, sqlite3.Error) as error:
                        value = None; status = "FAILED"; capture = canonical(dict(status=status, errorType=type(error).__name__,
                            **(dict(instanceObservation=local.last_capture) if allocation_factory else {})))
                    if allocation_factory and name == "create" and status == "SUCCEEDED":
                        address = value["handle"]
                    end = timestamp()
                    finish = dict(invocationId=invocation, completedAt=end, status=status)
                    if status == "FAILED": finish["errorId"] = "error-" + invocation
                    append("FINISH", finish, capture)
                    if name != "approval":
                        append("OBSERVATION", dict(claim("RESULT", invocation, name, end), status=status), capture)
                    return status, value, end, capture

                def export(name, expected, directory=journal, retained=None):
                    checkpoint = folder / (name + "-checkpoint.json"); checkpoint.write_bytes(canonical(retained or tip))
                    result = host("export", "--checkpoint", checkpoint, "--cli", cli, "--trust", trust, "--private-key", key,
                        "--key-id", "observer", "--out", folder / name, expected=expected, directory=directory)
                    return loads(read(Path(result["directory"]) / "operation-outcome-review.json"))

                observe("approval", "approval", 1, lambda: dict(fixtureApproval=True, humanApprovalClaimed=False))
                status, created, created_at, created_capture = observe("create-1", "create", 1, local.create)
                require(status == "SUCCEEDED", "Actual local creation failed")
                if allocation_factory:
                    require(address["key"].encode() not in frozen_requirements and local.last_instance.encode() not in frozen_requirements,
                            "Runtime identity was predeclared")
                    append("OBSERVATION", claim("ALLOCATION", "create-1", "create", created_at, binding="create", path=["handle"]), created_capture)
                    append("OBSERVATION", claim("OWNERSHIP", "create-1", "create", created_at, owner=created["owner"]), created_capture)
                append("OBSERVATION", claim("BINDING", "create-1", "create", created_at, binding="create", path=["handle"]), created_capture)
                for number in (1, 2):
                    status, resolved, end, capture = observe("resolve-" + str(number), "resolve", number, lambda: local.resolve(created["handle"]))
                    require(status == "SUCCEEDED", "Actual resource resolution failed")
                    binding = claim("BINDING", "resolve-" + str(number), "resolve", end, binding="resolve", path=["handle"])
                    if number == 1: append("OBSERVATION", binding, capture)
                    else: late_binding, late_capture = binding, capture
                    status, _, _, _ = observe("probe-" + str(number), "probe", number, lambda: local.probe(resolved["handle"]))
                    require(status == ("FAILED" if number == 1 else "SUCCEEDED"), "Native read retry did not follow the actual failure")
                append("CLOSE", dict(completedAt=timestamp(), status="SUCCEEDED"), canonical({"closed": True}))
                final = local.probe(resolved["handle"])
                append("OBSERVATION", claim("STATE", "probe-2", "probe", timestamp(), facts=final), canonical(final))

                # Independent negative snapshots reuse captured work, never dispatch creation again.
                before_binding = folder / "missing-latest-journal"; shutil.copytree(journal, before_binding)
                creation = claim("CREATION", "create-1", "create", created_at, created=True)
                old_tip = append("OBSERVATION", creation, created_capture, directory=before_binding)
                missing = export("old-attempt-not-substituted", 3, before_binding, old_tip)
                require(any(r["id"] == "probe" and r["code"] == "OUTCOME_RESOURCE_BINDING_MISSING_OR_AMBIGUOUS" for r in missing["results"]), "Old binding supplied a newer attempt")
                rows.append(dict(case=domain, variant="old-attempt-not-substituted", state=missing["state"]))

                wrong_path = folder / "wrong-path-journal"; shutil.copytree(before_binding, wrong_path)
                wrong_tip = append("OBSERVATION", dict(late_binding, path=["other"]), late_capture, wrong_path, old_tip)
                wrong = export("wrong-output-path", 3, wrong_path, wrong_tip)
                rows.append(dict(case=domain, variant="wrong-output-path", state=wrong["state"]))
                append("OBSERVATION", dict(late_binding, invocationId="resolve-1"), late_capture, before_binding, old_tip, expected=2)
                rows.append(dict(case=domain, variant="wrong-producer-invocation", state="REJECTED"))

                append("OBSERVATION", late_binding, late_capture)
                missing = export("binding-without-creation", 3)
                require(any(r["code"] == "OUTCOME_OBSERVATION_MISSING" for r in missing["results"]), "Binding invented creation")
                rows.append(dict(case=domain, variant="binding-without-creation", state=missing["state"]))
                not_created = folder / "not-created-journal"; shutil.copytree(journal, not_created)
                other_tip = append("OBSERVATION", dict(creation, created=False), created_capture, not_created)
                failed = export("reported-not-created", 4, not_created, other_tip)
                rows.append(dict(case=domain, variant="reported-not-created", state=failed["state"]))

                append("OBSERVATION", creation, created_capture)
                accepted = export("late-lineage-and-creation", 0)
                probe_result = next(r for r in accepted["results"] if r["id"] == "probe")
                require(probe_result["creatorInvocationId"] == "create-1" and [r["producerInvocationId"] for r in probe_result["resourceLineage"]] == ["resolve-1", "resolve-2"], "Exact producer or creator lineage was lost")
                require(req.read_bytes() == frozen_requirements, "Allocation changed the independently frozen requirements")
                require(local.creations == 1 and accepted["executionAuthorized"] is False, "Observer replayed or authorized work")
                rows.append(dict(case=domain, variant="late-lineage-and-creation", state=accepted["state"], creationExecutions=local.creations))
                if allocation_factory:
                    require(all(r["instanceId"] == local.last_instance for r in accepted["results"]), "Resolved instance was lost")
                    from verify_runtime_identities import falsify
                    rows.extend(falsify(root, cli, folder, local, key, frozen_requirements))
                if deployed:
                    rows.extend(dict(case=domain, **row) for row in deployed.finish_proof())
            finally:
                if deployed:
                    deployed.close()
                local.close()
    result = dict(status="PASS", evidenceKind="runtime-allocation-integration-test" if allocation_factory else ("deployed-observer-integration-test" if deployment_factory else "resource-invocation-lineage-integration-test"), cases=rows,
                  localWorkExecuted=True, cloudWorkExecuted=False, liveModelEvaluated=False, targetSupportPromoted=False,
                  **(dict(deployedObserver=bool(deployment_factory)) if allocation_factory else {}))
    print(json.dumps(result))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(args.root, args.cli)
