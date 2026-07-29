# Real-World Corpus Methodology

## Purpose

The corpus measures whether Flow preserves externally authored automation intent across normalization, planning and target assessment. It is not a syntax-transpilation benchmark.

## Executed evaluation pipeline

Every accepted case is exercised through the production semantic path:

```text
immutable external source
-> source behavior inventory
-> reconstructed author intent
-> strict Intent YAML loader
-> IntentCapabilityValidator
-> IntentToAstPlanner
-> FlowValidator
-> FlowPlanner
-> semantic expectation comparison
-> target compatibility/readiness/materialization assessment
-> negative mutations
```

No stage uses the output it validates as its own expectation. Expected task identities, dependency relations, required features and diagnostics are committed independently in each case package.

## Source admission

An admitted source records repository identity, immutable commit revision, exact path, source class, license evidence and semantic reason for inclusion. Every executable case vendors a bounded attributed source fixture plus provenance and an SPDX notice. The fixture is evidence input, not a target template.

## Case lifecycle

```text
CATALOGUED
SOURCE_VERIFIED
INTENT_RECONSTRUCTED
FIXTURE_AUTHORED
PLAN_VALIDATED
TARGET_ASSESSED
MUTATION_VALIDATED
ACCEPTED
```

A case cannot be loaded as executable evidence below `MUTATION_VALIDATED`, and its committed evidence must be `ACCEPTED`.

## Complete case package

```text
case.yaml
source/workflow.yaml
source/provenance.yaml
source/LICENSE
source-observations.md
canonical.intent.yaml
expected/execution-plan.json
expected/target-assessments.yaml
mutations/<mutation>/mutation.yaml
mutations/<mutation>/intent.yaml
evidence/result.yaml
```

`case.yaml` owns identity, source linkage and invariants. Generated plan output never rewrites that authority.

## Intent reconstruction

Each observation file records observed behavior, reconstructed target-neutral intent, invariants and ambiguities. Ambiguity is an allowed result.

## Continuity classification

Ordering proves only that one node completes before another may run. Continuity is classified separately as value, artifact, workspace or state. Artifact remains a corpus observation and maps explicitly to an existing VALUE, WORKSPACE or STATE relation plus an adapter artifact contract.

## Result vocabulary

- `SUPPORTED`: universal meaning and selected target evidence are executable.
- `SUPPORTED_WITH_BINDING`: universal meaning is represented but a concrete adapter binding is required.
- `SEMANTIC_ONLY`: intent is understood but a required structure or selected target remains review-only.
- `BLOCKED_BY_TARGET_CAPABILITY`: target execution would violate a required invariant.
- `AMBIGUOUS_SOURCE_INTENT`: no single safe interpretation is established.
- `UNSUPPORTED_DYNAMIC_CONSTRUCTION`: runtime-created topology exceeds the current planning contract.
- `INVALID_SOURCE_PIPELINE`: a producer, relation or other required dependency is missing or contradictory.

## Plan comparison

Expected plans compare exact authored task source IDs, semantic capability, node kind, producer-to-consumer relations, relation kind, channel identity, forbidden sibling ordering, required structural features, exact diagnostics and expected representability outcome.

## Target assessment

For each target expectation the harness executes CompatibilityAnalyzer, ExecutionReadinessAnalyzer, TargetManifestGenerationPipeline where allowed and TargetRenderPolicy. Cases with known semantic loss are blocked before target materialization.

## Negative mutations

The initial baseline covers removed fan-in, removed branch, wrong artifact reference, ordering-only artifact substitution, removed approval output, removed matrix producer output, missing consumer result and serialized siblings with incomplete fan-in. A mutation passes only when exact diagnostics and outcome match.

## Core boundary

The corpus runs after frozen Core and adapter inventories. It cannot rewrite closure evidence or adapter certification counts.

## Current executable baseline

Six cases are executed: C02, C06, A06, A11, N01 and N08. Remaining catalog entries stay research hypotheses until they receive the same complete package and mutation evidence.
