#!/usr/bin/env bash
# Run from the repository root with the planning-worker Python and installed CLI.
set -euo pipefail
worker_python=${1:?pass the planning-worker Python executable}
flow_cli=${2:?pass the installed Flow CLI executable}
bundle=$(mktemp -d)
trap 'rm -rf "$bundle"' EXIT
asset=envtest-v1.34.1-linux-amd64.tar.gz
curl --fail --silent --show-error --location --proto '=https' --retry 2 --max-time 120 \
  "https://github.com/kubernetes-sigs/controller-tools/releases/download/envtest-v1.34.1/$asset" -o "$bundle/$asset"
(cd "$bundle" && echo "c8500090806ed5ce4064eeeb2a5666476a5168c1f4ff0eadd54fe59b22c4baa7  $asset" | sha256sum --check)
tar -xzf "$bundle/$asset" -C "$bundle" controller-tools/envtest/{etcd,kube-apiserver,kubectl}
"$worker_python" tools/verify_kubernetes_dispatch.py --cli "$flow_cli" --binaries "$bundle/controller-tools/envtest"
