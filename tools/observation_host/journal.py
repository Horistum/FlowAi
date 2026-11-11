"""Bounded transactional event journal; the compiler remains the semantic authority."""
from __future__ import annotations

import calendar
from contextlib import contextmanager
from datetime import datetime
import hashlib
import os
from pathlib import Path
import re
import sqlite3
import stat
import time

from authoring_host.contracts import HostError, canonical, digest, integer, keys, loads, require

MAX_CAPTURE = 65536
MAX_EVENTS = 1024
MAX_TOTAL = 8 * 1024 * 1024
DB_LIMIT = 32 * 1024 * 1024
ID = re.compile(r"[A-Za-z][A-Za-z0-9_.-]{0,127}")
SHA = re.compile(r"[a-f0-9]{64}")
STAMP = re.compile(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z")


def identifier(value):
    require(isinstance(value, str) and ID.fullmatch(value), "Invalid observation identifier")


def text(value):
    require(isinstance(value, str) and 0 < len(value.strip()) <= 1000, "Invalid observation text")


def timestamp(value):
    require(isinstance(value, str), "Invalid observation timestamp")
    match = STAMP.fullmatch(value)
    require(match is not None, "Observation timestamps require UTC with at most nine fractional digits")
    try:
        seconds = calendar.timegm(datetime.strptime(match[1], "%Y-%m-%dT%H:%M:%S").timetuple())
    except ValueError:
        raise HostError("Invalid observation timestamp") from None
    return seconds * 1_000_000_000 + int((match[2] or "").ljust(9, "0"))


def checkpoint(value):
    keys(value, ("sessionId", "sequence", "digest"))
    integer(value["sequence"], 0, MAX_EVENTS)
    require(all(isinstance(value[k], str) and SHA.fullmatch(value[k]) for k in ("sessionId", "digest")),
            "Invalid journal checkpoint")


def private(path, directory=False):
    info = Path(path).lstat()
    require((stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)) and
            info.st_uid == os.getuid() and info.st_mode & 0o077 == 0, "Observer storage must be private and owned by the caller")


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _commit(connection):
    """Acknowledgement follows this synchronous commit; no external work is executed."""
    connection.commit()


def _context(source_kind, source, requirements, review, compiler_fingerprint):
    require(source_kind in ("intent", "proposal"), "Unsupported observation source kind")
    require(all(isinstance(b, bytes) and 0 < len(b) <= 2_000_000 for b in (source, requirements, review)), "Observer input size exceeded")
    req, assessment = loads(requirements), loads(review)
    require(req.get("formatVersion") == 2 and assessment.get("formatVersion") == 2, "Observer requires outcome contracts version 2")
    require(assessment.get("state") == "UNRESOLVED" and assessment.get("executionAuthorized") is False,
            "Expected a pending, non-authorizing compiler review")
    require(assessment.get("graphDigest") == req["graphDigest"] and assessment["requirementsSource"]["sha256"] == digest(requirements)
            and assessment["runId"] == req["runId"], "Compiler review does not bind the captured requirements")
    identifier(req["runId"])
    require(SHA.fullmatch(req["graphDigest"]) and SHA.fullmatch(compiler_fingerprint), "Invalid observer context digest")
    require(isinstance(req["challenge"], str) and re.fullmatch(r"[A-Za-z0-9_-]{32,128}", req["challenge"]), "Invalid run challenge")
    require(timestamp(req["startedAt"]) <= time.time_ns(), "Run start is in the future")
    text(req["execution"]["workflow"])
    targets = assessment["execution"]["invocationTargets"]
    require(isinstance(targets, list) and 0 < len(targets) <= 256, "Unsupported observer target inventory")
    nodes = {}
    for target in targets:
        keys(target, ("nodeId", "capability"), ("outputs",)); text(target["nodeId"]); text(target["capability"])
        outputs = target.get("outputs", [])
        require(isinstance(outputs, list) and len(outputs) <= 256, "Invalid observer output inventory")
        for output in outputs:
            text(output)
        require(len(set(outputs)) == len(outputs), "Ambiguous observer output inventory")
        require(target["nodeId"] not in nodes and target["capability"] != "UNKNOWN", "Ambiguous observer targets")
        nodes[target["nodeId"]] = target["capability"]
    require(all(r.get("nodeId") in nodes for r in assessment["results"]), "Unresolved operation selectors")
    binding = dict(formatVersion=1, sourceKind=source_kind, sourceSha256=digest(source), requirementsSha256=digest(requirements),
                   reviewSha256=digest(review), compilerFingerprint=compiler_fingerprint)
    return binding, req, nodes


