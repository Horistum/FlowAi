# Flow v0.7.6 Full Offline Build Validation - Fix 2

Version: `0.7.6-semantic-correctness-hardening-fix2`

## Validation context

The user-provided `flow-core-prod-v0_7_6_full-offline-fix2.zip` package was used as the source of truth. This package includes a working local Gradle distribution and dependency cache under `.gradle-user-home`, so the actual offline Gradle build was executed in the sandbox.

The sandbox had approximately 4 GiB RAM available. To keep the build reliable under that limit, `gradle.properties` was adjusted to use one worker and a bounded JVM heap:

```properties
org.gradle.jvmargs=-Xmx2500m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8
org.gradle.workers.max=1
org.gradle.parallel=false
org.gradle.daemon=false
kotlin.compiler.execution.strategy=in-process
kotlin.daemon.jvmargs=-Xmx1800m -XX:MaxMetaspaceSize=768m
```

This is an environment-safety setting only. It does not change Flow semantics, public syntax, generated artifacts, target contracts, or standard behavior.

## Initial failure reproduced

The first full offline build reproduced the failure in the test phase, not compilation:

```text
FlowConformanceBridgeJUnitTest > betaConformanceScenariosPass() FAILED
FlowRcConformanceTests > rcConformanceRunnerPasses() FAILED
```

The root failure was:

```text
snapshots.e2e.content - Snapshot mismatch for github-actions.yml
snapshots.e2e.content - Snapshot mismatch for tekton-pipeline.yaml
```

## Root cause

v0.7.6 intentionally changed rendered target output semantics by making end-to-end rendered snapshots exact. The generated GitHub Actions and Tekton manifests now include target compatibility/mapping notes and accurate compatibility status. The committed rendered snapshots still reflected the pre-fix text, so the new exact snapshot gate correctly failed.

This was not a compiler failure and not a generator regression. It was stale conformance artifact drift exposed by the exact snapshot gate.

## Fix applied

Reviewed and regenerated the affected rendered snapshots from the actual v0.7.6 rendering path:

- `conformance/snapshots/build-test-deploy/github-actions.yml`
- `conformance/snapshots/build-test-deploy/tekton-pipeline.yaml`

The updated snapshots now preserve the intended v0.7.6 semantics:

- GitHub Actions output is marked as `PARTIAL` when generated with mapping notes for partially supported target features.
- Tekton output includes explicit mapping notes for unsupported/partial features instead of silently pretending full support.
- Kubernetes deploy still targets `deployment/'build-test-deploy'`, not the old literal `deployment/'app'` fallback.
- Runtime inputs still render as target-native parameters such as `${{ inputs.version }}`, `${{ inputs.environment }}`, `$(params.version)`, and `$(params.environment)`.

## Commands executed

Full offline test suite:

```bash
GRADLE_USER_HOME=$PWD/.gradle-user-home ./gradlew --offline --no-daemon clean test --stacktrace --console=plain
```

Result:

```text
BUILD SUCCESSFUL in 1m 19s
151 tests, 0 failures, 0 errors, 0 skipped
```

Conformance command:

```bash
GRADLE_USER_HOME=$PWD/.gradle-user-home ./gradlew --offline --no-daemon run --args="conformance" --console=plain
```

Result:

```text
Passed: 76
Failed: 0
BUILD SUCCESSFUL in 12s
```

## Scope confirmation

This fix does not add a runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion, or Flow syntax change. It keeps Flow on its intended path: an AI-first standardization layer for DevOps/IT automation with explicit validation, target capability visibility, and no silent semantic fallback.
