# Flow v0.7.6 Semantic Correctness Hardening - Fix 2

This package is a full-offline build correction for the v0.7.6 semantic-correctness-hardening release.

Flow remains an AI-first standardization layer for DevOps/IT automation intent. This fix does not introduce a runtime executor, SDK-first architecture, plugin lifecycle, target-specific public DSL, adapter framework, or new Flow syntax.

## What failed

The project compiled successfully, but the full Gradle test suite failed on exact rendered snapshot conformance:

- `FlowConformanceBridgeJUnitTest > betaConformanceScenariosPass()`
- `FlowRcConformanceTests > rcConformanceRunnerPasses()`

The failing conformance check was `snapshots.e2e.content`.

## Root cause

v0.7.6 changed the conformance gate so rendered target snapshots are compared exactly against regenerated output. That gate exposed stale rendered snapshots for:

- GitHub Actions
- Tekton

The generated output had correctly changed because the target compatibility model now emits mapping notes and accurate partial/unsupported target feature visibility. The committed snapshots had not been updated to reflect those reviewed semantics.

## Fixed in this package

- Regenerated `conformance/snapshots/build-test-deploy/github-actions.yml` from the real v0.7.6 GitHub Actions rendering path.
- Regenerated `conformance/snapshots/build-test-deploy/tekton-pipeline.yaml` from the real v0.7.6 Tekton rendering path.
- Kept the semantic-correctness fixes from v0.7.6 intact:
  - Kubernetes deploy uses the real application identity instead of a literal `app` fallback.
  - Runtime references such as `environment` and `version` render as target-native runtime parameters.
  - Target command interpolation is centralized across action families.
  - Validator block scopes preserve sibling declarations inside control-flow blocks.
  - Mixed safe-navigation paths preserve per-segment semantics.
  - Exact `${name}` YAML intent values load as references without malformed braces.
  - Jenkins named patterns no longer fall back to a match-everything regex.
  - Intent `requires` remains deterministic ordering, not hidden parallelization.
  - Mandatory safety obligations are enforced by the core intent validator.

## Validation performed

The actual full offline Gradle commands were executed successfully:

```bash
GRADLE_USER_HOME=$PWD/.gradle-user-home ./gradlew --offline --no-daemon clean test --stacktrace --console=plain
GRADLE_USER_HOME=$PWD/.gradle-user-home ./gradlew --offline --no-daemon run --args="conformance" --console=plain
```

Results:

```text
clean test: BUILD SUCCESSFUL in 1m 19s; 151 tests; 0 failures; 0 errors; 0 skipped
conformance: Passed 76; Failed 0; BUILD SUCCESSFUL in 12s
```

## Quality note

This was fixed by updating conformance artifacts to match the reviewed generated semantics. No test was weakened, disabled, bypassed, or relaxed. The exact snapshot gate remains active and now protects the rendered output from future bitrot.
