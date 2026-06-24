# Build/Test/Deploy End-to-End Snapshot

This directory contains the first public end-to-end Flow conformance snapshot.

The snapshot represents the platform-neutral pipeline:

`Intent YAML -> normalized intent -> Flow AST -> Execution Plan -> Compatibility Report -> Target Manifest -> Rendered Output`

The files are intentionally committed as standard artifacts. Future releases should compare generated outputs against these snapshots or update them explicitly when the standard changes.
