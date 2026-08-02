# Real-World Pipeline Representability Baseline

## Status

`EXECUTABLE_BASELINE`

Base reviewed from main commit `a3b8c13783a37ea7b6176eddc4760cae3149a983`; executable evidence is owned by PR #100 case packages and post-Core conformance checks.

This report is not A0.5 completion evidence and does not promote any adapter support class.

## Inventory

- immutable source records: 15;
- production workflow sources: 3;
- official vendor example sources: 8;
- official semantic documentation references: 4;
- broad hypothesis scenarios: 44;
- accepted scenario index entries: 6;
- complete executable cases: 6;
- executable cases using a production workflow as their primary fixture: 1;
- executable cases using an official example as their primary fixture: 5;
- executable cases using documentation as their primary fixture: 0;
- executed negative mutations: 8;
- strict document schemas: 9;
- post-Core checks: 8;
- focused regression tests for declared output continuity, strict corpus parsing, source composition and conformance phase separation.

These source-class counts are declared in the corpus manifest and verified against `sources.yaml` and each case package's primary `sourceRef`. They are not prose-only estimates.

The broad scenario catalog remains research input. `accepted-scenarios.yaml` is a separate machine-checked authority whose IDs and case paths must match the executable packages exactly. This permits a negative case such as N01 to cite both its semantic reference and the independent valid workflow used as a mutation fixture without rewriting the original research record.

## Source character and representativeness

The word "real-world" means externally authored and independently sourced; it does not mean that every fixture is a mature production pipeline.

| Source class | Count | Role |
|---|---:|---|
| Production workflow | 3 | Argo CD release, Helm release and Prometheus CI configurations with repository-specific delivery behavior |
| Official example | 8 | GitHub Actions starters, Buildkite examples and Argo Workflows examples designed to demonstrate bounded platform semantics |
| Official semantic reference | 4 | Tekton, GitLab, CircleCI and Azure DevOps documentation used to define or screen behavior |

Only A11 currently uses a production workflow (`prometheus-ci`) as its primary executable fixture. C02, C06, A06, N01 and N08 use official examples. Those examples are valuable independent probes because Flow's authors did not design them, but their isolated teaching structure is closer to a focused fixture than to a historically accumulated production configuration.

The baseline therefore proves that the harness can preserve or reject specific externally authored semantics and can expose concrete model gaps. It does not yet prove resilience against the combined complexity, policy layering and historical irregularities of large production pipelines.

## Accepted cases

| Case | Actual result | Primary source class | Key proof |
|---|---|---|---|
| C02 | SEMANTIC_ONLY | official example | the authored diamond is detected as serialized by current Intent lowering |
| C06 | SUPPORTED_WITH_BINDING | official example | named producer-to-consumer value continuity preserves artifact identity |
| A06 | SUPPORTED_WITH_BINDING | official example | typed inputs, approval and approval output are preserved |
| A11 | UNSUPPORTED_DYNAMIC_CONSTRUCTION | production workflow | value continuity is preserved while runtime matrix construction remains absent |
| N01 | INVALID_SOURCE_PIPELINE | official example mutation fixture | a missing or forward producer reference blocks planning |
| N08 | SEMANTIC_ONLY | official example | required parallel siblings are serialized and the loss remains explicit |

## Behavioral evidence

The harness uses the real Intent loader, capability validator, Intent-to-AST lowering, Flow validation and planner. Target expectations use compatibility, execution readiness, manifest generation where allowed and render policy.

Expected artifacts compare authored task identities, source-authored dependency relations, generated dependency relations, continuity channel names, forbidden sibling ordering and exact diagnostics. Conservative dependencies introduced by lowering cannot self-certify that the author declared a fan-in edge.

Every manifest, catalog, case, provenance record, expected plan, target assessment, evidence record and mutation is loaded through a strict duplicate-detecting boundary. Unknown fields fail instead of disappearing.

## Frozen closure boundary

Real-world checks are intentionally absent from both the frozen Core pre-closure inventory and the adapter certification inventory.

`ConformanceRunner` now constructs four explicit collections:

1. frozen Core pre-closure checks;
2. the semantic closure result derived only from that immutable pre-closure collection;
3. adapter-stream certification;
4. real-world behavioral evidence.

`SemanticClosureChecks` receives the dedicated pre-closure list, not a mutable aggregate whose meaning depends on the relative position of later append statements. A regression test also verifies that every `real-world-corpus.*` check executes after adapter certification and is absent from both committed inventories.

The closure authority remains strict. It is not filtered by prefixes, because such filtering could hide a newly introduced, non-inventoried Core check.

## Core consistency correction exposed by the corpus

The accepted continuity cases exposed an existing contract inconsistency rather than requiring a new capability:

- Canonical Intent already declared `produces` outputs;
- Intent lowering already retained them as `declaredOutputs`;
- Flow planning already registered them and derived VALUE continuity;
- Flow validation did not place them in lexical scope, so valid consumers failed with `UNRESOLVED_REFERENCE`.

PR #100 aligns FlowValidator with the existing Intent and planner contract. Declared outputs become referenceable only after the producing statement validates, duplicate output names remain blocking, and no new Core dependency kind or target-specific meaning is introduced.

## Current architecture findings

1. VALUE continuity is representable through named references and verified producer-to-consumer relations.
2. Artifact identity can be observed through VALUE plus binding evidence without inventing a new Core dependency kind.
3. Typed global inputs and approval outputs are representable.
4. Explicit sibling parallelism is not representable by the current Standard Intent lowering.
5. Bounded runtime matrix expansion is not represented by the current ExecutionPlan.
6. Missing or forward references require pre-planning integrity checks because accepting them would silently erase author meaning.
7. Generated ordering cannot be used as evidence that equivalent ordering was authored.

## A0.5 implications

A0.5 should build on C06 and A06 while extending behavioral evidence for WORKSPACE and STATE. It must also retain C02 and N08 as ordering-only controls.

No target may be promoted to executable continuity support until producer identity, consumer identity, channel, lifetime and target materialization evidence all agree.

## Honest limitations

Thirty-eight catalogued scenarios do not yet have complete case packages. Five of the six executable cases use official teaching examples rather than mature production pipelines.

The executable baseline proves the harness and exposes real current gaps. It does not prove general representability, production-scale configuration resilience, target parity, workspace continuity, durable state continuity or production promotion semantics.
