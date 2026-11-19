#!/usr/bin/env python3
"""Run the commissioned external writer or the independently configured audit observer."""
import argparse
import http.client
import subprocess
import sys

from authoring_host.contracts import HostError
from kubernetes_configmap import observer, runtime
from kubernetes_configmap.common import json_file


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=("runtime", "observer"))
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    try:
        config = json_file(args.config)
        (runtime.run if args.role == "runtime" else observer.run)(config)
    except (HostError, KeyError, ValueError, TypeError, OSError, http.client.HTTPException, subprocess.SubprocessError):
        print("ConfigMap exchange failed. Inspect retained host evidence and the provider audit.", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
