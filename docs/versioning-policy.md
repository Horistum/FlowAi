# Flow Versioning Policy

Flow has separate public version axes with distinct purposes. They are intentionally independent, and every exported reference snapshot records the applicable versions together so the distinction is visible without archaeology across release documents.

| Axis | Current value | Changes when |
|---|---:|---|
| Implementation package | `0.9.5` | A new implementation release is published. |
| Public standard | `0.8.0` | Public semantics, schemas, snapshots and conformance advance together. |
| Intent contract | `2.0` | The serialized Intent contract changes incompatibly. |
| AST contract | `2.0` | The serialized AST contract changes incompatibly. |
| ExecutionPlan contract | `2.1` | The serialized execution-plan contract changes incompatibly. |
| TargetManifest contract | `3.0` | The serialized target-manifest contract changes incompatibly. |
| TargetRegistry contract | `3.1` | The serialized target-registry contract changes incompatibly. |

## Package version

The Gradle package identifies the implementation release. The current published package is `0.9.5`; the next planned package is `0.9.6`. Bounded work identifiers such as `0.9.6.2` do not independently publish a Gradle package.

## Public standard version

`FlowStandardVersions.FLOW_STANDARD_VERSION` identifies the public semantic standard and exported standard surface. Flow 0.9.5 advanced the standard from `0.7.6` to `0.8.0` because trigger semantics, target semantics and projection evidence changed publicly.

A contract-only migration may advance one serialized artifact version without changing unrelated semantic contracts or prematurely publishing a new package. Such a migration still requires schemas, migration documentation, exact snapshots and conformance evidence.

## Artifact contract versions

Artifact contracts are independently versioned serialized boundaries. They must not be represented by one misleading global number after their versions diverge.

Intent and AST remain at `2.0`, while ExecutionPlan advances independently to `2.1`:

- Intent 2.0 introduces top-level triggers and removes schedule-as-step.
- AST 2.0 introduces trigger nodes.
- ExecutionPlan 2.0 introduced the trigger-aware execution contract.
- ExecutionPlan 2.1 adds mandatory target-neutral control requirement scope so security evidence remains bound to the intent operation or plan node it protects.

The ExecutionPlan 2.1 migration is documented in `docs/SI_01_EXECUTION_PLAN_CONTROL_SCOPE_MIGRATION.md`.

Target contracts advance separately:

- TargetRegistry 3.0 replaced prefix-encoded payload parameter strings with universal typed binding templates.
- TargetRegistry 3.1 requires complete execution-topology evidence for isolation, lifetime, persistence and propagation.
- TargetManifest 3.0 preserves binding kind, source provenance, resolved compile-time values and symbolic runtime references.

The target migration is documented in `docs/V0_9_6_TYPED_BINDING_MIGRATION.md`.

Artifact versions are not cosmetic. A breaking shape or interpretation change requires a migration document, updated schema, exact snapshots and conformance evidence.

## Historical correction identifiers

Identifiers such as `0.9.5.7.9` describe historical bounded work inside the completed v0.9.5.x repair track. Identifiers such as `0.9.6.2` identify bounded work toward the next package line. Neither form is a substitute for the published Gradle package version.

## Projection coverage

Version promotion does not imply that every action-target pair is executable. Executable projection requires a concrete `NATIVE` rule, structured renderer payload evidence, valid typed bindings and behavioral conformance. Missing rules remain review-only or fail-fast. Expanding native payload coverage is tracked separately and must not be disguised as snapshot or version metadata work.

## Release requirements

A public release must align:

- `build.gradle.kts`
- `FlowStandardVersions`
- public schemas
- exact reference snapshots
- `REPORT.md`
- `CHANGELOG.md`
- `.flow-agent/release-state.yaml`
- `.flow-agent/roadmap.yaml`
- release documentation and report
- standard Flow CI evidence

Conformance gate identifiers remain stable historical contract names unless a separate compatibility migration explicitly replaces them.
