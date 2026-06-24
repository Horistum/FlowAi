# v0.7.4 Architecture Delta Analyzer

Flow v0.7.4 adds a real delta analyzer for the public standard model. The goal is to measure how the standard changed between two release boundaries, not whether the current repository is internally consistent.

## Purpose

The analyzer protects the main Flow axis:

- Flow is an AI-first standardization layer for IT and DevOps automation intent.
- Flow validates intent, safety boundaries, target capability compatibility and evidence.
- Flow does not add a runtime executor, SDK-first architecture, plugin lifecycle or target-specific public DSL.

Previous governance checks could prove that generated projections agreed with the live `StandardModel`. That is necessary, but it is not enough. A self-consistent model can still drift. Delta analysis compares a frozen previous model snapshot with the active model and asks what actually changed.

## Inputs

The analyzer uses:

- `standard/architecture/standard-model-baseline-v0.7.3.yaml` as the frozen previous release snapshot.
- `StandardModelSnapshot.current()` as the active model projection.

The baseline is data, not code. That keeps the previous release boundary inspectable and prevents the analyzer from comparing the model to itself, because that trick already caused enough trouble.

## Invariants

The v0.7.4 gate requires:

- no new `REGISTRY_CONSISTENCY` gates,
- no silent gate-kind reclassification,
- no silent removal of substance checks,
- every added substance check to carry a `negativeFixture` or `externalAnchor`,
- stable public artifact growth not to outrun evidence-backed check growth,
- the only v0.7.4 added check to be `v0.7.4.architecture-delta-analyzer`.

## Non-goals

The analyzer does not execute workflows, introduce a runtime, define an SDK API, create a plugin system or make any target-specific public DSL part of the standard.

## Maintenance rule

For every future minor version, freeze the previous `StandardModel` snapshot before modifying the active model. Then run the delta analyzer against that baseline. If the delta fails, fix the design instead of bending the check.