def _create(directory, source_kind, source, requirements, review, compiler_fingerprint):
    """Internal constructor: public initialization obtains the review from the installed compiler."""
    binding, _, _ = _context(source_kind, source, requirements, review, compiler_fingerprint)
    root = Path(directory).absolute()
    root.mkdir(mode=0o700)
    private(root, True)
    path = root / "journal.sqlite3"
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    os.close(fd)
    with _connection(root, write=True) as db:
        db.execute("CREATE TABLE metadata (name TEXT PRIMARY KEY, value BLOB NOT NULL)")
        db.execute("CREATE TABLE events (sequence INTEGER PRIMARY KEY, event_id TEXT UNIQUE NOT NULL, event BLOB NOT NULL, capture BLOB NOT NULL, digest TEXT NOT NULL)")
        db.executemany("INSERT INTO metadata VALUES (?, ?)", [("binding", canonical(binding)), ("source", source),
                      ("requirements", requirements), ("review", review)])
        _commit(db)
    sync_directory(root)
    sync_directory(root.parent)
    return snapshot(root)["checkpoint"]


@contextmanager
def _connection(root, write=False):
    root = Path(root).absolute()
    private(root, True)
    path = root / "journal.sqlite3"
    private(path)
    require(path.stat().st_size <= DB_LIMIT, "Observer database size exceeded")
    db = None
    try:
        db = sqlite3.connect(path.as_uri() + "?mode=rw", uri=True, timeout=5, isolation_level=None)
        db.execute("PRAGMA trusted_schema=OFF")
        if write:
            require(db.execute("PRAGMA journal_mode=DELETE").fetchone()[0] == "delete", "Unsupported observer journal mode")
            db.execute("PRAGMA synchronous=FULL")
        else:
            db.execute("PRAGMA query_only=ON")
        db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
        yield db
    except (sqlite3.Error, KeyError, TypeError, ValueError, OverflowError):
        raise HostError("Observer journal unavailable or invalid") from None
    finally:
        if db is not None:
            db.close()


def event_for(tip, event_id, kind, data, capture=b""):
    """Build a command using a caller-retained checkpoint, never an implicit latest tip."""
    checkpoint(tip)
    return dict(formatVersion=1, sessionId=tip["sessionId"], sequence=tip["sequence"] + 1,
                previousDigest=tip["digest"], id=event_id, type=kind, data=data,
                sourceSha256=digest(capture), sourceSizeBytes=len(capture))


def _event_digest(previous, raw, capture):
    return hashlib.sha256(b"Horistum observer event\0v1\0" + bytes.fromhex(previous) + len(raw).to_bytes(4, "big") + raw +
                          len(capture).to_bytes(4, "big") + capture).hexdigest()


