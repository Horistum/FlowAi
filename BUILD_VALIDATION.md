# Flow v0.7.6-fix1 Build Validation

Version: `0.7.6-semantic-correctness-hardening-fix1`

## Validation context

The user-provided `flow-core-prod-v0_7_6_full-offline.zip` was used as the source of truth for this corrective package.

The Gradle wrapper is present, but the package still does not include a locally usable Gradle distribution/cache. In this sandbox, even `--offline` attempts to download Gradle from `services.gradle.org`, which is blocked. Full Gradle execution therefore cannot be honestly claimed here.

Observed Gradle failure:

```text
Downloading https://services.gradle.org/distributions/gradle-8.10.2-bin.zip
java.net.UnknownHostException: services.gradle.org
```

## Source-level compilation performed

Because Gradle could not bootstrap offline, the source tree was compiled directly with the installed Kotlin compiler (`kotlinc 1.9.0`) in dependency-ordered chunks. Minimal Jackson API stubs were used only as compile-check scaffolding because the sandbox has no dependency cache. These stubs are not part of the project source.

The following source groups compiled successfully after the fixes:

- AST model
- Parser
- Intent model
- Standard core model
- Module registry
- Intent validator and planner
- Flow validator
- Execution planner
- Target capability analysis
- Target manifest generators/renderers
- Target expression translators
- Intent YAML loader
- Adapter boundary helpers
- AI normalization and scenario-pack sources
- CLI JSON helper sources
- Conformance base models
- Artifact export helpers
- Purpose coverage analyzer
- Architecture drift analyzer
- Conformance runner and reference corpus harness
- Flow CLI source

## Compile errors fixed

### TargetExpressionTranslator smart-cast error

The Kotlin compiler rejected direct access to `e.right.items` after `e.right is ListLiteralNode` because `right` is a public API property. The renderer now captures `e.right` in a local immutable value before the type check.

### Mandatory safety path inconsistency

The previous v0.7.6 source silently applied mandatory safety transformations before validation. That could make the validator pass while the lowered AST still used the original unsafe intent. The corrected implementation reports mandatory safety issues directly from `IntentCapabilityValidator` and does not synthesize hidden policies.

## Semantic smoke validation

A standalone Kotlin smoke program was compiled and executed against the compiled source chunks. It verified:

- Kubernetes deploy renders `deployment/'build-test-deploy'`, not `deployment/'app'`.
- Runtime inputs render as `${params.environment}` and `${params.version}` for Jenkins output.
- Manual `DATABASE_MIGRATE` without backup is rejected with `SAFETY_REQUIRES_BACKUP`.
- Mixed safe navigation keeps `a.b?.c` and `a?.b.c` distinct.
- Jenkins named pattern rendering does not fall back to `/.*/`.

Smoke result:

```text
SMOKE_OK
```

## Full validation to run in a complete offline build environment

Run these once the Gradle distribution and dependency cache are actually available locally:

```bash
GRADLE_USER_HOME=$PWD/.gradle-user-home ./gradlew --no-daemon clean test --offline --stacktrace --console=plain
GRADLE_USER_HOME=$PWD/.gradle-user-home ./gradlew --no-daemon run --args="conformance" --offline --stacktrace --console=plain
```

