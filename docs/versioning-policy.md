# Flow Versioning Policy

Flow has separate public version axes with distinct purposes. They are intentionally independent, and every exported reference snapshot records the applicable versions together so the distinction is visible without archaeology across release documents.

| Axis | Current value | Changes when |
|---|---:|---|
| Implementation package | `0.9.5` | A new implementation release is published. |
| Public standard | `0.8.0` | Public semantics, schemas, snapshots and conformance advance together. |
| Intent contract | `2.0` | The serialized Intent contract changes incompatibly. |
| AST contract | `2.2` | The serialized AST contract changes incompatibly. |
| ExecutionPlan contract | `2.4` | The serialized execution-plan contract changes incompatibly. |
| WorkflowExecutionPlanSet contract | `1.1` | The public multi-workflow envelope, ownership or trigger-routing shape changes incompatibly. |
| ExecutionPlan lowering evidence | `2.1` | The artifact-derived proof needed to authenticate preserved intent meaning changes. |
| TargetManifest contract | `3.0` | The serialized target-manifest contract changes incompatibly. |
| TargetRegistry contract | `3.2` | The serialized target-registry contract changes incompatibly. |

## Package version

The Gradle package identifies the implementation release. The current published package is `0.9.5`; the next planned package is `0.9.6`. Bounded work identifiers such as `0.9.6.2` do not independently publish a Gradle package.

## Public standard version

`FlowStandardVersions.FLOW_STANDARD_VERSION` identifies the public semantic standard and exported standard surface. Flow 0.9.5 advanced the standard from `0.7.6` to `0.8.0` because trigger semantics, target semantics and projection evidence changed publicly.

A contract-only migration may advance only the serialized artifact versions whose public shape changes without prematurely publishing a new package or changing unrelated contracts. Such a migration still requires schemas, migration documentation, exact snapshots and conformance evidence.

## Artifact contract versions

Artifact contracts are independently versioned serialized boundaries. They must not be represented by one misleading global number after their versions diverge.

Intent remains at `2.0`; AST remains at `2.2`; ExecutionPlan remains at `2.4`; WorkflowExecutionPlanSet is at `1.1`. The earlier control-scope and explicit node-kind migrations remain part of the current contract history:

- Intent 2.0 introduces top-level triggers and removes schedule-as-step. SI-01 does not add an authored Intent field.
- AST 2.0 introduced trigger nodes.
- AST 2.1 adds mandatory target-neutral control requirement scope.
- AST 2.2 carries the enriched target-neutral `SemanticEffect` recovery facet produced from already-authored BACKUP/RESTORE parameters.
- ExecutionPlan 2.0 introduced the trigger-aware execution contract.
- ExecutionPlan 2.1 preserves canonical control scope through planning and adds plan-node scope for planning-owned obligations.
- ExecutionPlan lowering evidence 2.1 additionally certifies each authored workflow-to-step membership used to authenticate `OPERATION` scope during materialization.
- ExecutionPlan 2.2 makes public node-kind semantics explicit: task specialization is derived from canonical capability rather than implementation module/action labels, adapter capability strings or resource-name substrings, and the JSON Schema accepts only the closed lowercase canonical kind vocabulary.
- ExecutionPlan 2.3 adds the `STATE_RECOVERY` domain and typed recovery relationship semantics for BACKUP/RESTORE, including protected-state identity, authored backup destination, authored recovery-point identity and narrow backup retention. It deliberately leaves consistency guarantees, recoverability and restore create-versus-replace unresolved where the authored contract does not prove them.
- ExecutionPlan 2.4 adds explicit `stateLifetime` to every `STATE` dependency relation. `WORKFLOW` means mutable state continuity only within the current workflow execution; `DURABLE` additionally requires persistence evidence. Non-state relations cannot carry this field, and a state relation without it is invalid.
- WorkflowExecutionPlanSet 1.0 introduces the non-flattening public envelope for independent workflow views and exact trigger routes. Existing ExecutionPlan 2.4 remains the exact single-workflow compatibility contract.
- WorkflowExecutionPlanSet 1.1 adds first-class workflow failure policy, handler-region identity and handler-entry availability to each workflow view. The synthetic tail `TryPlanNode` remains a checked compatibility mirror, not semantic authority.

The AST and ExecutionPlan 2.1 migration is documented in `docs/SI_01_EXECUTION_PLAN_CONTROL_SCOPE_MIGRATION.md`. The ExecutionPlan 2.2 semantic migration is documented in `docs/SI_04_EXPLICIT_CANONICAL_EXECUTION_PLAN_SEMANTICS_MIGRATION.md`. The AST 2.2 and ExecutionPlan 2.3 recovery-effect migration is documented in `docs/SI_05_OPERATIONAL_EFFECT_MODEL_RE_EVALUATION_MIGRATION.md`. The ExecutionPlan 2.4 state-lifetime and module-schema 1.3 migration is documented in `docs/SI_08_TYPED_POLICY_STATE_LIFETIME_MIGRATION.md`. The WorkflowExecutionPlanSet 1.0 migration is documented in `docs/AR_02C_WORKFLOW_EXECUTION_PLAN_SET_MIGRATION.md`; the 1.1 failure-policy migration is documented in `docs/AR_02D_WORKFLOW_FAILURE_POLICY_MIGRATION.md`.

Target contracts advance separately:

- TargetRegistry 3.0 replaced prefix-encoded payload parameter strings with universal typed binding templates.
- TargetRegistry 3.1 requires complete execution-topology evidence for isolation, lifetime, persistence and propagation.
- TargetRegistry 3.2 aligns the published interchange shape with the production loader for expression profiles, defaults and the closed authored support vocabulary; it also rejects duplicate authored expression features rather than collapsing them into a set.
- TargetManifest 3.0 preserves binding kind, source provenance, resolved compile-time values and symbolic runtime references.

The typed-binding migration is documented in `docs/V0_9_6_TYPED_BINDING_MIGRATION.md`. The TargetRegistry 3.1 to 3.2 acceptance migration is documented in `docs/SI_07_PUBLIC_SCHEMA_ACCEPTANCE_ALIGNMENT_MIGRATION.md`.

Artifact versions are not cosmetic. A breaking shape or interpretation change requires a migration document, updated schema, exact snapshots and conformance evidence.

## Schema authority

Published JSON Schemas are independently classified in `PublishedSchemaContracts`. A `SYNTACTIC_INTERCHANGE` schema constrains serialization but does not replace its named production loader or semantic validator. `PRODUCTION_VALIDITY` is reserved for a schema with explicit bidirectional acceptance proof. The classification prevents a schema-only conformance pass from being presented as stronger production-validity evidence than the implementation actually proves.

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
