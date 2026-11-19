"""One independently approved conditional update. EOF after submission is indeterminate."""
from datetime import timedelta
import os
from pathlib import Path
import re

from authoring_host.contracts import HostError, canonical, digest, keys, loads, require
from authoring_host.inventory_provenance import read_regular
from .common import BINDING, OWNER, PRECONDITION, ResourceAPI, binding, emit, frame, instant, json_file, now, sign
from .selection import desired_value


PINS = ("dispatchId", "runtimeId", "authorizationRef", "candidateId", "proposalSha256",
        "canonicalPlanSha256", "fidelityReviewSha256", "compilerDigest")


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
    op, = contract["operations"]
    targets = [r for r in contract["resources"] if r["id"] == op["resource"]]
    if (len(targets) != 1 or targets[0]["identity"] != resource or targets[0]["disposition"] != "MANAGED" or
            targets[0].get("owner") != grant["owner"] or targets[0]["instanceId"] != grant["uid"] or
            op["selector"]["capability"] != "SYNC" or op["parameter"] != "destination" or op.get("path")):
        return None
    expected = dict(resource=resource, nodeId=grant["nodeId"], requirementId=op["id"])
    if (pre["operations"] != [expected] or not instant(pre["issuedAt"], lower_bound=True) <= now() <
            min(instant(grant["expiresAt"]), instant(pre["expiresAt"]))):
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
    api = ResourceAPI(config)
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
        desired = desired_value(config, grant["pins"], api)
    except HostError:
        emit(dict(reply, kind="rejected", reason="unsupported"))
        return
    observed_at = now()
    status, _, obj = api.call("GET")
    if status in (401, 403):
        emit(dict(reply, kind="rejected", reason="authorization"))
        return
    require(status in (200, 404), "Provider read unavailable")
    if status == 404 or not api.matches(obj):
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
    facts = {api.profile.fact: api.profile.observed(obj), "resourceVersion": meta["resourceVersion"]}
    projection = dict(resource=api.resource, instanceId=meta["uid"], owner=owner, facts=facts)
    pins = {k: pre[k] for k in ("contractSha256", "graphDigest", "assessmentId", "challenge")}
    claim = sign(config, PRECONDITION, canonical(projection), id=api.profile.resource_type + "-before", requirementId=op["requirementId"],
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
    api.profile.replace(obj, desired)
    result = dict(reply, kind="result", decisionSha256=decision_sha, status="rejected", receiptId="no-update")
    if (decision["currentState"] == "SATISFIED" and decision["code"] == "PRECONDITION_SATISFIED" and
            json_file(config["grant"]) == grant and instant(decision["checkedAt"], lower_bound=True) <= now() <
            min(instant(decision["validUntil"]), instant(pre["expiresAt"]), instant(grant["expiresAt"]),
                observed_at + timedelta(seconds=contract["operations"][0].get("maxAgeSeconds", 300)))):
        result = submit(api, obj, result)
    emit(result)
