# AR-02C WorkflowExecutionPlanSet 1.0 Migration

## Boundary

AR-02C introduces `WorkflowExecutionPlanSet` version `1.0` as the public target-neutral envelope for a compilation that owns one or more workflows.

This is a new contract axis. It does not advance or reinterpret Intent `2.0`, AST `2.2`, ExecutionPlan `2.4`, ExecutionPlan lowering evidence `2.1`, TargetManifest `3.0` or TargetRegistry `3.2`.

## Why a new contract is required

`ExecutionPlan` and `CanonicalExecutionPlan` were defined as one-workflow compatibility views. Concatenating multiple workflows into either contract would erase workflow ownership, permit node-id collisions and make trigger routing ambiguous. Selecting the first workflow would be even worse, merely with fewer lines of code.

`WorkflowExecutionPlanSet` therefore owns:

- the program display name and shared input contract;
- the complete trigger inventory and exact workflow routes;
- one graph-derived `WorkflowExecutionPlanView` for every workflow;
- program-level source Intent and lowering evidence;
- program-level control and topology requirements;
- an explicit `contractVersion` of `1.0`.

## Compatibility behavior

Single-workflow compilation remains byte- and meaning-compatible through the existing `ExecutionPlan` and `CanonicalExecutionPlan` accessors.

For a multi-workflow compilation, legacy one-workflow accessors fail with `MultipleWorkflowCompatibilityViewException`. They never select the first workflow and never flatten nodes across workflow boundaries.

Consumers that support multiple workflows must read `CompilationUnit.workflowPlanSet` and select a workflow explicitly by its stable name or consume the complete set.

## Trigger routing

Every trigger route must name at least one declared workflow, contain no blank or duplicate routes and survive canonical graph construction and digesting. A workflow-local compatibility view contains only the triggers routed to that workflow, with the route narrowed to that one workflow.

Unknown, duplicate or empty routes are rejected before graph authorization.

## Dependency containment

Plan nodes and dependency edges remain workflow-owned. Cross-workflow step dependencies and canonical dependency edges are rejected unless a future explicit inter-workflow contract authorizes them.

## CLI behavior

    Target-neutral `intent` and lowered `normalize` commands emit `workflow-execution-plan-set.json`, per-workflow compilation evidence and a target-neutral planning report. They do not touch the legacy one-workflow accessors.

    When a target is explicitly selected, the CLI reaches the typed multi-workflow target gate before asking for an `ExecutionPlan`; the failure therefore identifies unsupported adapter semantics rather than masquerading as a generic compatibility-view error.

    ## Target materialization

AR-02C advances compiler representability, not adapter executability. Existing target adapters do not claim multi-workflow behavior. `TargetMaterializationRequest.fromCompilation` and its diagnostic counterpart fail closed with `MultiWorkflowTargetMaterializationUnsupportedException` for a multi-workflow compilation.

A target may support this contract only after its adapter preserves workflow membership and trigger routing and provides separate behavioral evidence.

## Schema and acceptance

`schemas/workflow-execution-plan-set.schema.json` describes the version `1.0` interchange shape and is classified as `SYNTACTIC_INTERCHANGE`. Production semantic validity remains owned by `WorkflowExecutionPlanSet`, canonical graph validation and compilation authorization.

The schema does not replace the typed ownership, route, dependency or authorization checks.

## Migration checklist

1. Keep existing one-workflow consumers on `executionPlan` or `canonicalPlan`.
2. Move multi-workflow consumers to `workflowPlanSet`.
3. Select workflow views by `workflowName`, never by collection position.
4. Preserve program-level trigger routes before narrowing them for one workflow view.
5. Treat target materialization as unsupported until a target-specific multi-workflow contract exists.
