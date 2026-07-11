# v0.9.5.7.3 Renderer Failure Semantics Unification

## Purpose

v0.9.5.7.3 removes target-specific disagreement about unresolved materialization.

Before this correction:

- Jenkins emitted a pipeline that failed inside the first unresolved step.
- GitHub Actions emitted `steps: []`, which could appear as a successful no-op job.
- Tekton referenced a `flow-materialization-required` Task that Flow did not provide.

Those outputs represented the same unresolved semantic state with three incompatible failure behaviors.

## Shared readiness model

`TargetRendererReadinessAnalyzer` classifies a manifest as:

- `EXECUTABLE`: every actionable step has explicit target-native materialization evidence
- `REVIEW_ONLY`: required actions are unresolved or the manifest contains no executable actions
- `BLOCKED`: at least one required action is blocked or unsupported

Approval steps may remain target-native control points. Other actions are executable only when their materialization status is `NATIVE` and their projection evidence identifies a `TARGET_NATIVE` artifact.

## Shared renderer gate

`TargetRendererContractValidator.requireRenderable()` now performs two separate checks:

1. structural renderer-contract validation
2. executable-readiness validation

Malformed manifests still produce an `IllegalArgumentException` with renderer-contract diagnostics.

Structurally valid but unresolved manifests produce `TargetManifestNotExecutableException` with the target, readiness mode, unresolved step ids and blocked step ids.

The exception is raised before Jenkins, GitHub Actions or Tekton syntax is serialized.

## Result

The correction removes:

- GitHub Actions green no-op jobs for unresolved work
- Tekton phantom Task references
- Jenkins fail-later placeholder pipelines for unresolved work

The same semantic state now has one meaning across all implemented renderers: the target artifact is not executable and target serialization is withheld.

## Follow-up

v0.9.5.7.4 removes legacy test generators and fixtures that still encode shell execution as expected behavior.

v0.9.5.7.5 will connect compatibility reporting to materialization and projection readiness so `SUPPORTED` cannot be confused with executable readiness.
