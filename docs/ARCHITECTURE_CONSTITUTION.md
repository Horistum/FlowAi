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

## Target-neutral Core rule

Flow Core may validate generic projection evidence, but it must not enumerate target-specific payload kinds or treat Jenkins, GitHub Actions, Tekton, or any other platform as part of the semantic architecture.

Adding a new target or payload kind must not require changing Intent, AST, ExecutionPlan, materialization semantics, or the public TargetManifest structure. Concrete renderers may understand target syntax only at the edge serialization boundary and must fail closed when evidence is incomplete or unrecognized.

## Target projection ownership rule

Flow Core may define immutable target-neutral provider and registry contracts for manifest generation and serialization. It must not instantiate, discover, select or enumerate concrete target projections.

Concrete generators, renderers, payload identifiers, binding syntax and expression translation belong to edge target packages. Application and conformance composition roots may assemble a finite built-in provider registry explicitly. That registry must not perform classpath scanning, dynamic loading, lifecycle callbacks or third-party plugin discovery.

Adding a target projection may require registering a new edge provider in a distribution composition root, but it must not require editing Core generation routing or introducing a target switch into semantic, planning, materialization or manifest contracts.

A target registry declaration with `mode: NATIVE` is not implementation evidence by itself. The selected provider must own a matching immutable native projection contract for the opaque payload kind, reference and typed binding schema before Core may compile a native renderer payload. Missing or inconsistent provider evidence fails closed before executable readiness is inferred.

Cross-target coverage must preserve one semantic action contract while allowing each edge renderer to enforce its own concrete value and structural requirements. A target may be marked native for an action only when registry evidence, provider ownership, renderer behavior and behavioral tests exist together. Similar-looking target syntax must not be treated as equivalent when required runtime structures such as workspaces or repository identity rules differ.

Native image-build coverage must preserve image identity, workspace-relative context, Dockerfile selection and push policy through structured target APIs. Edge renderers must reject values that would require shell commands, free-form CLI argument strings, unsafe path interpretation or silent credential inference. A target-specific limitation may narrow native coverage, but it must not be hidden by approximating another target's behavior.

An executable multi-step reference claim must prove more than isolated native leaves. The selected target must preserve ordering and any workspace, artifact or output continuity required between actions. Reference evidence must declare its target scope explicitly. A target that renders each action separately but lacks the required transfer contract must remain outside that executable reference even when both isolated actions are native.

## Change rule

Every larger change must pass an Architecture Decision Gate before implementation. The decision must explain which part of the core pipeline is strengthened and which drift risks are explicitly rejected.

## Report budget rule

A new public report may be added only when it introduces a stable public contract that cannot be represented by extending an existing report. The default decision is to extend an existing report first. Registry-consistency bookkeeping must stay below one third of the active release profile so the standard remains behavior-led instead of ceremony-led.

## Negative conformance rule

Every release must include at least one negative conformance test or explicitly state why the release is documentation-only.

## Drift Score rule

Every larger feature proposal must be scored using the Drift Score model. Existing baseline artifacts are reported as context only and must not create a positive score floor. A feature with any negative delta score is rejected unless a specific Architecture Decision records an exception and adds a falsifying conformance fixture.
