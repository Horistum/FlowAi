# Architecture Decision: Require explicit native projection implementation contracts

## Status

Accepted

## Change

A target registry rule with `mode: NATIVE` is necessary evidence, but it is no longer sufficient by itself to create a native renderer payload.

Every built-in target distribution now supplies an immutable `TargetNativeProjectionCatalog`. The catalog declares the opaque payload identity and typed binding contract that the edge implementation actually owns. Manifest generation compiles a native payload only when all three layers agree:

1. the target registry declares a `NATIVE` projection rule;
2. the provider-owned native projection catalog declares the same payload kind, reference and binding schema;
3. the generated manifest bindings satisfy the catalog contract and the existing generic binding contract.

Typed binding resolution moves from the general materialization resolver into this catalog-backed compilation boundary.

## Main Flow Axis

- Target registry evidence
- Materialization negotiation
- Target Manifest generation
- Edge projection ownership
- Render readiness

## Reason

Before this change, Flow Core could turn any structurally valid `NATIVE` registry payload template into `TargetRendererPayload`. Concrete renderer support was discovered only later through target-specific renderer branches. That allowed registry metadata to claim native coverage without a separately composed implementation contract.

The new boundary prevents declaration from being confused with implementation. A missing or mismatched implementation contract fails closed before executable readiness can be inferred.

## Contract Shape

The catalog is target-neutral and contains only:

- an opaque target id;
- an opaque payload kind;
- an opaque payload reference;
- named binding slots;
- required or optional presence;
- accepted target-neutral `ProjectionBindingKind` values.

Flow Core does not interpret target syntax, select handlers, scan the classpath or create a plugin lifecycle.

## Built-in Distribution State

- Jenkins declares the already implemented `JENKINS_STEP/git` contract used by the existing `git.checkout` projection.
- GitHub Actions declares no native projection contracts in this work item.
- Tekton declares no native projection contracts in this work item.

This preserves current honest behavior. Cross-target checkout, native image build and the first executable multi-step reference scenario remain separate work items.

## Explicit Non-Goals

This decision does not add:

- a runtime executor;
- an SDK or adapter framework;
- dynamic plugin discovery;
- target-specific public Flow syntax;
- shell or command projection;
- new checkout coverage;
- native image-build coverage;
- a new executable reference scenario;
- a public artifact or schema version bump.

## Negative Conformance

The smallest falsifying fixtures require that:

- duplicate native implementation identities are rejected;
- a `NATIVE` registry rule without a catalog definition is rejected;
- missing, unexpected or incorrectly typed bindings are rejected;
- a provider cannot use a catalog belonging to another target;
- an empty GitHub Actions or Tekton catalog cannot be used to manufacture native coverage;
- unresolved compile-time values remain explicit and cannot become empty strings;
- Core catalog and resolver sources contain no built-in target identifiers or payload constants.

## Report Budget

No new public report is added. The catalog is an internal generation contract. Existing Target Manifest, readiness and reference-snapshot evidence remain the public audit surface.

## Drift Score

The change removes a declaration-to-executability shortcut and adds no runtime, SDK, plugin, template or target-specific semantic direction. No Drift Score exception is required.

## Consequences

Adding native projection coverage now requires two reviewed changes at the edge: registry evidence and an implementation contract. The concrete renderer remains responsible for target syntax. Flow Core remains responsible only for generic contract agreement, typed binding compilation and fail-closed validation.
