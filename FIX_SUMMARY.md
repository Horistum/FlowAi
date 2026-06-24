# Flow v0.7.6 Semantic Correctness Hardening

This release fixes semantic defects found in v0.7.5 without changing Flow's main architectural direction. Flow remains an AI-first standardization layer for DevOps/IT automation intent, not a runtime executor, SDK API, plugin lifecycle or target-specific DSL.

## Fixed behavior

- Kubernetes deploy lowering now preserves the application identity through an explicit `app` parameter instead of relying on a literal `app` fallback in the renderer.
- Runtime input references such as `environment` and `version` are rendered as target-specific runtime parameters instead of shell literals.
- Parameter interpolation is centralized for target command rendering so equivalent values behave consistently across shell, git, docker, helm, Kubernetes, ArgoCD, REST, database, file and notification actions.
- Mandatory safety requirements are enforced in the core intent validator, so manual intent files and AI-normalized intents receive the same verdict.
- Flow validator block scopes now preserve sibling declarations inside `else`, `parallel`, `match`, `try`, `retry` and local error handlers.
- Mixed safe-navigation paths preserve per-segment semantics instead of converting the whole reference into fully safe navigation.
- Condition parsing no longer rewrites quotes globally; the lexer supports both single-quoted and double-quoted string literals.
- Exact `${name}` values in intent YAML now load as `IntentRef(name)`, not as a malformed `{name}` reference.
- Jenkins `matches <named pattern>` no longer falls back to `/.*/`; supported named patterns are mapped explicitly and unsupported dynamic patterns fail loudly.
- Intent `requires` no longer implicitly converts independent steps into parallel execution. Ordering remains deterministic unless parallelism is explicit in Flow source.
- Intent step dependency cycle diagnostics are deduplicated and no longer depend on traversal side effects.
- End-to-end rendered snapshots are now compared exactly against regenerated Jenkins/GitHub Actions/Tekton output in conformance.
- Tekton conditional rendering now keeps `when` and `taskSpec` at valid YAML levels.

## Validation performed in this sandbox

- Core semantic Kotlin subset compiled successfully with `kotlinc`.
- Manifest renderers compiled successfully with the core subset.
- `IntentYamlLoader` compiled with minimal Jackson stubs to verify source-level Kotlin correctness in the offline sandbox.
- Rendered Jenkins, GitHub Actions and Tekton snapshots were regenerated from the fixed planning/rendering path.

## Validation not completed here

Full Gradle test execution could not run because this source package does not contain the Gradle distribution/cache and the sandbox has no internet access. The wrapper attempts to download `gradle-8.10.2-bin.zip` from `services.gradle.org` even with `--offline`, which fails with `UnknownHostException`.
