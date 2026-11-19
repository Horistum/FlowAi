"""One independently approved conditional update. EOF after submission is indeterminate."""
from datetime import timedelta
import os
from pathlib import Path
import re

from authoring_host.contracts import HostError, canonical, digest, keys, loads, require
from authoring_host.inventory_provenance import read_regular, timestamp
from .common import BINDING, OWNER, PRECONDITION, ConfigMapAPI, binding, emit, frame, json_file, now, sign

PINS = ("dispatchId", "runtimeId", "authorizationRef", "candidateId", "proposalSha256",
        "canonicalPlanSha256", "fidelityReviewSha256", "compilerDigest")


def proposed_data(path, source_path, expected_sha, resource):
    raw = read_regular(path, 2_000_000)
    require(digest(raw) == expected_sha, "Reviewed proposal digest mismatch")
    intent = loads(raw)["response"]["normalizedIntent"]
    keys(intent, ("kind", "intentVersion", "name", "workflows"))
    require(intent["kind"] == "FlowIntentDocument" and intent["intentVersion"] == "3.0" and
            len(intent["workflows"]) == 1, "Unsupported intent")
    workflow, = intent["workflows"]
    keys(workflow, ("name", "kind", "body"))
    require(workflow["kind"] == "CUSTOM" and len(workflow["body"]) == 2, "Unsupported workflow")
    approval, op = workflow["body"]
    keys(approval, ("id", "kind", "capability"))
    keys(op, ("id", "kind", "capability", "requires", "params"))
    require(approval["kind"] == op["kind"] == "operation" and approval["capability"] == "APPROVE" and
            op["capability"] == "SYNC" and op["requires"] == [approval["id"]], "Unsupported operations")
    params = op["params"]
    keys(params, ("destination", "mode", "source"))
    source_bytes = read_regular(source_path, 65536)
    require(params["mode"] == "replace-text-data" and params["source"] == "sha256:" + digest(source_bytes),
            "Commissioned source differs from the reviewed sync operation")
    source = loads(source_bytes)
    keys(source, ("data",))
    data = source["data"]
    require(params["destination"] == resource and isinstance(data, dict) and len(canonical(data)) <= 65536 and
            all(re.fullmatch(r"[-a-zA-Z0-9_.]{1,253}", k) and isinstance(v, str) for k, v in data.items()),
            "Expected literal text data for the commissioned ConfigMap")
    return data


def reserve(directory, dispatch_id):
    path = Path(directory) / digest(dispatch_id.encode("utf-8"))
    try:
        with path.open("xb") as stream:
            stream.write(canonical(dict(dispatchId=dispatch_id)))
            stream.flush()
            os.fsync(stream.fileno())
    except FileExistsError:
        return False
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
    return True


def approved_contract(config, grant, request, resource):
    """Match the external grant and reconstruct all precondition pins from its exact contract."""
    keys(grant, ("pins", "contractSha256", "graphDigest", "nodeId", "uid", "resourceVersion",
                 "expiresAt", "owner", "deadlineSemantics"))
    pre = request["preconditions"]
    raw = read_regular(config["requirements"], 2_000_000)
    contract = loads(raw)
    if (grant["deadlineSemantics"] != "submission" or grant["pins"] != {k: request[k] for k in PINS} or
            digest(raw) != grant["contractSha256"] or pre["contractSha256"] != grant["contractSha256"] or
            pre["graphDigest"] != grant["graphDigest"] or len(contract["operations"]) != 1 or
            any(pre[k] != contract[k] for k in ("graphDigest", "assessmentId", "issuedAt", "expiresAt", "challenge"))):
        return None
    expected = dict(resource=resource, nodeId=grant["nodeId"], requirementId=contract["operations"][0]["id"])
    if (pre["operations"] != [expected] or not timestamp(pre["issuedAt"]) <= now() <
            min(timestamp(grant["expiresAt"]), timestamp(pre["expiresAt"]))):
        return None
    return contract


