# Architecture Decision: Separate Flow Core from target projections

## Status

Accepted

## Change

Flow Core defines only target-neutral manifest generation, rendering and provider-registry contracts. Concrete Jenkins, GitHub Actions and Tekton generators, renderers, payload identifiers, binding syntax and expression translation live in the built-in target edge package and are assembled explicitly by application and conformance composition roots.

## Main Flow Axis

- Target manifest boundary
- Public artifacts
- Conformance

The change strengthens the boundary between portable Flow semantics and concrete target serialization without changing Intent, AST, ExecutionPlan, materialization semantics, Target Registry 3.0 or Target Manifest 3.0.

## Explicit Non-Goals

This decision does not introduce:

- runtime execution
- SDK direction
- plugin lifecycle
- target-specific DSL
- template ownership
- silent semantic fallback
- adapter implementation framework

`TargetProjectionRegistry` is immutable explicit composition. It performs no classpath scanning, dynamic loading, lifecycle callbacks or third-party implementation discovery.

## Report Budget

No public report is added. Existing compatibility, readiness, Target Manifest and reference snapshot artifacts continue to carry the same contracts.

## Negative Conformance

`CoreTargetProjectionBoundaryTests` provides the smallest falsifying fixtures:

- a provider with mismatched generator and renderer targets is rejected;
- duplicate target providers are rejected;
- an unregistered target cannot be generated;
- Core source files must not enumerate built-in target ids, generator classes, renderer classes or payload kinds;
- CLI and reference snapshot composition must resolve projection behavior through the explicit built-in registry.

## Drift Score

The proposed change has no negative delta signal. It removes target-specific ownership from Core and does not create a runtime, SDK, plugin, template or silent-fallback direction. No Drift Score exception is required.

## Decision

Accepted.

## Consequences

Adding a target projection requires a new edge provider and composition registration, but no change to Intent, AST, ExecutionPlan, materialization semantics or public Target Manifest structure. Core unit tests can use synthetic providers without importing built-in platforms. The built-in distribution remains explicit and finite while avoiding a hardcoded target switch inside Core.
