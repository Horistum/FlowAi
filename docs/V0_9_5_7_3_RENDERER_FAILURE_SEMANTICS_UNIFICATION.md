# v0.9.5.7.3 Renderer Failure Semantics Unification

## Purpose

v0.9.5.7.3 removes three incompatible unresolved-rendering behaviors:

- Jenkins fail-fast pipelines that stop on the first unresolved step
- GitHub Actions jobs with `steps: []` that can become green no-op artifacts
- Tekton Pipelines that reference a `flow-materialization-required` Task that Flow does not provide

Unresolved Flow work is now represented by one target-neutral review artifact rather than target syntax that appears runnable.

## Render modes

The renderer policy defines two modes:

- `EXECUTABLE`: every required leaf step carries explicit target-native projection evidence
- `REVIEW_ONLY`: one or more required steps lack target-native executable projection evidence

A `NOTES_PROJECTED` step is not automatically executable. It represents notes-backed semantic materialization, but the target renderer still needs explicit target-native projection evidence.

## Review artifact

All current reference target manifests produce the same review disposition:

- `mode: REVIEW_ONLY`
- `executable: false`
- target identity
- requested artifact format
- target-specific input references
- jobs and unresolved steps
- materialization status, capability and reason

The review artifact is not a Jenkinsfile, GitHub Actions workflow or Tekton Pipeline. During the repair track it remains stored under the historical snapshot names so existing snapshot indexing stays stable. Snapshot names and conformance wording are scheduled for reset in v0.9.5.7.9.

## Executable evidence boundary

Executable rendering requires:

- manifest metadata `executableProjection=true`
- `NATIVE` materialization for every required leaf step
- leaf metadata `targetNativeProjection=true`
- concrete renderer metadata for the selected target

Current reference manifests do not meet this boundary and therefore remain review-only.

## Renderer-specific correction

- GitHub Actions executable rendering no longer has a `steps: []` fallback.
- Tekton executable rendering no longer has a phantom Task fallback.
- Jenkins executable rendering no longer uses unresolved `error(...)` steps as a generated pipeline substitute.

## Versioning boundary

- Current package version remains `0.9.4`.
- Active public standard version remains `0.7.6`.
- No artifact schema version is changed.
- Full compatibility and readiness status reconciliation remains assigned to v0.9.5.7.5.
