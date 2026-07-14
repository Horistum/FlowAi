# Flow Versioning Policy

Flow has three public version axes with distinct purposes. They are intentionally separate, but every exported reference snapshot records them together so the distinction is visible without reading several release documents.

| Axis | Current value | Changes when |
|---|---:|---|
| Implementation package | `0.9.5` | A new implementation release is published. |
| Public standard | `0.8.0` | Public semantics, schemas, snapshots and conformance advance together. |
| Artifact contracts | `2.0` | A serialized Intent, AST, ExecutionPlan, TargetManifest or TargetRegistry contract changes incompatibly. |

## Package version

The Gradle package identifies the implementation release. The current package is `0.9.5`; the next planned package is `0.9.6`.

## Public standard version

`FlowStandardVersions.FLOW_STANDARD_VERSION` identifies the public semantic standard and exported standard surface. Flow 0.9.5 advanced the standard from `0.7.6` to `0.8.0` because trigger semantics, target semantics and projection evidence changed publicly.

## Artifact contract versions

Public artifact contract versions advance to `2.0` in package 0.9.5. Intent, AST, ExecutionPlan, TargetManifest and TargetRegistry are independently versioned serialized contracts.

- Intent 2.0 introduces top-level triggers and removes schedule-as-step.
- AST 2.0 introduces trigger nodes.
- ExecutionPlan 2.0 preserves trigger requirements.
- TargetManifest 2.0 removes command text and adds structured renderer payloads plus triggers.
- TargetRegistry 2.0 adds declarative projection rules.

Artifact versions are not cosmetic. A breaking shape or interpretation change requires a migration document, updated schema, exact snapshots and conformance evidence.

## Historical correction identifiers

Identifiers such as `0.9.5.7.9` describe historical bounded work inside the completed v0.9.5.x repair track. They are not package, standard or artifact versions and are not used as future release numbers after package 0.9.5 is promoted.

## Projection coverage

Version promotion does not imply that every action-target pair is executable. Executable projection requires a concrete `NATIVE` rule, structured renderer payload evidence and behavioral conformance. Missing rules remain review-only or fail-fast. Expanding native payload coverage is tracked separately under `0.9.7 Projection Rule Coverage` and must not be disguised as snapshot or version metadata work.

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
