#!/usr/bin/env python3
"""Prepare an inventory challenge or collect a signed export through a configured process."""
import argparse
from pathlib import Path
import sys

from authoring_host.contracts import HostError, canonical, loads, read
from authoring_host.inventory_collection import collect, prepare


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    request = commands.add_parser("prepare")
    request.add_argument("--request", required=True)
    request.add_argument("--system", required=True)
    request.add_argument("--scope", required=True)
    request.add_argument("--lifetime-seconds", type=int, default=900)
    request.add_argument("--out", required=True)
    capture = commands.add_parser("collect")
    for option in ("capture-request", "collector-config", "trust", "private-key", "out"):
        capture.add_argument("--" + option, required=True)
    capture.add_argument("--max-age-seconds", type=int, default=900)
    capture.add_argument("--max-clock-skew-seconds", type=int, default=5)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            value = prepare(loads(read(args.request)), args.system, args.scope, args.lifetime_seconds)
            with Path(args.out).open("xb") as output:
                output.write(canonical(value))
            result = dict(status="prepared", captureRequestFile=str(Path(args.out).absolute()))
        else:
            result = collect(loads(read(args.capture_request)), loads(read(args.collector_config)), loads(read(args.trust)),
                             args.private_key, args.out, max_age_seconds=args.max_age_seconds,
                             max_clock_skew_seconds=args.max_clock_skew_seconds)
        sys.stdout.buffer.write(canonical(result))
        return 0
    except (HostError, OSError, ValueError):
        print("Managed inventory preparation or collection failed; no authoring or execution is authorized.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
