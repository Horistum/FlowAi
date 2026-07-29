# Real-World Pipeline Representability Baseline

## Status

`EXECUTABLE_BASELINE`

Base reviewed from main commit `a3b8c13783a37ea7b6176eddc4760cae3149a983`; executable evidence is owned by PR #100 case packages and post-Core conformance checks.

This report is not A0.5 completion evidence and does not promote any adapter support class.

## Inventory

- immutable source records: 15;
- broad scenarios: 44;
- complete executable cases: 6;
- executed negative mutations: 8;
- strict document schemas: 9;
- post-Core checks: 8;
- focused regression tests for declared output continuity and strict corpus parsing.

## Accepted cases

| Case | Actual result | Key proof |
|---|---|---|
| C02 | SEMANTIC_ONLY | the authored diamond is detected as serialized by current Intent lowering |
| C06 | SUPPORTED_WITH_BINDING | named producer-to-consumer value continuity preserves artifact identity |
| A06 | SUPPORTED_WITH_BINDING | typed inputs, approval and approval output are preserved |
| A11 | UNSUPPORTED_DYNAMIC_CONSTRUCTION | value continuity is preserved while runtime matrix construction remains absent |
| N01 | INVALID_SOURCE_PIPELINE | a missing or forward producer reference blocks planning |
| N08 | SEMANTIC_ONLY | required parallel siblings are serialized and the loss remains explicit |

## Behavioral evidence

The harness uses the real Intent loader, capability validator, Intent-to-AST lowering, Flow validation and planner. Target expectations use compatibility, execution readiness, manifest generation where allowed and render policy.

Expected artifacts compare authored task identities, source-authored dependency relations, generated dependency relations, continuity channel names, forbidden sibling ordering and exact diagnostics. Conservative dependencies introduced by lowering cannot self-certify that the author declared a fan-in edge.

Every manifest, catalog, case, provenance record, expected plan, target assessment, evidence record and mutation is loaded through a strict duplicate-detecting boundary. Unknown fields fail instead of disappearing.

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

Thirty-eight catalogued scenarios do not yet have complete case packages.

The executable baseline proves the harness and exposes real current gaps. It does not prove general representability, target parity, workspace continuity, durable state continuity or production promotion semantics.
