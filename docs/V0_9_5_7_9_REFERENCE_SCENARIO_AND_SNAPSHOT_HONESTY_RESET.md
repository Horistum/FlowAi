# v0.9.5.7.9 Reference Scenario and Snapshot Honesty Reset

## Purpose

Align flagship reference scenarios, committed snapshots and conformance claims with the repaired materialization and renderer-readiness model.

## Changes

- Replaced the active flagship `shell.run` test step with `standard.execute` and the universal `software.test` capability.
- Made adapter expectations exact: executable, review-only and fail-fast are distinct outcomes.
- Added a snapshot evidence index derived from concrete compatibility, materialization, projection and render state.
- Renamed non-executable target files to `.review.yaml` and forbade executable-looking legacy snapshot names.
- Required conformance to compare the complete normalized intent, AST, canonical plan, snapshot index and rendered review artifacts against the real pipeline.
- Removed the false end-to-end execution claim from snapshot documentation.

## Result

The reference snapshot set proves semantic normalization and current projection readiness. It does not claim that Jenkins, GitHub Actions or Tekton artifacts are executable while renderer payload evidence remains incomplete.

## Architecture boundary

This correction does not introduce runtime execution, an SDK, a framework lifecycle, shell projection, target-specific public Flow syntax or fabricated renderer payloads. Package `0.9.4`, public standard `0.7.6` and artifact contract versions remain unchanged.