class History:
    def __init__(self, req, nodes, outputs):
        self.req, self.nodes = req, nodes
        self.outputs = outputs
        self.started, self.finished, self.claims, self.closed = {}, [], [], None
        self.pending = None
        self.last_time = timestamp(req["startedAt"])
        self.claim_ids, self.slots, self.errors = set(), set(), set()

    def _time(self, value):
        at = timestamp(value)
        require(timestamp(self.req["startedAt"]) <= at <= time.time_ns(), "Observation time is outside the run interval")
        return at

    def apply(self, event, capture):
        kind, data = event["type"], event["data"]
        require(self.closed is None or kind == "OBSERVATION", "The run is already closed")
        if kind == "START":
            keys(data, ("invocationId", "nodeId", "attempt", "startedAt"), ("causedBy",))
            identifier(data["invocationId"]); integer(data["attempt"], 1, 100)
            require(data["nodeId"] in self.nodes, "Unknown canonical invocation target")
            require(self.pending is None and data["invocationId"] not in self.started and len(self.started) < 256,
                    "Invocation is duplicated, concurrent or over the limit")
            require(self._time(data["startedAt"]) >= self.last_time, "Invocation starts before the preceding completion")
            if "causedBy" in data:
                require(any(i["id"] == data["causedBy"] and i["status"] == "FAILED" for i in self.finished), "Unknown failed invocation cause")
            self.pending = data["invocationId"]
            self.started[self.pending] = dict(id=self.pending, **{k: v for k, v in data.items() if k != "invocationId"})
        elif kind == "FINISH":
            keys(data, ("invocationId", "completedAt", "status"), ("errorId",))
            require(data["invocationId"] == self.pending and self.pending is not None, "Invocation has no unmatched start")
            require(data["status"] in ("SUCCEEDED", "FAILED") and bool(capture), "Known completion and actual capture are required")
            require((data["status"] == "FAILED") == ("errorId" in data), "Failure needs an explicit error identity")
            if "errorId" in data:
                identifier(data["errorId"])
                require(data["errorId"] not in self.errors, "Duplicate failure identity")
                self.errors.add(data["errorId"])
            start = self.started[self.pending]
            require(self._time(data["completedAt"]) >= timestamp(start["startedAt"]), "Completion precedes invocation start")
            require(all(timestamp(claim["observedAt"]) <= timestamp(data["completedAt"])
                        for claim, _ in self.claims if claim["kind"] == "CREATION" and claim["invocationId"] == self.pending),
                    "Completion precedes a captured creation")
            self.finished.append(dict(start, **{k: v for k, v in data.items() if k != "invocationId"}))
            self.last_time, self.pending = timestamp(data["completedAt"]), None
        elif kind == "CLOSE":
            keys(data, ("completedAt", "status"), ("errorId",))
            require(self.pending is None and bool(capture), "An outstanding invocation cannot be closed")
            require(data["status"] in ("SUCCEEDED", "FAILED") and (data["status"] == "FAILED") == ("errorId" in data), "Invalid run closure")
            require("errorId" not in data or data["errorId"] in self.errors, "Closure references an unknown error")
            require(self._time(data["completedAt"]) >= self.last_time, "Run closure precedes an invocation")
            self.closed = data
        elif kind == "OBSERVATION":
            self._claim(data, capture)
        else:
            raise HostError("Unknown observer event type")

    def _claim(self, data, capture):
        keys(data, ("id", "kind", "status", "nodeId", "invocationId", "resource", "observedAt"), ("facts", "owner", "created", "binding", "path"))
        identifier(data["id"]); identifier(data["invocationId"])
        require(data["kind"] in ("RESULT", "STATE", "OWNERSHIP", "CREATION", "BINDING") and data["status"] in ("SUCCEEDED", "FAILED", "UNKNOWN"), "Unsupported observation claim")
        require(data["invocationId"] in self.started and data["nodeId"] == self.started[data["invocationId"]]["nodeId"]
                and self.nodes[data["nodeId"]] != "APPROVE", "Observation does not identify an actual operation invocation")
        keys(data["resource"], ("scope", "type", "key"))
        require(data["resource"] in [r["identity"] for r in self.req["resources"]], "Undeclared resource identity")
        at = self._time(data["observedAt"])
        if data["kind"] == "STATE":
            require(self.closed is not None and at >= timestamp(self.closed["completedAt"]),
                    "Final state requires an explicitly closed run and a fresh capture")
        require((data["kind"] == "OWNERSHIP") == ("owner" in data) and (data["kind"] == "CREATION") == ("created" in data)
                and (data["kind"] == "STATE" or "facts" not in data)
                and (data["kind"] == "BINDING") == ("binding" in data)
                and (data["kind"] == "BINDING" or "path" not in data), "Invalid observation fields for its kind")
        if data["kind"] == "BINDING":
            identifier(data["binding"])
            require(data["binding"] in self.outputs.get(data["nodeId"], []), "Binding is not a declared output of its producer")
            path = data.get("path", [])
            require(isinstance(path, list) and len(path) <= 8 and all(
                isinstance(part, str) and 0 < len(part.strip()) and len(part) <= 128 for part in path), "Invalid output path")
            result = next((i for i in self.finished if i["id"] == data["invocationId"]), None)
            require(result is not None and data["status"] == result["status"] == "SUCCEEDED"
                    and at == timestamp(result["completedAt"]), "Binding requires its producer's actual successful completion")
        if "owner" in data:
            text(data["owner"])
            require(at <= timestamp(self.started[data["invocationId"]]["startedAt"]), "Ownership was observed after invocation start")
        if "created" in data:
            require(type(data["created"]) is bool, "Creation requires a Boolean observation")
            start = self.started[data["invocationId"]]
            finish = next((i for i in self.finished if i["id"] == data["invocationId"]), None)
            require(at >= timestamp(start["startedAt"]) and (finish is None or at <= timestamp(finish["completedAt"])),
                    "Creation capture is outside its actual invocation")
        facts = data.get("facts", {})
        require(isinstance(facts, dict) and len(facts) <= 32, "Invalid observation facts")
        for key, value in facts.items():
            identifier(key)
            require(type(value) in (str, bool, int) and (not isinstance(value, str) or len(value) <= 1000)
                    and (type(value) is not int or len(str(value)) <= 100), "Observer facts require bounded strings, Booleans or exact integers")
        if data["kind"] == "RESULT":
            result = next((i for i in self.finished if i["id"] == data["invocationId"]), None)
            require(result is not None and data["status"] == result["status"] and at == timestamp(result["completedAt"]), "Result does not match the recorded completion")
        slot = canonical([data["kind"], data["nodeId"], data["invocationId"], data["binding"], data.get("path", [])]
                         if data["kind"] == "BINDING" else [data["kind"], data["nodeId"], data["invocationId"], data["resource"]])
        require(bool(capture) and data["id"] not in self.claim_ids and slot not in self.slots and len(self.claims) < 256,
                "Observation is empty, duplicated or over the limit")
        self.claim_ids.add(data["id"]); self.slots.add(slot)
        self.claims.append((data, capture))


