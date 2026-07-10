# v0.9.5.7.1 Connect Materialization Pipeline

## Purpose

v0.9.5.7.1 starts the repair subtrack created after the target registry honesty review.

The problem being fixed is that the notes, semantic, materialization and projection models existed, but target manifest generation still resolved task materialization through a local hardcoded switch. That made the new models mostly decorative at the point where generated artifacts are built.

This step connects real `ExecutionPlan` task nodes to the contract stack before a `TargetStep` receives its `TargetMaterialization`.

## Added

- `TargetMaterializationResolver`
- `TargetMaterializationResolution`
- `FlowConnectedMaterializationPipelineTests`
- `.flow-agent/roadmap-v0.9.5.7-repair-track.yaml`

## Changed

`TaskNode.toTargetStep()` now resolves materialization through `TargetMaterializationResolver`.

Each action step now carries metadata for:

- semantic graph id
- semantic node id
- materialization negotiation id
- projection plan id
- projection artifact id
- projection artifact kind

## Status behavior

The resolver is conservative:

- `standard.execute` and `standard.rollback` can become `NOTES_PROJECTED` through generated capability notes and explicit materialization evidence.
- ordinary target actions such as `git.checkout`, `docker.build`, `kubernetes.deploy` and `notify.send` remain `ADAPTER_REQUIRED` unless a projection adapter is declared.
- raw runtime action intent is preserved for review but remains `BLOCKED` at the Flow Core projection boundary.

## Non-goal

This step does not claim broad executable target readiness. It only connects the existing contract models to real manifest generation so later steps can make readiness and renderer behavior honest.

## Follow-up repair track

The full repair track is recorded in `.flow-agent/roadmap-v0.9.5.7-repair-track.yaml` and continues through:

- v0.9.5.7.2 Governance Scanner Honesty
- v0.9.5.7.3 Renderer Failure Semantics Unification
- v0.9.5.7.4 Remove Legacy Shell Generator Fixtures
- v0.9.5.7.5 Compatibility and Readiness Honesty
- v0.9.5.7.6 Release Metadata Reconciliation
- v0.9.5.7.7 Policy-Driven Safety Prelude
- v0.9.5.7.8 Target Expression and Unknown Target Safety
- v0.9.5.7.9 Reference Scenario and Snapshot Honesty Reset
