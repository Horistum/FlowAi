"""Test synchronization point between a genuine signed GET and the installed compiler decision."""
import json
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host.contracts import canonical, loads, require
from kubernetes_configmap.common import emit, frame


def main():
    provider, config, kubectl, kubeconfig = sys.argv[1:]
    runtime = [sys.executable, provider, "runtime", "--config", config]
    request = frame()
    # Inherit the bounded host process group so timeout also stops the writer.
    with subprocess.Popen(runtime, stdin=subprocess.PIPE, stdout=subprocess.PIPE) as child:
        try:
            child.stdin.write(canonical(request))
            child.stdin.flush()
            first = loads(child.stdout.readline(2_000_001))
            require(first["kind"] == "observations", "Competitor missed the observation boundary")
            duplicate = subprocess.run(runtime, input=canonical(request), capture_output=True, check=True, timeout=8)
            require(loads(duplicate.stdout).get("reason") == "conflict", "Duplicate entered the reserved dispatch")
            subprocess.run([kubectl, "--kubeconfig", kubeconfig, "--request-timeout=4s", "-n", "commissioning",
                "patch", "configmap", "settings", "--type=merge", "-p", json.dumps(dict(data=dict(mode="concurrent")))],
                capture_output=True, check=True, timeout=8)
            emit(first)
            decision = frame()
            child.stdin.write(canonical(decision))
            child.stdin.close()
            emit(loads(child.stdout.readline(2_000_001)))
            require(child.wait(timeout=8) == 0, "Writer did not exit cleanly")
        finally:
            if child.poll() is None:
                child.kill()


if __name__ == "__main__":
    main()
