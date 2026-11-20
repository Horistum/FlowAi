#!/usr/bin/env python3
"""Run the commissioned external writer or the independently configured audit observer."""
import argparse
import http.client
import subprocess
import sys

from authoring_host.contracts import HostError
from kubernetes_provider import observer, runtime
from kubernetes_provider.common import json_file


def main(*, configmap_only=False):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=("runtime", "observer"))
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    try:
        config = json_file(args.config)
        if configmap_only:
            if "profileFile" in config or config.setdefault("profile", "configmap-text") != "configmap-text":
                raise HostError("The compatibility entry point accepts only its original profile")
        (runtime.run if args.role == "runtime" else observer.run)(config)
    except (HostError, KeyError, ValueError, TypeError, OSError, http.client.HTTPException, subprocess.SubprocessError):
        print("Kubernetes exchange failed. Inspect retained host evidence and the provider audit.", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
