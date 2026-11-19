#!/usr/bin/env python3
"""Compatibility entry point for the original ConfigMap-only commissioning profile."""
from kubernetes_resource_dispatch import main

if __name__ == "__main__":
    raise SystemExit(main(configmap_only=True))