def submit(api, obj, result):
    # PUT carries the same provider UID and resourceVersion as the signed GET.
    # Never retry the request or infer non-commit from a broken connection.
    status, receipt, updated = api.call("PUT", obj)
    if status == 200:
        meta = obj["metadata"]
        require(api.matches(updated) and updated["metadata"]["uid"] == meta["uid"] and
                updated["metadata"]["resourceVersion"] != meta["resourceVersion"] and
                receipt and re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", receipt),
                "Uncertain update receipt")
        return dict(result, status="completed", receiptId=receipt)
    require(status in (400, 401, 403, 404, 409, 422), "Uncertain update status")
    return result


def run(config):
    api = ConfigMapAPI(config)
    request = frame()
    pre = request["preconditions"]
    require(request["kind"] == "observe-for-dispatch" and type(request["formatVersion"]) is int and
            request["formatVersion"] == pre["formatVersion"] == 1, "Invalid dispatch frame")
    grant = json_file(config["grant"])
    request_sha = digest(canonical(request))
    reply = dict(formatVersion=1, dispatchId=request["dispatchId"], requestSha256=request_sha)
    contract = approved_contract(config, grant, request, api.resource)
    if contract is None:
        emit(dict(reply, kind="rejected", reason="authorization"))
        return
    try:
        data = proposed_data(config["proposal"], config["source"], grant["pins"]["proposalSha256"], api.resource)
    except HostError:
        emit(dict(reply, kind="rejected", reason="unsupported"))
        return
    status, _, obj = api.call("GET")
    if status != 200 or not api.matches(obj):
        emit(dict(reply, kind="rejected", reason="conflict"))
        return
    meta = obj["metadata"]
    owner = meta.get("labels", {}).get(OWNER)
    if (not owner or owner != grant["owner"] or meta["uid"] != grant["uid"] or
            meta["resourceVersion"] != grant["resourceVersion"] or obj.get("immutable") or
            not reserve(config["reservationDirectory"], request["dispatchId"])):
        emit(dict(reply, kind="rejected", reason="conflict"))
        return
    op, = pre["operations"]
    facts = dict(dataSha256=digest(canonical(obj.get("data", {}))), resourceVersion=meta["resourceVersion"])
    projection = dict(resource=api.resource, instanceId=meta["uid"], owner=owner, facts=facts)
    pins = {k: pre[k] for k in ("contractSha256", "graphDigest", "assessmentId", "challenge")}
    observed_at = now()
    claim = sign(config, PRECONDITION, canonical(projection), id="configmap-before", requirementId=op["requirementId"],
                 nodeId=op["nodeId"], observedAt=observed_at.isoformat(), **pins, **projection)
    snapshots = canonical(dict(formatVersion=1, observations=[claim]))
    emit(dict(reply, kind="observations", snapshots=snapshots.decode()))
    decision = frame()
    require(decision["kind"] == "assessment" and decision["formatVersion"] == 1 and
            decision["dispatchId"] == request["dispatchId"] and decision["requestSha256"] == request_sha and
            decision["snapshotsSha256"] == digest(snapshots) and decision["executionAuthorized"] is False and
            re.fullmatch("[0-9a-f]{64}", decision["nonce"]), "Invalid assessment frame")
    decision_sha = digest(canonical(decision))
    context = dict(reply, runtimeId=request["runtimeId"], resource=api.resource, graphDigest=pre["graphDigest"])
    meta.setdefault("annotations", {})[BINDING] = binding(context, op["nodeId"], decision["checkedAt"], decision_sha)
    obj["data"] = data
    result = dict(reply, kind="result", decisionSha256=decision_sha, status="rejected", receiptId="no-update")
    if (decision["currentState"] == "SATISFIED" and decision["code"] == "PRECONDITION_SATISFIED" and
            json_file(config["grant"]) == grant and timestamp(decision["checkedAt"]) <= now() <
            min(timestamp(decision["validUntil"]), timestamp(pre["expiresAt"]), timestamp(grant["expiresAt"]),
                observed_at + timedelta(seconds=contract["operations"][0].get("maxAgeSeconds", 300)))):
        result = submit(api, obj, result)
    emit(result)
