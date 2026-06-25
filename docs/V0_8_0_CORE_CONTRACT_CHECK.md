# v0.8.0 Core Contract Check

v0.8.0 introduces a compact core contract check for the public standard surface.

The check verifies that required stable artifacts are still present, stable artifact ids are unique, standard candidate checks are unique, and `StandardModel` remains well formed.

This release does not change Flow syntax, target rendering semantics, execution behavior, or the active public standard version. The package version moves to `0.8.0`; the active public standard version remains controlled by `FlowStandardVersions.FLOW_STANDARD_VERSION` and its snapshot/conformance gates.

The goal is to prepare the project for a future public contract freeze without mixing that work with renderer or runner changes.
