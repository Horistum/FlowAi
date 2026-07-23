
# v0.9.7.9.3 Environment Safety Production Integration

## Purpose

This work item turns environment safety from an optional side validator into a mandatory part of production Flow validation and CLI lowering.

## Classification contract

Environment evidence has three compile-time forms:

- `LITERAL`: a concrete value that may match a policy rule.
- `REFERENCE`: a runtime reference whose value is not known at compile time.
- `DYNAMIC_EXPRESSION`: any other expression that cannot be reduced safely.

Only parameter names declared by the environment safety policy participate in classification. Explicit environment fields fail closed on unmatched literals. Contextual resource identifiers such as namespaces and clusters become environment evidence only when they match a declared policy value or remain dynamic. Text in unrelated fields such as application names, descriptions or image tags is never environment evidence.

Classification is fail closed:

1. Any sensitive match yields `SENSITIVE`.
2. Otherwise any unresolved or dynamic candidate yields `UNKNOWN`. Explicit environment fields also yield `UNKNOWN` for unclassified literals.
3. Contextual namespace or cluster literals that match no policy rule are not silently labelled safe; they remain resource identifiers rather than environment claims.
4. `NON_SENSITIVE` is returned only from an explicit policy match.

Unknown evidence cannot proceed without reachable approval. Unconditional approval is sequential and branch-scoped. A conditional approval may guard later environment-sensitive work only when its predicate is structurally proven to select a policy-declared sensitive environment, such as `environment == "prod"`. An unrelated optional approval does not authorize the environment boundary.

## Production integration

`FlowValidator` invokes `SafetyBoundaryValidator` using the standard notes-backed environment policy. The CLI requires a valid Flow report before `FlowPlanner` is invoked in direct Flow, Intent and normalization-lowering paths. Reference snapshot generation already requires the same validator report.

A `ReferenceNode` remains a reference. Its path is retained as diagnostic evidence and is never converted to a literal environment value.

## Approval evidence

An action-local `safety: requiresApproval` must be unconditional. A standalone `ApproveNode` is reachability-scoped. A structurally verified sensitive-environment conditional approval may authorize only the later environment boundary; it does not become generic approval evidence for destructive actions. An `onlyIf` safety rule may replace destructive approval only when the referenced input has a finite option domain and exhaustive evaluation proves that every executable value is policy-classified `NON_SENSITIVE`. Arbitrary boolean conditions remain insufficient.

## Diagnostics

- `ENVIRONMENT_CLASSIFICATION_UNKNOWN`
- `ENVIRONMENT_APPROVAL_REQUIRED`
- `APPROVAL_REQUIRED`
- `ROLLBACK_APPROVAL_REQUIRED`

All are registered in the standard diagnostic catalog.

## Boundary

This change does not add a runtime environment resolver, target-specific environment aliases or a shell/runtime escape hatch. Dynamic environment classification remains blocked until a later explicit enforcement capability can prove it.
