# v0.9.5.7.3 Renderer Failure Semantics Unification

## Purpose

v0.9.5.7.3 removes target-specific ambiguity when materialization is unresolved.

Before this repair:

- Jenkins emitted runtime `error(...)` calls.
- GitHub Actions emitted empty jobs that could appear successful while doing no work.
- Tekton referenced a task that Flow did not provide.

Those outputs represented three different failure models for the same semantic state.

## Unified render modes

All target renderers now evaluate the same readiness policy before emitting target syntax:

- `EXECUTABLE`: every materialization leaf has a completed materialization status and explicit renderer payload evidence for the selected target.
- `REVIEW_ONLY`: semantic or materialization evidence exists, but target renderer payload evidence is incomplete.
- `FAIL_FAST`: a required semantic action is blocked or unsupported.

## Review-only artifacts

When readiness is `REVIEW_ONLY`, Jenkins, GitHub Actions and Tekton render the same Flow-owned diagnostic document:

```yaml
apiVersion: flowlang.org/v1alpha1
kind: TargetProjectionReview
spec:
  renderMode: REVIEW_ONLY
  executable: false
```

The document lists every unresolved node and reason. It is deliberately not valid Jenkins, GitHub Actions or Tekton syntax, so it cannot be mistaken for a runnable target artifact.

## Fail-fast behavior

When a leaf action is `BLOCKED` or `UNSUPPORTED`, rendering terminates through `TargetRenderBlockedException` before any target artifact is returned.

This is a projection-time failure, not a delayed target-runtime failure.

## Executable evidence

A completed materialization status is not sufficient by itself. Target syntax requires all of the following step metadata:

- `rendererReady=true`
- `rendererTarget=<selected target>`
- a non-empty `rendererPayloadId`

Current general-purpose actions do not yet provide those fields, so they remain review-only. This is intentional honesty rather than a claim that notes metadata alone is executable target code.

## Correctness boundary

This repair does not modify compatibility status. Capability compatibility and executable readiness are connected in v0.9.5.7.5.

It also does not add target-native action payloads. Those must be introduced through explicit projection implementations and evidence, not inferred from a materialization label.
