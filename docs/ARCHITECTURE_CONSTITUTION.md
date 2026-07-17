# Flow Architecture Constitution

## Purpose

Flow is an AI-first standardization layer for universal automation intent.

Flow converts Human/AI intent into canonical semantic meaning, explicit effects and policy requirements, a portable execution plan, materialization evidence, abstract topology requirements, adapter capability reports and conformance artifacts.

## Core pipeline

Every accepted feature must strengthen at least one part of this pipeline:

1. Human/AI intent
2. Canonical Intent Model
3. Semantic effects, policy and control validation
4. Universal Execution Plan
5. Materialization negotiation
6. Abstract execution topology satisfaction
7. Adapter binding
8. Concrete target artifact boundary
9. Public artifacts and conformance

## Non-goals

Flow is not an SDK.
Flow is not a runtime executor.
Flow is not a plugin framework.
Flow is not another target-specific workflow language.
Flow is not a template engine.
Flow does not execute workflows.
Flow does not own adapter implementation lifecycle.

## Allowed standard surface

Flow may define:

- canonical semantic contracts
- JSON schemas
- diagnostic codes
- semantic effect and state-transition models
- dependency and continuity requirements
- validation rules
- safety and control policies
- execution plan models
- materialization evidence models
- abstract execution topology models
- conformance profiles
- reference and negative conformance corpus entries

## Forbidden standard surface

Flow must not define:

- adapter base classes as public standard contracts
- plugin lifecycle hooks
- runtime task execution APIs
- target-specific implementation SDKs
- concrete implementation defaults as universal meaning
- silent semantic fallbacks
- concrete platform topology assumptions in Core

## Target-neutral Core rule

Flow Core may validate generic materialization and topology evidence, but it must not enumerate concrete payload kinds or treat any concrete platform, adapter or implementation tool as part of semantic architecture.

Adding a new target or implementation must not require changing canonical intent, AST, semantic effects, dependency meaning, control requirements, ExecutionPlan, materialization semantics or public manifest structure. Concrete adapters may understand target syntax only at the edge serialization boundary and must fail closed when evidence is incomplete or unrecognized.

## Adapter ownership rule

Flow Core may define immutable target-neutral provider, topology and registry contracts for binding and serialization. It must not discover concrete implementations, manage their lifecycle or derive universal meaning from their limitations.

Concrete generators, renderers, payload identifiers, binding syntax and expression translation belong to adapter-owned packages and distribution composition roots. Adapter inability must remain explicit as unsupported, adapter-required, review-only or fail-fast evidence.

An adapter declaration is not implementation evidence by itself. Native or executable claims require agreement between canonical capability requirements, effect preservation, control requirements, dependency continuity, topology evidence, implementation ownership and behavioral tests.

## Continuity and executability rule

Action-level implementation coverage is not scenario-level executability.

A scenario is executable only when the selected adapter proves every required ordering, data, output, artifact, mutable-state, durable-state, secret, environment and human-decision dependency. Unknown or contradictory topology properties fail closed.

## Roadmap separation rule

The project roadmap is split into three authorities:

- Core roadmap: universal semantic meaning, effects, dependencies, policy, controls, materialization and abstract topology requirements.
- Adapter roadmap: concrete bindings, topology evidence and artifact generation.
- Conformance roadmap: cross-domain semantic adequacy, abstract topology behavior, equivalence and adapter certification.

Core roadmap items must declare a universal invariant, forbidden scope and completion evidence. Core roadmap names, purposes and required outcomes must not name concrete platforms or implementation tools. Adapter or conformance work may not redefine Core meaning.

## Change rule

Every larger change must pass an Architecture Decision Gate before implementation. The decision must explain which universal invariant is strengthened, which roadmap stream owns the change and which drift risks are explicitly rejected.

## Report budget rule

A new public report may be added only when it introduces a stable public contract that cannot be represented by extending an existing report. The default decision is to extend an existing report first. Registry-consistency bookkeeping must stay below one third of the active release profile so the standard remains behavior-led instead of ceremony-led.

## Negative conformance rule

Every behavioral release must include at least one falsifying negative conformance case or explicitly state why the release is documentation-only.

## Drift Score rule

Every larger feature proposal must be scored using the Drift Score model. Existing baseline artifacts are context only and must not create a positive score floor. A feature with any negative delta score is rejected unless a specific Architecture Decision records an exception and adds a falsifying conformance fixture.
