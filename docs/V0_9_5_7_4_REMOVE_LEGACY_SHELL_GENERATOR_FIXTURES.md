# v0.9.5.7.4 Remove Legacy Shell Generator Fixtures

## Purpose

v0.9.5.7.4 removes the last compiled test generator that still treated shell commands and concrete CLI calls as successful Jenkins projection.

## Removed active behavior

`src/test/kotlin/org/flowlang/generators/JenkinsGenerator.kt` was part of the normal Gradle test source set. Despite its legacy label, it emitted active Jenkins syntax for `shell.run`, Docker, Helm, Argo CD, Kubernetes and database operations, and used `echo` as a fallback for unmapped work.

The file is deleted rather than retained as a compatibility facade. A compiled facade would remain a second projection implementation and would keep obsolete shell behavior available to tests and future callers.

## Archived negative fixture

Historical cases are represented by `conformance/negative-fixtures/legacy-jenkins-shell-projections.yaml`.

The fixture contains only structured capability identities and expected outcomes. It stores no shell command bodies and is explicitly classified as `ARCHIVED_NEGATIVE` and `compiled: false`.

Each case is executed through the real `JenkinsManifestGenerator`, `TargetMaterializationResolver`, `TargetRenderPolicy` and `JenkinsManifestRenderer` contracts:

- `shell.run` resolves to `BLOCKED` and `FAIL_FAST`.
- Docker, Helm, Argo CD, Kubernetes, database and notification actions resolve to `ADAPTER_REQUIRED` and `REVIEW_ONLY` until explicit target projection evidence exists.
- review-only cases emit `TargetProjectionReview`, never Jenkins pipeline syntax.
- no case carries `TargetStep.run` executable text.

## Test migration

`FlowSpecTests_part2.kt` no longer compiles or asserts the historical Jenkins shell generator. Its old expectations for `sh`, `kubectl`, Docker, Helm, Argo CD and `echo` output were removed.

`LegacyShellGeneratorFixtureTests` adds structural and behavioral gates that:

- reject a `JenkinsGenerator.kt` file in active Kotlin source sets;
- require the archived fixture to remain explicitly negative and non-compiled;
- verify every fixture case against canonical materialization and renderer readiness.

## Versioning boundary

This repair does not change the package version, public standard version or artifact schema versions. Compatibility and executable readiness remain separate until v0.9.5.7.5.