def _read(db):
    rows = db.execute("SELECT name, value FROM metadata LIMIT 5").fetchall()
    require(len(rows) == 4 and {r[0] for r in rows} == {"binding", "source", "requirements", "review"}, "Invalid observer metadata")
    metadata = dict(rows)
    require(all(isinstance(v, bytes) and len(v) <= 2_000_000 for v in metadata.values()), "Observer metadata limit exceeded")
    binding = loads(metadata["binding"])
    expected, req, nodes = _context(binding["sourceKind"], metadata["source"], metadata["requirements"], metadata["review"], binding["compilerFingerprint"])
    require(binding == expected, "Observer context changed")
    session = digest(b"Horistum observer session\0v1\0" + canonical(binding))
    tip = dict(sessionId=session, sequence=0, digest=session)
    targets = loads(metadata["review"])["execution"]["invocationTargets"]
    history = History(req, nodes, {target["nodeId"]: target.get("outputs", []) for target in targets})
    entries = db.execute("SELECT sequence, event_id, event, capture, digest FROM events ORDER BY sequence LIMIT ?", (MAX_EVENTS + 1,)).fetchall()
    require(len(entries) <= MAX_EVENTS, "Journal event limit exceeded")
    total, ids = sum(len(v) for v in metadata.values()), set()
    for sequence, event_id, raw, capture, stored in entries:
        require(isinstance(raw, bytes) and isinstance(capture, bytes), "Invalid journal record")
        total += len(raw) + len(capture)
        require(total <= MAX_TOTAL and event_id not in ids, "Journal budget or identity violation")
        event = _validate_event(raw, capture)
        require(sequence == tip["sequence"] + 1 and event["sequence"] == sequence and event["id"] == event_id
                and event["sessionId"] == session and event["previousDigest"] == tip["digest"], "Broken journal sequence or context")
        require(stored == _event_digest(tip["digest"], raw, capture), "Journal content changed")
        history.apply(event, capture)
        ids.add(event_id)
        tip = dict(sessionId=session, sequence=sequence, digest=stored)
    return metadata, history, tip, entries, total


