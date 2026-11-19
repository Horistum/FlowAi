"""Read provider-owned audit evidence and current ConfigMap state under a separate identity."""
import os
from pathlib import Path
import stat

from authoring_host.contracts import canonical, digest, loads, require
from authoring_host.discovery import _directory
from authoring_host.inventory_provenance import timestamp
from .common import BINDING, OUTCOME, OWNER, ConfigMapAPI, binding, emit, frame, now, sign


def completed_event(raw, request, api, actor, expected_binding):
    events = []
    for line in raw.splitlines():
        event = loads(line)
        if event.get("auditID") == request["receiptId"] and event.get("stage") == "ResponseComplete":
            events.append(event)
    if len(events) != 1:
        return None
    event, = events
    ref = event.get("objectRef", {})
    if not (event.get("apiVersion") == "audit.k8s.io/v1" and event.get("kind") == "Event" and
            event.get("level") == "RequestResponse" and event.get("verb") == "update" and
            event.get("requestURI") == api.path and event.get("user", {}).get("username") == actor and
            "impersonatedUser" not in event and event.get("responseStatus", {}).get("code") == 200 and
            ref.get("apiGroup", "") == "" and ref.get("apiVersion") == "v1" and ref.get("resource") == "configmaps" and
            ref.get("namespace") == api.config["namespace"] and ref.get("name") == api.config["name"] and
            not ref.get("subresource") and timestamp(request["startedAt"]) <= timestamp(event["stageTimestamp"]) <= now()):
        return None
    before, after = event.get("requestObject", {}), event.get("responseObject", {})
    if not all(api.matches(obj) and obj["metadata"].get("annotations", {}).get(BINDING) == expected_binding
               for obj in (before, after)):
        return None
    submitted, committed = before["metadata"], after["metadata"]
    if submitted["uid"] != committed["uid"] or submitted["resourceVersion"] == committed["resourceVersion"]:
        return None
    return event


def observations(config, request, audit_bytes):
    api = ConfigMapAPI(config)
    operations = request["operations"]
    if request["runtimeId"] != config["runtimeId"] or len(operations) != 1 or operations[0]["resource"] != api.resource:
        return []
    result = dict(formatVersion=1, kind="result", status="completed",
                  **{k: request[k] for k in ("dispatchId", "requestSha256", "decisionSha256", "receiptId")})
    if (request["runId"] != "dispatch-" + request["requestSha256"] or
            request["challenge"] != digest(b"Horistum dispatch outcome v1\0" + canonical(result))):
        return []
    node = operations[0]["nodeId"]
    correlation = binding(dict(request, resource=api.resource), node, request["startedAt"], request["decisionSha256"])
    event = completed_event(audit_bytes, request, api, config["writerUsername"], correlation)
    if event is None:
        return []
    status, _, live = api.call("GET")
    if status == 404:
        return []
    require(status == 200, "Observer could not read the commissioned resource")
    recorded = event["responseObject"]["metadata"]
    if not api.matches(live) or live["metadata"]["uid"] != recorded["uid"]:
        return []
    at = event["stageTimestamp"]
    read_at = now().isoformat()
    record = dict(auditId=event["auditID"], auditEventSha256=digest(canonical(event)), binding=correlation,
                  uid=recorded["uid"], resourceVersion=recorded["resourceVersion"], recordedAt=at)
    owner = recorded.get("labels", {}).get(OWNER)
    facts = dict(committedDataSha256=digest(canonical(event["responseObject"].get("data", {}))),
                 dataSha256=digest(canonical(live.get("data", {}))))
    state = dict(record, observedAt=read_at, observedResourceVersion=live["metadata"]["resourceVersion"], facts=facts)
    pins = {k: request[k] for k in ("contractSha256", "graphDigest", "runId", "challenge")}
    def claim(kind, when, source, **fields):
        return sign(config, OUTCOME, canonical(source), kind=kind, id="configmap-" + kind, observedAt=when,
                    nodeId=node, resource=api.resource, **pins, **fields)
    claims = [claim("RESULT", at, record), claim("STATE", read_at, state, facts=facts)]
    if owner:
        claims.append(claim("OWNERSHIP", at, dict(record, owner=owner), owner=owner))
    return claims


def audit_export(path):
    """Read a stable NDJSON export. An empty regular file is a valid zero-event capture."""
    path = Path(path)
    directory = _directory(str(path.parent))
    try:
        fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
    finally:
        os.close(directory)
    limit = 16 * 1024 * 1024
    with os.fdopen(fd, "rb") as stream:
        before = os.fstat(fd)
        require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1 and before.st_size <= limit,
                "Expected a bounded regular audit export")
        raw = stream.read(limit + 1)
        after = os.fstat(fd)
    require(len(raw) == before.st_size == after.st_size and before.st_ctime_ns == after.st_ctime_ns and
            (not raw or raw.endswith(b"\n")), "Incomplete or changing audit export")
    return raw


def run(config):
    request = frame()
    emit(dict(formatVersion=1, observations=observations(config, request, audit_export(config["auditFile"]))))
