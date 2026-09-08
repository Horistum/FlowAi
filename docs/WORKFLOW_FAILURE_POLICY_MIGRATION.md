# AR-02D Workflow Failure Policy Migration

WorkflowExecutionPlanSet 1.0 to 1.1

AR-02D advances the public `WorkflowExecutionPlanSet` contract from **1.0** to
**1.1**. The new minor version adds an explicit `failurePolicy` to every
workflow view. Existing single-workflow `ExecutionPlan` 2.4 and
`CanonicalExecutionPlan` contracts keep their versions and remain exact
compatibility projections.

## Why the contract changed

Before AR-02D, workflow-level `on error` behavior survived in the public plan as
a generated terminal `TryPlanNode` with an empty body. Consumers could therefore
mistake node position, an `onError_*` id, or `errorHandlers.finally` capability
text for semantic authority. The canonical graph now owns that meaning directly.
The terminal `TryPlanNode` may still appear in the legacy single-workflow view,
but only as a checked graph-derived compatibility mirror.

## WorkflowExecutionPlanSet 1.1 shape

Each `WorkflowExecutionPlanView` now contains:

```json
{
  "failurePolicy": {
    "disposition": "PROPAGATE",
    "handler": {
      "id": "workflow-failure-handler:main",
      "nodeIds": ["..."],
      "entry": {
        "errorBinding": "error",
        "priorSuccessfulValuesAvailable": false
      }
    }
  }
}
```

`handler` is absent when the workflow has no workflow-level error handler.
`RECOVER` is valid only with an explicit handler region. Current Flow source
`on error` semantics compile to `PROPAGATE`: the handler runs and the original
failure remains the workflow result.

## Canonical ownership and validation

The canonical workflow owns:

- failure disposition;
- handler-region identity;
- the exact handler node membership;
- the handler entry error binding;
- whether prior successful values are available.

Handler nodes are not normal workflow roots. Graph validation rejects normal and
failure region overlap, dependency or candidate edges that cross the boundary,
and failure-only values published as normal workflow outputs. All of these fields
participate in the canonical digest and therefore in compilation authorization.

## Consumer migration

Consumers that need workflow failure semantics must start from
`CompilationAuthorization` and use the graph-derived workflow plan set. They
must not infer workflow failure handling from:

- a terminal node;
- an empty `TryPlanNode.body`;
- an `onError_*` id;
- `errorHandlers.finally` capability text;
- display metadata or renderer hints.

The adapter control authority and built-in target projections now use the typed
policy. A raw compatibility plan containing a tail-shaped empty-body `TryPlanNode`
is classified as detached handler semantics and cannot forge a workflow policy.

## Target behavior

Jenkins materialization creates a protected `try/catch` boundary from the typed
policy, binds the configured error variable at handler entry, executes the
handler, and rethrows the original failure for `PROPAGATE`. Nested `try/on error`
continues to use `TryPlanNode` and remains a distinct construct.

Targets without an exact supported control contract remain blocked or
review-only through the existing adapter materialization gates. Canonical
representability does not imply target executability, despite the recurring
human temptation to treat a serializable object as proof that reality agrees.

## Compatibility guidance

Clients reading `WorkflowExecutionPlanSet` must accept contract version 1.1 and
consume `workflows[*].failurePolicy`. Clients that only support 1.0 must fail
closed rather than ignore the new field. Existing code limited to a genuine
single-workflow `ExecutionPlan` can continue unchanged, but it must treat the
terminal failure `TryPlanNode` as compatibility data, not source authority.