def _validate_event(raw, capture):
    require(isinstance(raw, bytes) and 0 < len(raw) <= MAX_CAPTURE and isinstance(capture, bytes) and len(capture) <= MAX_CAPTURE, "Observer record size exceeded")
    event = loads(raw)
    keys(event, ("formatVersion", "sessionId", "sequence", "previousDigest", "id", "type", "data", "sourceSha256", "sourceSizeBytes"))
    require(type(event["formatVersion"]) is int and event["formatVersion"] == 1, "Unsupported observer event version")
    checkpoint(dict(sessionId=event["sessionId"], sequence=event["sequence"], digest=event["previousDigest"]))
    require(event["sequence"] > 0, "Invalid observer event sequence")
    identifier(event["id"])
    require(event["sourceSha256"] == digest(capture) and type(event["sourceSizeBytes"]) is int and event["sourceSizeBytes"] == len(capture), "Capture bytes do not match event")
    require(raw == canonical(event), "Observer events require canonical JSON encoding")
    return event


def append(directory, event_bytes, capture=b""):
    event = _validate_event(event_bytes, capture)
    with _connection(directory, write=True) as db:
        metadata, history, tip, entries, total = _read(db)
        previous = next((row for row in entries if row[1] == event["id"]), None)
        if previous:
            require(previous[2] == event_bytes and previous[3] == capture, "Event identity reused with different contents")
            return dict(sessionId=tip["sessionId"], sequence=previous[0], digest=previous[4])
        require(event["sessionId"] == tip["sessionId"] and event["sequence"] == tip["sequence"] + 1 and event["previousDigest"] == tip["digest"], "Stale or foreign checkpoint")
        require(total + len(event_bytes) + len(capture) <= MAX_TOTAL, "Journal byte budget exceeded")
        history.apply(event, capture)
        # Reserve enough space for the existing version 2 run envelope before committing.
        require(len(canonical(history.finished)) <= 56000, "Invocation history exceeds export budget")
        leaf = _event_digest(tip["digest"], event_bytes, capture)
        db.execute("INSERT INTO events VALUES (?, ?, ?, ?, ?)", (event["sequence"], event["id"], event_bytes, capture, leaf))
        _commit(db)
        return dict(sessionId=tip["sessionId"], sequence=event["sequence"], digest=leaf)


def snapshot(directory, expected=None):
    with _connection(directory) as db:
        metadata, history, tip, entries, _ = _read(db)
        if expected is not None:
            checkpoint(expected)
            require(tip == expected, "Journal does not match the caller-retained checkpoint")
        state = "CLOSED" if history.closed else "PENDING_INVOCATION" if history.pending else "OPEN"
        review = loads(metadata["review"])
        return dict(formatVersion=1, checkpoint=tip, state=state, pendingInvocations=[history.pending] if history.pending else [],
                    completedInvocations=history.finished, observationCount=len(history.claims), executionAuthorized=False,
                    invocationTargets=review["execution"]["invocationTargets"],
                    operationTargets=[{k: r[k] for k in ("id", "nodeId", "resource")} for r in review["results"]],
                    _metadata=metadata, _history=history)


def public_snapshot(value):
    return {k: v for k, v in value.items() if not k.startswith("_")}
