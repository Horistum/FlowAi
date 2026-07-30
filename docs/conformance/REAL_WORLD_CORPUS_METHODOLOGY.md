# Real-World Corpus Methodology

## Purpose

The corpus measures whether Flow preserves externally authored automation intent across normalization, planning and target assessment. It is not a syntax-transpilation benchmark.

"Real-world" means externally authored and independently sourced. It does not imply that every source is a mature production pipeline.

## Catalog authority separation

`scenarios.yaml` remains the broad research catalog. Its entries are hypotheses and coverage planning records.

`accepted-scenarios.yaml` is a separate executable authority. Every entry must:

- exist in the broad catalog with the same category and admission class;
- have lifecycle `ACCEPTED` and claim `accepted-evidence`;
- identify an exact case package;
- cite all immutable sources used by the accepted evidence;
- agree with the case package's actual expected outcome.

This separation prevents later evidence from silently rewriting the historical research hypothesis. It also permits a negative case to cite both a semantic reference and an independent valid workflow used as the mutation fixture.

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
-> authored-semantics comparison
-> generated-plan comparison
-> target compatibility/readiness/materialization assessment
-> negative mutations
```

No stage uses the output it validates as its own expectation. Expected task identities, dependency relations, required features and diagnostics are committed independently in each case package.

## Strict corpus document boundary

Executable evidence is loaded through a dedicated strict YAML/JSON boundary. Duplicate keys, unknown properties, numeric enum coercion and null-to-primitive coercion fail before evidence can enter the runner.

Nine schemas cover:

- corpus manifest;
- source catalog;
- scenario catalogs;
- case definition;
- immutable provenance;
- expected plan semantics;
- target assessments;
- accepted evidence;
- negative mutations.

The loader additionally verifies repository-bounded paths, immutable revisions, SPDX agreement, catalog counts, source references, accepted-index identity and case-path parity, unique task and mutation identities, continuity channel names and evidence-to-expectation agreement.

## Source admission and source classes

An admitted source records repository identity, immutable commit revision, exact path, source class, license evidence and semantic reason for inclusion. Every executable case vendors a bounded attributed source fixture plus provenance and an SPDX notice. The fixture is evidence input, not a target template.

The corpus recognizes three source classes:

- `production-workflow`: repository-specific automation used by the source project for its own delivery or verification;
- `official-example`: an official vendor or project example designed to demonstrate bounded platform semantics;
- `official-semantic-reference`: official documentation or a semantic reference used to define or screen behavior.

The manifest declares counts for all three classes and for the primary source class of executable cases. Post-Core integrity checks compare those declarations with `sources.yaml` and each case package's primary `sourceRef`.

Official examples are valuable independent probes, but they are intentionally narrow and often didactic. They must not be described as equivalent to production configurations with accumulated policy, compatibility workarounds and historical complexity.

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

A case cannot be loaded as executable evidence below `MUTATION_VALIDATED`, its accepted-index entry must be `ACCEPTED`, and its committed evidence must be `ACCEPTED`.

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

`case.yaml` owns identity, primary source linkage and invariants. The accepted index may cite additional independent supporting sources. Generated plan output never rewrites either authority.

## Intent reconstruction

Each observation file records observed behavior, reconstructed target-neutral intent, invariants and ambiguities. Ambiguity is an allowed result.

## Continuity classification

Ordering proves only that one node completes before another may run. Continuity is classified separately as value, artifact, workspace or state. Artifact remains a corpus observation and maps explicitly to an existing VALUE, WORKSPACE or STATE relation plus an adapter artifact contract.

Declared Intent outputs are existing canonical evidence. They become lexical references only after their producer validates; a consumer cannot refer to a future, missing or self-produced output.

## Authored versus generated dependency evidence

The runner checks source-authored `requires` relations independently from generated ExecutionPlan edges. This separation is mandatory because conservative lowering may add sequential dependencies. Such generated ordering cannot prove that the author declared fan-in or intended serialization.

The generated plan is then checked separately for exact task identities, dependency kind, channel identity and forbidden ordering reachability.

## Result vocabulary

- `SUPPORTED`: universal meaning and selected target evidence are executable.
- `SUPPORTED_WITH_BINDING`: universal meaning is represented but a concrete adapter binding is required.
- `SEMANTIC_ONLY`: intent is understood but a required structure or selected target remains review-only.
- `BLOCKED_BY_TARGET_CAPABILITY`: target execution would violate a required invariant.
- `AMBIGUOUS_SOURCE_INTENT`: no single safe interpretation is established.
- `UNSUPPORTED_DYNAMIC_CONSTRUCTION`: runtime-created topology exceeds the current planning contract.
- `INVALID_SOURCE_PIPELINE`: a producer, relation or other required dependency is missing or contradictory.

Semantic loss is not mislabeled as a target limitation. For example, silent sibling serialization is `SEMANTIC_ONLY`; a target-specific result is assessed only after universal semantics are intact.

## Plan comparison

Expected plans compare exact authored task source IDs, semantic capability, node kind, source-authored relations, generated producer-to-consumer relations, relation kind, channel identity, forbidden sibling ordering, required structural features, exact diagnostics and expected representability outcome.

## Target assessment

For each target expectation the harness executes CompatibilityAnalyzer, ExecutionReadinessAnalyzer, TargetManifestGenerationPipeline where allowed and TargetRenderPolicy. Cases with known semantic loss are blocked before target materialization.

## Negative mutations

The initial baseline covers removed fan-in, removed branch, wrong artifact reference, ordering-only artifact substitution, removed approval output, removed matrix producer output, missing consumer result and serialized siblings with incomplete fan-in. A mutation passes only when exact diagnostics and outcome match.

## Conformance phase ownership

The runner constructs four explicit phases:

1. frozen Core pre-closure evidence;
2. semantic closure derived only from that pre-closure collection;
3. adapter-stream certification from its separate inventory;
4. real-world behavioral evidence.

The semantic closure authority receives the dedicated pre-closure collection directly. It does not receive a mutable aggregate whose contents depend on the position of later append statements.

Real-world check IDs must remain absent from both `standard/conformance/pre-closure-check-inventory.yaml` and `adapters/conformance/check-inventory.yaml`. A regression test verifies inventory exclusion and final execution order.

The closure authority is deliberately not filtered by known prefixes. Prefix filtering could hide an accidentally introduced Core check instead of failing on the missing inventory declaration.

## Core boundary

The corpus runs after frozen Core and adapter inventories. It cannot rewrite closure evidence or adapter certification counts.

The FlowValidator correction in this PR aligns validation with the existing `produces` and `declaredOutputs` contract already retained by lowering and planning. It adds no capability, dependency kind, target behavior or public semantic invention.

## Current executable baseline

Six cases are executed: C02, C06, A06, A11, N01 and N08. A11 is the only case whose primary fixture is currently a production workflow. The other five use official examples. Remaining catalog entries stay research hypotheses until they receive the same complete package and mutation evidence.
