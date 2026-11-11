"""One explicitly enrolled run's durable checkpoint witness; contains no captured work."""
from contextlib import contextmanager
import os
from pathlib import Path
import sqlite3

from authoring_host.contracts import HostError, require
from .journal import MAX_EVENTS, checkpoint, private, sync_directory


@contextmanager
def connection(directory, write=False):
    root = Path(directory).absolute()
    private(root, True)
    path = root / "receipts.sqlite3"
    private(path)
    require(path.stat().st_size <= 1024 * 1024, "Witness storage limit exceeded")
    db = None
    try:
        db = sqlite3.connect(path.as_uri() + "?mode=rw", uri=True, timeout=5, isolation_level=None)
        db.execute("PRAGMA trusted_schema=OFF")
        if write:
            require(db.execute("PRAGMA journal_mode=DELETE").fetchone()[0] == "delete", "Invalid witness journal mode")
            db.execute("PRAGMA synchronous=FULL")
        else:
            db.execute("PRAGMA query_only=ON")
        db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
        yield db
    except sqlite3.Error:
        raise HostError("Witness storage unavailable or invalid") from None
    finally:
        if db is not None:
            db.close()


def enroll(directory, genesis):
    checkpoint(genesis)
    require(genesis["sequence"] == 0 and genesis["digest"] == genesis["sessionId"], "Explicit genesis enrollment required")
    root = Path(directory).absolute()
    root.mkdir(mode=0o700)
    private(root, True)
    fd = os.open(root / "receipts.sqlite3", os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    os.close(fd)
    with connection(root, True) as db:
        db.execute("CREATE TABLE receipts (sequence INTEGER PRIMARY KEY, session TEXT NOT NULL, digest TEXT NOT NULL)")
        db.execute("INSERT INTO receipts VALUES (?, ?, ?)", (0, genesis["sessionId"], genesis["digest"]))
        db.commit()
    sync_directory(root)
    sync_directory(root.parent)
    return genesis


def _read(db, session):
    rows = db.execute("SELECT sequence, session, digest FROM receipts ORDER BY sequence LIMIT ?", (MAX_EVENTS + 2,)).fetchall()
    require(0 < len(rows) <= MAX_EVENTS + 1, "Invalid witness history size")
    receipts = []
    for index, (sequence, actual_session, digest) in enumerate(rows):
        value = dict(sessionId=actual_session, sequence=sequence, digest=digest)
        checkpoint(value)
        require(sequence == index and actual_session == session and (index != 0 or digest == session), "Invalid witness history")
        receipts.append(value)
    return receipts


def current(directory, session):
    with connection(directory) as db:
        return _read(db, session)[-1]


def advance(directory, previous, following):
    checkpoint(previous); checkpoint(following)
    require(previous["sessionId"] == following["sessionId"] and following["sequence"] == previous["sequence"] + 1,
            "Witness advances exactly one checkpoint")
    with connection(directory, True) as db:
        receipts = _read(db, previous["sessionId"])
        require(previous["sequence"] < len(receipts) and receipts[previous["sequence"]] == previous, "Conflicting witness predecessor")
        if following["sequence"] < len(receipts):
            require(receipts[following["sequence"]] == following, "Conflicting witnessed history")
            return following
        require(receipts[-1] == previous, "Witness checkpoint is stale")
        db.execute("INSERT INTO receipts VALUES (?, ?, ?)", (following["sequence"], following["sessionId"], following["digest"]))
        db.commit()
        return following
