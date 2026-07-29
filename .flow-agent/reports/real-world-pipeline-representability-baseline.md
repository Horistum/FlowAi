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
- schemas: 3;
- post-Core checks: 8.

## Accepted cases

| Case | Actual result | Key proof |
|---|---|---|
| C02 | SEMANTIC_ONLY | required diamond is detected as serialized |
| C06 | SUPPORTED_WITH_BINDING | named producer-to-consumer value continuity |
| A06 | SUPPORTED_WITH_BINDING | typed inputs, approval and approval output |
| A11 | UNSUPPORTED_DYNAMIC_CONSTRUCTION | value preserved, runtime matrix absent |
| N01 | INVALID_SOURCE_PIPELINE | missing producer blocks planning |
| N08 | BLOCKED_BY_TARGET_CAPABILITY | silent loss of parallelism is blocking |

## Behavioral evidence

The harness uses the real Intent loader, capability validator, Intent-to-AST lowering, Flow validation and planner. Target expectations use compatibility, execution readiness, manifest generation where allowed and render policy.

Expected artifacts compare authored task identities, dependency relations, continuity channel names, forbidden sibling ordering and exact diagnostics. They do not self-certify from target registry claims.

## Current architecture findings

1. VALUE continuity is representable through named references and verified producer-to-consumer relations.
2. Artifact identity can be observed through VALUE plus binding evidence without inventing a new Core dependency kind.
3. Typed global inputs and approval outputs are representable.
4. Explicit sibling parallelism is not representable by the current Standard Intent lowering.
5. Bounded runtime matrix expansion is not represented by the current ExecutionPlan.
6. Missing references require a corpus-level pre-planning integrity authority because accepting them would silently erase author meaning.

## A0.5 implications

A0.5 should build on C06 and A06 while extending behavioral evidence for WORKSPACE and STATE. It must also retain C02/N08 as ordering-only controls.

No target may be promoted to executable continuity support until producer identity, consumer identity, channel, lifetime and target materialization evidence all agree.

## Honest limitations

Thirty-eight catalogued scenarios do not yet have complete case packages.

The executable baseline proves the harness and exposes real current gaps. It does not prove general representability, target parity, workspace continuity, durable state continuity or production promotion semantics.
