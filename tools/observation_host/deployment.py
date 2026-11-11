"""Collector/witness coordination over the unchanged, compiler-admitted event journal."""
import base64
from pathlib import Path

from authoring_host.contracts import canonical, keys, require
from . import channel, journal, witness


def request(operation, **values):
    return dict(formatVersion=1, operation=operation, **values)


def _request(value, operation, fields):
    keys(value, ("formatVersion", "operation", *fields))
    require(type(value["formatVersion"]) is int and value["formatVersion"] == 1 and value["operation"] == operation,
            "Unsupported observer service command")


def witness_handler(directory, value):
    operation = value.get("operation")
    if operation == "STATUS":
        _request(value, operation, ("sessionId",))
        return witness.current(directory, value["sessionId"])
    _request(value, "ADVANCE", ("previous", "following"))
    return witness.advance(directory, value["previous"], value["following"])


class Collector:
    def __init__(self, directory, witness_socket, genesis):
        journal.checkpoint(genesis)
        require(genesis["sequence"] == 0 and genesis["digest"] == genesis["sessionId"], "Pinned genesis required")
        self.directory, self.witness_socket = Path(directory).absolute(), Path(witness_socket).absolute()
        self.genesis = genesis
        # A separate path is necessary; the operator must also separate backup/failure domains.
        require(self.directory.resolve() not in self.witness_socket.resolve().parents, "Witness endpoint must be outside the journal")

    def retained(self):
        value = channel.call(self.witness_socket, request("STATUS", sessionId=self.genesis["sessionId"]))
        journal.checkpoint(value)
        require(value["sessionId"] == self.genesis["sessionId"], "Wrong witness session")
        return value

    def _inspect(self):
        retained = self.retained()
        # Validate the retained prefix and the complete history in the same read transaction.
        with journal._connection(self.directory) as db:
            _, _, tip, entries, _ = journal._read(db)
        require(tip["sessionId"] == self.genesis["sessionId"], "Collector context changed")
        receipts = [self.genesis] + [dict(sessionId=tip["sessionId"], sequence=row[0], digest=row[4]) for row in entries]
        require(retained["sequence"] < len(receipts) and receipts[retained["sequence"]] == retained,
                "Journal lost or replaced a witnessed prefix")
        require(tip["sequence"] - retained["sequence"] <= 1, "Uncoordinated journal writes require investigation")
        return retained, tip, entries

    def __call__(self, value):
        operation = value.get("operation")
        _request(value, operation, ("sessionId",) if operation == "STATUS" else ("event", "capture"))
        require(operation in ("STATUS", "APPEND"), "Unsupported collector command")
        with channel.lock(self.directory / "collector.lock"):
            retained, tip, entries = self._inspect()
            if operation == "STATUS":
                require(value["sessionId"] == self.genesis["sessionId"], "Wrong collector session")
                result = journal.public_snapshot(journal.snapshot(self.directory, tip))
                result.update(retainedCheckpoint=retained, retentionState="CONFIRMED" if tip == retained else "REDELIVERY_REQUIRED")
                return result
            require(isinstance(value["capture"], str) and len(value["capture"]) <= 87384, "Capture limit exceeded")
            capture = base64.b64decode(value["capture"], validate=True)
            raw = canonical(value["event"])
            journal._validate_event(raw, capture)
            if tip != retained:
                # Never automatically bless a newer local tip: only the identical pending command may heal the gap.
                require(entries[-1][2] == raw and entries[-1][3] == capture, "Redeliver the unconfirmed event first")
            acknowledged = journal.append(self.directory, raw, capture)
            following = journal.snapshot(self.directory)["checkpoint"]
            expected_tip = acknowledged if acknowledged["sequence"] > tip["sequence"] else tip
            require(following == expected_tip, "Concurrent uncoordinated writer")
            if following != retained:
                require(acknowledged == following, "Only the delivered event may advance the witness")
                confirmed = channel.call(self.witness_socket, request("ADVANCE", previous=retained, following=following))
                require(confirmed == following, "Witness confirmed another checkpoint")
            # A duplicate returns its original receipt, never an implicit newer checkpoint.
            return acknowledged


def append_request(event, capture=b""):
    return request("APPEND", event=event, capture=base64.b64encode(capture).decode("ascii"))


def confirmed_export(directory, expected, witness_socket, cli, trust, private_key, key_id, output):
    from .session import export
    journal.checkpoint(expected)
    with channel.lock(Path(directory) / "collector.lock"):
        retained = channel.call(witness_socket, request("STATUS", sessionId=expected["sessionId"]))
        require(retained == expected, "Export must select the current witnessed checkpoint")
        # Existing export requires this exact journal snapshot and preserves signature domains.
        return export(directory, expected, cli, trust, private_key, key_id, output)
