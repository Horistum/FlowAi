#!/usr/bin/env python3
"""Durable capture commands for trusted external observers; never executes workflow operations."""
import argparse
import json
import sys
import signal
from functools import partial

from authoring_host.contracts import HostError, canonical, loads
from observation_host.journal import append, public_snapshot, snapshot
from observation_host.session import export, initialize, read_bytes
from observation_host import channel, deployment, witness


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
    publish.add_argument("--witness-socket")
    for name in ("checkpoint", "cli", "trust", "private-key", "key-id", "out"):
        publish.add_argument("--" + name, required=True)
    for command in (init, add, status, publish):
        command.add_argument("--journal", required=True)
    enroll = commands.add_parser("enroll-witness")
    enroll.add_argument("--store", required=True)
    enroll.add_argument("--genesis", required=True)
    witness_server = commands.add_parser("serve-witness")
    witness_server.add_argument("--store", required=True)
    collector = commands.add_parser("serve")
    collector.add_argument("--journal", required=True)
    collector.add_argument("--witness-socket", required=True)
    collector.add_argument("--genesis", required=True)
    for command in (witness_server, collector):
        command.add_argument("--socket", required=True)
    send = commands.add_parser("send")
    send.add_argument("--socket", required=True)
    send.add_argument("--event", required=True)
    send.add_argument("--capture")
    inspect = commands.add_parser("inspect")
    inspect.add_argument("--socket", required=True)
    inspect.add_argument("--genesis", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "init":
            result = initialize(args.journal, args.cli, args.source_kind, read_bytes(args.source), read_bytes(args.requirements))
        elif args.command == "append":
            result = append(args.journal, canonical(loads(read_bytes(args.event, 65536))),
                            read_bytes(args.capture, 65536) if args.capture else b"")
        elif args.command == "status":
            result = public_snapshot(snapshot(args.journal, loads(read_bytes(args.checkpoint)) if args.checkpoint else None))
        elif args.command == "export":
            parameters = (args.journal, loads(read_bytes(args.checkpoint)), args.cli, read_bytes(args.trust),
                          args.private_key, args.key_id, args.out)
            result = deployment.confirmed_export(*parameters[:2], args.witness_socket, *parameters[2:]) if args.witness_socket else export(*parameters)
        elif args.command == "enroll-witness":
            result = witness.enroll(args.store, loads(read_bytes(args.genesis)))
        elif args.command in ("serve", "serve-witness"):
            def stop(*_):
                raise SystemExit(0)
            signal.signal(signal.SIGTERM, stop)
            if args.command == "serve":
                handler = deployment.Collector(args.journal, args.witness_socket, loads(read_bytes(args.genesis)))
                handler(deployment.request("STATUS", sessionId=handler.genesis["sessionId"]))
            else:
                handler = partial(deployment.witness_handler, args.store)
            channel.serve(args.socket, handler)
            return 0
        elif args.command == "send":
            result = channel.call(args.socket, deployment.append_request(loads(read_bytes(args.event, 65536)),
                read_bytes(args.capture, 65536) if args.capture else b""))
        else:
            genesis = loads(read_bytes(args.genesis))
            result = channel.call(args.socket, deployment.request("STATUS", sessionId=genesis["sessionId"]))
        print(json.dumps(result, sort_keys=True))
        return result.get("exitCode", 0)
    except (HostError, OSError, ValueError, TypeError, KeyError, AttributeError):
        # Payloads, captures, SQL text, private paths and signing failures never enter diagnostics.
        print(json.dumps({"error": "OBSERVER_INPUT_OR_STORAGE_REJECTED", "executionAuthorized": False}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
