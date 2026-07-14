# v0.9.5.7.9 Reference Scenario and Snapshot Honesty Reset

## Problem

The flagship build/test/deploy reference scenario still used `shell.run` and described the test step as the universal capability `command.run`. Its committed target outputs were named like executable Jenkins, GitHub Actions and Tekton artifacts even though the render policy classified them as review-only and non-executable. Adapter expectations were also permissive enough that a review-required scenario accepted supported, degraded or blocked results interchangeably.

## Semantic correction

The flagship test step now uses `standard.execute` with the universal `software.test` capability. The reference intent example and intent analyzer use the semantic `standard` system instead of assigning build and test meaning to shell execution.

Explicit shell intent remains supported only as preserved user intent that materializes to blocked and fail-fast evidence. It is no longer the flagship automation path.

## Exact adapter expectations

`ReferenceAdapterProjectionMatrix` now distinguishes:

- `EXECUTABLE`
- `REVIEW_ONLY`
- `BLOCKED`

Positive reference scenarios currently require exact `REVIEW_ONLY` render mode, `executable=false` and degraded evidence. Negative scenarios require exact `BLOCKED` outcomes. A review-only expectation no longer accepts executable or blocked output merely because both are non-crashing states.

## Snapshot evidence

`ReferenceSnapshotHonesty` defines a versioned snapshot index with:

- semantic artifact layer and state
- target projection state
- capability compatibility
- effective compatibility
- materialization readiness
- projection readiness
- render mode
- executable flag

The target files are committed as:

```text
jenkins.review.yaml
github-actions.review.yaml
tekton.review.yaml
```

Legacy executable-looking names are forbidden until complete materialization and renderer payload evidence exists.

## Conformance

Conformance now regenerates the normalized intent, Flow AST, canonical Execution Plan, snapshot index and rendered target artifacts through the real pipeline and compares them exactly with committed snapshots. It also rejects active shell projection in the flagship plan, stale snapshot files and documentation that claims end-to-end execution.

Snapshots were generated transactionally by the real parser, intent planner, Flow planner, compatibility analyzer, manifest generators and render policy. They were not edited to satisfy assertions.

## Validation

The v0.9.5.7.9 transactional validation workflow run `#39` passed:

- exact implementation patch checks
- snapshot generation from the real pipeline
- Flow Agent tooling tests
- Flow Agent structure validation
- Flow Agent context generation
- `./gradlew --no-daemon clean test --stacktrace --console=plain`
- `./gradlew --no-daemon run --args="conformance" --stacktrace --console=plain`
- persistence of the fully validated implementation

The standard Flow CI on the final clean branch remains the merge gate.

## Version boundary

- package remains `0.9.4`
- correction item is `0.9.5.7.9`
- public Flow standard remains `0.7.6`
- artifact contract versions remain unchanged

## Architecture boundary

No runtime executor, SDK API, plugin lifecycle, framework lifecycle, shell projection, fabricated renderer payload or target-specific public Flow DSL is introduced.
