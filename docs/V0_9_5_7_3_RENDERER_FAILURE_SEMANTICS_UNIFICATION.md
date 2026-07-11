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

## Shared renderer policy

`TargetRendererContractValidator` performs structural validation and reports executable readiness separately.

Malformed manifests still fail before rendering with renderer-contract diagnostics.

Structurally valid but unresolved manifests are serialized as a deterministic comment-only review artifact. The review artifact contains:

- target and compatibility identity
- readiness mode
- `Executable: false`
- unresolved and blocked step ids
- an explicit statement that target syntax was intentionally withheld

It is not a Jenkins pipeline, GitHub Actions workflow or Tekton resource.

## Result

The correction removes:

- GitHub Actions green no-op jobs for unresolved work
- Tekton phantom Task references
- Jenkins fail-later placeholder pipelines for unresolved work

The same semantic state now has one meaning across all implemented renderers: the target artifact is not executable and only review evidence is emitted.

## Follow-up

v0.9.5.7.4 removes legacy test generators and fixtures that still encode shell execution as expected behavior.

v0.9.5.7.5 will connect compatibility reporting to materialization and projection readiness so `SUPPORTED` cannot be confused with executable readiness.
