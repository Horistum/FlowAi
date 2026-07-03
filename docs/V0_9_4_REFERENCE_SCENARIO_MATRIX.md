# v0.9.4 Reference Scenario Matrix

## Purpose

v0.9.4 adds a deeper reference scenario matrix before end-to-end readiness work.

The reference scenario matrix is target-neutral. It records realistic automation scenarios, universal semantic capabilities, risks, safety requirements and portability expectations. It does not use Jenkins, GitHub Actions or Tekton as the source of scenario meaning.

Target-specific projection expectations are kept separately in `ReferenceAdapterProjectionMatrix`.

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
- target-neutral semantic expectation
- universal required capabilities
- portability class
- risks
- safety requirements
- optional negative-coverage diagnostics

Universal capabilities use names such as:

```text
source.checkout
command.run
deployment.apply
data.write
notification.send
approval.require
rollback.perform
resource.delete
secret.consume
secret.rotate
```

Implementation-specific module names such as `git.checkout`, `kubernetes.deploy`, `notify.send`, `database.upsert` or `shell.run` may appear inside Flow source examples, but they are not the semantic source of truth for the matrix.

## Adapter projection matrix

`ReferenceAdapterProjectionMatrix` is the place where Jenkins, GitHub Actions and Tekton expectations are declared.

This keeps the architecture split clear:

```text
ReferenceScenarioMatrix = target-neutral automation meaning
ReferenceAdapterProjectionMatrix = known target adapter projection expectations
```

## Validation path

Positive core scenarios are tested through:

```text
Flow source
  -> parser
  -> Flow validator
  -> safety validator
  -> planner
```

Adapter projection checks are tested separately through:

```text
Flow source
  -> planner
  -> target compatibility analysis
  -> target manifest generation
  -> TargetCapabilityDegradationAnalyzer
```

Negative scenarios are parsed and then required to fail at a core validation gate with explicit diagnostic codes. The exact gate may be the Flow validator or the safety boundary, depending on where the violation is detected first.

## Why this matters

End-to-end readiness should not be claimed from one friendly example or from a matrix that is secretly shaped by a few orchestrators. The matrix creates a broader target-neutral reference set before v0.9.5 by covering normal workflows, risky workflows and known negative behavior.

This keeps unsupported or unsafe cases visible without turning Flow Core into a Jenkins, GitHub Actions or Tekton abstraction layer.

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
