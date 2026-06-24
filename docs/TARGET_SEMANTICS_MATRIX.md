# Target Semantics Matrix

Introduced in v0.4.8.

The target semantics matrix records how supported targets express the same
canonical Flow semantics.

The matrix does not claim that every target is equivalent. It records whether a
feature is native, partial, externally required, expanded into a DAG shape, or
blocked by readiness diagnostics.

## Cross-checked against the standard

The matrix is not an independent hardcoded list. The conformance check
`v0.4.8.target-semantics-matrix` validates it against reality:

- target ids must be real targets in the target registry, not an invented list;
- the `conditions` row is derived from and cross-checked against
  `TargetExpressionSupport`, the single source of truth for which guard
  expressions a target can enforce natively. A target that can express every
  reference condition is `native`; one that can express some but not all is
  `partial`. On this basis Jenkins is `native`, GitHub Actions is `partial`
  (it cannot express every reference guard) and Tekton is `partial`;
- the unsupported-target-condition reference case is verified here as well: a
  guard expression that Tekton cannot express natively must report the
  `condition.expression` diagnostic, while a target that can express it (Jenkins)
  must not.

If the expression-support model changes, the check fails until the matrix is
updated, so the conditions row cannot silently drift. The remaining rows are
descriptive mechanism labels for how each target realizes the feature.

## Required feature families

- conditions,
- approvals,
- secrets,
- artifacts,
- parallelism,
- rollback,
- manual gates,
- environment gates,
- matrix builds,
- dynamic expressions.

Unsupported semantics must produce diagnostics or blocked readiness before
rendering.
