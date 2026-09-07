# AR-02 Flow-Sensitive Workflow, Data and Failure Semantics

## Completion decision

AR-02 is complete through AR-02E once this candidate passes the required exact-head and synthetic merge-candidate validation boundaries. The recovered compiler now preserves path-sensitive value availability, explicit merge identity, workflow ownership, trigger routing and workflow failure policy as one authorized canonical meaning.

This milestone closes audit findings **F-02**, **F-08** and **F-15**. AR-03, **Compiler-Enforced Boundaries and Adapter Extraction**, is selected as the next Architecture Recovery milestone but remains **not activated**. This change does not begin module extraction or move concrete adapters.

## Integrated authority outcome

### Flow-sensitive value availability

`FlowAvailabilityAnalyzer` computes one immutable path-aware analysis consumed by both validation and planning. Undefined, maybe-defined, definitely-defined and explicitly merged values remain distinct. Unguarded uncertain reads fail closed, while branch-local reads resolve only to producers available on that path.

### Explicit merge and producer identity

Mutually exclusive producers never become one value because their display names happen to match. The authored `merge(...)` contract identifies the join, incoming producer identities and exhaustive path ownership. Ordering, value and continuity relations are derived from producer and merge identities rather than traversal order.

### Workflow ownership and trigger routing

Multi-workflow Intent lowers into independent workflow plans and one `WorkflowExecutionPlanSet`. Canonical workflows retain distinct roots, node membership, outputs and trigger routes. Legacy single-workflow accessors refuse to choose or flatten a multi-workflow compilation. Current target adapters remain fail closed because none owns a certified multi-workflow execution contract.

### First-class workflow failure policy

Each canonical workflow owns a typed failure disposition and optional handler region. Handler membership, entry availability and propagation behavior participate in graph validation, digesting and authorization. Adapter and target consumers use the authorization-owned policy. The synthetic terminal `TryPlanNode` survives only as an exact graph-derived compatibility mirror and cannot create failure meaning.

## Integrated semantic closure matrix

AR-02E adds one executable matrix that combines the four earlier slices rather than merely re-running them in isolation:

1. **Frontend matrix** verifies Intent YAML, reviewed AI proposal and Flow compilation inputs converge through `FlowCompilationService`, while actual parsed Flow Source preserves explicit merge and failure contracts. It also verifies multi-workflow Intent and reviewed AI proposal equivalence.
2. **Mutation matrix** proves semantic digest sensitivity to branch values, workflow routing and failure disposition while preserving merge input-order invariance and rejecting normal/failure region overlap.
3. **Target-gating matrix** preserves the certified Jenkins workflow-failure path, rejects unsupported explicit-merge execution and rejects executable or diagnostic multi-workflow flattening for every registered target.
4. **Public-compatibility matrix** verifies exact single-workflow compatibility views, `WorkflowExecutionPlanSet` 1.1 serialization and schema evidence, multi-workflow legacy-accessor rejection and exact failure-tail parity.
5. **Finding-closure matrix** binds F-02, F-08 and F-15 to existing production files, positive checks and independent negative or mutation checks.

## Findings closed

### F-02: validation and planning confused may-exist with must-exist values

Production evidence:

- `FlowAvailabilityModel.kt`
- `FlowAvailabilityAnalyzer.kt`
- `FlowValidator.kt`
- `FlowPlanner.kt`

Positive evidence includes the availability-lattice and explicit-merge checks. Negative evidence includes uncertain-read rejection and producer-permutation invariance. Direct planner invocation cannot bypass the same path-sensitive gate used by `FlowCompilationService`.

### F-08: the model declared multiple workflows while executable lowering supported one

Production evidence:

- `IntentToAstPlanner.kt`
- `CompilationContracts.kt`
- `CanonicalExecutionGraphBuilder.kt`
- `CanonicalExecutionGraphProjection.kt`
- `TargetSelection.kt`

Positive evidence preserves workflow identities, roots, memberships and trigger routes. Negative evidence proves all current target materialization paths reject multi-workflow execution instead of selecting the first workflow or flattening nodes.

### F-15: global error handling was encoded as a synthetic tail Try node

Production evidence:

- `WorkflowFailurePolicy.kt`
- `CanonicalExecutionGraph.kt`
- `WorkflowFailureAuthorization.kt`
- `AdapterControlRequirementAuthority.kt`
- `BuiltInTargetProjections.kt`

Positive evidence verifies typed policy round-trip, handler ownership and Jenkins propagation behavior. Negative evidence proves a terminal empty-body `TryPlanNode`, generated identifier or capability string cannot forge workflow-level failure authority.

## Public contracts

AR-02 introduced two deliberate public boundaries:

- `WorkflowExecutionPlanSet` 1.0 for non-flattened multi-workflow projection in AR-02C;
- `WorkflowExecutionPlanSet` 1.1 for required typed workflow failure policy in AR-02D.

Intent remains 2.0, AST 2.2, Execution Plan 2.4, Intent lowering evidence 2.1, Target Manifest 3.0 and Target Registry 3.2. AR-02E adds no public wire migration and changes no target support class.

## Conformance inventory

The completed AR-02 milestone is covered by the ten slice checks plus five integrated closure checks:

- `architecture-recovery.ar-02.flow-analysis-single-owner`
- `architecture-recovery.ar-02.value-availability-lattice`
- `architecture-recovery.ar-02.maybe-defined-read-rejection`
- `architecture-recovery.ar-02.explicit-merge-integrity`
- `architecture-recovery.ar-02.producer-permutation-invariance`
- `architecture-recovery.ar-02.public-contract-migration-integrity`
- `architecture-recovery.ar-02.workflow-membership-integrity`
- `architecture-recovery.ar-02.multi-workflow-materialization-gate`
- `architecture-recovery.ar-02.failure-policy-integrity`
- `architecture-recovery.ar-02.no-synthetic-handler-authority`
- `architecture-recovery.ar-02.integrated-frontend-matrix`
- `architecture-recovery.ar-02.integrated-mutation-matrix`
- `architecture-recovery.ar-02.integrated-target-gating-matrix`
- `architecture-recovery.ar-02.integrated-public-compatibility-matrix`
- `architecture-recovery.ar-02.finding-closure-evidence`

## Validation boundary

The implementation candidate must pass Flow Agent tooling, Flow Agent structure validation, Kotlin compilation, the focused AR-02 matrix, the complete Gradle test suite and standalone conformance. The final PR head must then pass both official Flow CI jobs on the exact head and the synthetic merge candidate. Exact validation identifiers are recorded in the PR after those runs complete; this report does not invent future green evidence, because humanity has already tried that strategy.

## Successor boundary

AR-03 is the next ordered milestone and remains **not activated**. Its Gradle module extraction, compiler-enforced dependency directions and concrete adapter moves require a separate work package and activation transition. AR-02E closes semantic ownership only and deliberately performs no AR-03 implementation.
