# v0.9.4 Reference Scenario Matrix

## Purpose

v0.9.4 adds a deeper reference scenario matrix before end-to-end readiness work.

The matrix records realistic automation scenarios, expected capabilities, risks, safety requirements and target expectations. It is intentionally declarative: it does not execute anything, does not introduce a runtime, and does not add target-specific public Flow syntax.

## Scenario coverage

The matrix covers:

- build, test and deploy
- API synchronization
- database migration
- rollback workflow
- approved cleanup
- secret rotation
- operational notification
- negative cleanup without approval

## What each scenario declares

Each `ReferenceScenario` declares:

- stable scenario id
- human-readable name
- scenario kind
- Flow source
- expected capabilities
- risks
- safety requirements
- target expectations for Jenkins, GitHub Actions and Tekton
- optional negative-coverage diagnostics

## Validation path

Positive scenarios are tested through:

```text
Flow source
  -> parser
  -> Flow validator
  -> safety validator
  -> planner
  -> target compatibility analysis
  -> target manifest generation
  -> TargetManifestContractValidator
  -> TargetCapabilityDegradationAnalyzer
```

Negative scenarios are still parsed and structurally validated. They are then required to fail at the safety boundary with explicit diagnostic codes.

## Why this matters

End-to-end readiness should not be claimed from one friendly example. The matrix creates a broader reference set before v0.9.5 by covering normal workflows, risky workflows and known negative behavior.

This keeps unsupported or unsafe cases visible instead of hiding them as skipped examples.

## Boundary

This release does not add:

- runtime execution
- SDK API
- plugin lifecycle
- target-specific public DSL
- renderer expansion
- Flow syntax expansion
- public standard version bump
- artifact schema version bump

## Versioning

Package version: `0.9.4`

Active public standard version: `0.7.6`

Artifact versions remain unchanged:

- Intent: `1.0`
- AST: `1.0`
- ExecutionPlan: `1.1`
- TargetManifest: `1.0`
- TargetRegistry: `1.0`
