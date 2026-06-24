# Flow Architecture Constitution

## Purpose

Flow is an AI-first standardization layer for IT and DevOps automation.

Flow converts Human/AI intent into a validated standard model, an explicit decision and safety record, a portable execution plan, target capability reports, and public conformance artifacts.

## Core pipeline

Every accepted feature must strengthen at least one part of this pipeline:

1. Human/AI intent
2. Standard Intent Model
3. Decision, safety and capability validation
4. Execution Plan
5. Capability negotiation
6. Execution readiness and target selection
7. Target manifest boundary
8. Public artifacts and conformance

## Non-goals

Flow is not an SDK.
Flow is not a runtime executor.
Flow is not a plugin framework.
Flow is not another workflow language.
Flow is not a template engine.
Flow is not a target-specific DSL.
Flow does not execute workflows.
Flow does not own adapter implementation lifecycle.

## Allowed standard surface

Flow may define:

- public contracts
- JSON schemas
- diagnostic codes
- validation rules
- safety policies
- execution plan models
- target capability models
- conformance profiles
- reference and negative conformance corpus entries

## Forbidden standard surface

Flow must not define:

- adapter base classes
- plugin lifecycle hooks
- runtime task execution APIs
- target-specific implementation SDKs
- template ownership contracts
- silent semantic fallbacks

## Change rule

Every larger change must pass an Architecture Decision Gate before implementation. The decision must explain which part of the core pipeline is strengthened and which drift risks are explicitly rejected.

## Report budget rule

A new public report may be added only when it introduces a stable public contract that cannot be represented by extending an existing report. The default decision is to extend an existing report first. Registry-consistency bookkeeping must stay below one third of the active release profile so the standard remains behavior-led instead of ceremony-led.

## Negative conformance rule

Every release must include at least one negative conformance test or explicitly state why the release is documentation-only.

## Drift Score rule

Every larger feature proposal must be scored using the Drift Score model. Existing baseline artifacts are reported as context only and must not create a positive score floor. A feature with any negative delta score is rejected unless a specific Architecture Decision records an exception and adds a falsifying conformance fixture.
