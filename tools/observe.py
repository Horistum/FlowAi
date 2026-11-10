#!/usr/bin/env python3
"""Durable capture commands for trusted external observers; never executes workflow operations."""
import argparse
import json
import sys

from authoring_host.contracts import HostError, canonical, loads
from observation_host.journal import append, public_snapshot, snapshot
from observation_host.session import export, initialize, read_bytes


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init")
    init.add_argument("--source", required=True)
    init.add_argument("--source-kind", choices=("intent", "proposal"), required=True)
    init.add_argument("--requirements", required=True)
    init.add_argument("--cli", required=True)
    add = commands.add_parser("append")
    add.add_argument("--event", required=True)
    add.add_argument("--capture")
    status = commands.add_parser("status")
    status.add_argument("--checkpoint")
    publish = commands.add_parser("export")
    for name in ("checkpoint", "cli", "trust", "private-key", "key-id", "out"):
        publish.add_argument("--" + name, required=True)
    for command in (init, add, status, publish):
        command.add_argument("--journal", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "init":
            result = initialize(args.journal, args.cli, args.source_kind, read_bytes(args.source), read_bytes(args.requirements))
        elif args.command == "append":
            result = append(args.journal, canonical(loads(read_bytes(args.event, 65536))),
                            read_bytes(args.capture, 65536) if args.capture else b"")
        elif args.command == "status":
            result = public_snapshot(snapshot(args.journal, loads(read_bytes(args.checkpoint)) if args.checkpoint else None))
        else:
            result = export(args.journal, loads(read_bytes(args.checkpoint)), args.cli, read_bytes(args.trust),
                            args.private_key, args.key_id, args.out)
        print(json.dumps(result, sort_keys=True))
        return result.get("exitCode", 0)
    except (HostError, OSError, ValueError, TypeError, KeyError, AttributeError):
        # Payloads, captures, SQL text, private paths and signing failures never enter diagnostics.
        print(json.dumps({"error": "OBSERVER_INPUT_OR_STORAGE_REJECTED", "executionAuthorized": False}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
