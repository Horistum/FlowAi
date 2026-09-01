# AR-01 Canonical Compiler Axis and Authoritative Execution Graph

## Completion decision

AR-01 is complete. The recovered compiler architecture now has one product compilation axis, one typed authority for accepted executable meaning, graph-derived compatibility views, graph-bound target authorization and an explicitly non-executable architecture-obligation evidence graph.

This milestone closes audit findings **F-04**, **F-09** and **F-17**. AR-02 is selected as the next Architecture Recovery milestone but is deliberately not activated by this change.

## Authority outcome

### One compiler axis

Intent YAML, Flow Source and reviewed AI proposals converge through `FlowCompilationService`. Frontend provenance, diagnostics and proposal-review evidence remain in the compilation envelope while accepted semantic identity is represented by `CanonicalExecutionGraph`.

### One executable-meaning authority

`CanonicalExecutionGraph` is the sole pre-materialization authority for accepted executable meaning. Its semantic digest observes capability, dependency, effect, authored control, continuity and topology meaning while excluding implementation binding, target metadata, diagnostics and frontend provenance.

`CompilationAuthorization` binds validation and authorization to that exact graph digest. Evidence for a different graph cannot authorize materialization.

### Derived compatibility views

`ExecutionPlan` and `CanonicalExecutionPlan` are direct projections of the same validated `CanonicalExecutionGraph` plus its explicit non-semantic binding envelope. Product authorization no longer canonicalizes an `ExecutionPlan` into a second semantic authority.

`ExecutionPlanCanonicalizer` remains only as a deprecated compatibility/parity facade. AR-01 conformance rejects production callers that attempt to restore it as an independent authority.

### Graph-bound target edge

Target projection, capability resolution and execution gates receive `CompilationAuthorization`. A compatibility `TaskNode` must exactly match the graph-derived task view before implementation binding can be observed. Adapter `module.action` therefore cannot synthesize canonical capability or create a substitute one-node semantic graph.

### Honest architecture-obligation evidence

The production `SemanticActionGraph` type is retired. `ArchitectureObligationGraph` records notes, architecture and materialization obligations only and is explicitly not compiler IR, runtime meaning or an executable plan.

Canonical capability owns target-neutral obligation meaning when capability exists. Implementation binding is separately auditable evidence. Capability-absent tasks receive only canonical-authorization conformance evidence rather than invented semantic meaning.

## Findings closed

- **F-04, split compiler axes:** all accepted product frontends converge through `FlowCompilationService` before graph authorization and materialization.
- **F-09, misleading SemanticActionGraph authority:** the execution-looking production type is removed and its legitimate notes/evidence role is owned by `ArchitectureObligationGraph`.
- **F-17, stringly and redundant IR authority:** typed canonical graph identities and facets own internal semantic meaning; stable public strings remain deliberate derived wire compatibility rather than a parallel authority.

## Public-contract freeze

AR-01 closes without a public wire migration. The following published versions remain unchanged:

- Intent `2.0`
- AST `2.2`
- Execution Plan `2.4`
- Intent lowering evidence `2.1`
- Target Manifest `3.0`
- Target Registry `3.2`

Historical Target Manifest `semanticGraph` metadata, stable diagnostic identifiers and compatibility evidence keys remain preserved where changing them would constitute an unrelated wire migration.

## AR-01 conformance inventory

The completed AR-01 milestone is covered by the exact Architecture Recovery inventory:

1. `architecture-recovery.ar-01.compiler-entrypoint-inventory`
2. `architecture-recovery.ar-01.flow-source-compilation-axis`
3. `architecture-recovery.ar-01.intent-frontend-convergence`
4. `architecture-recovery.ar-01.reviewed-ai-frontend-convergence`
5. `architecture-recovery.ar-01.compiler-dependency-direction`
6. `architecture-recovery.ar-01.public-contract-freeze`
7. `architecture-recovery.ar-01.canonical-graph-integrity`
8. `architecture-recovery.ar-01.semantic-digest-determinism`
9. `architecture-recovery.ar-01.graph-mutation-polarity`
10. `architecture-recovery.ar-01.authorization-digest-binding`
11. `architecture-recovery.ar-01.derived-view-consistency`
12. `architecture-recovery.ar-01.graph-bound-target-authorization`
13. `architecture-recovery.ar-01.obligation-authority-retirement`
14. `architecture-recovery.ar-01.no-pseudo-graph-bypass`

Standalone conformance at the accepted AR-01D boundaries reported **227/227 checks passing**.

## Validation boundaries

| Boundary | Exact head | Synthetic merge candidate | Flow CI | Result |
|---|---|---|---:|---|
| AR-01 activation | `6131b6e43db54a60f9b132baa0d545f99e1b4498` | `9b4d090dfb53b8915fd4bd05504d058da0c4edf9` | #3091 | PASS |
| Initial AR-01 implementation | `882e306125dbfa8d7a6102629a22111510b05c94` | `dcd1c3183e90ded7a543182436e93ee570900c04` | #3104 | PASS |
| AR-01D implementation | `bb2a5254cc6e41430e7570e91bc30687521d7e85` | `affca90d9cc3039de59a0d0649814ac5d9d8e4d6` | #3145 | PASS |
| Independent validation | `d9e48a5bbdd8e86bf7a804a8c78b02fe1ef8e62c` | `47bdd893afde9df826121ab33fa9659a0d761eb7` | #3146 | PASS |
| Completion | `4bbd9437c30d1fc44bfe7a1e2ebd2f17f4ab1fcf` | `ecbaf9f71ecaa5fc65d1049751894264a0cba974` | #3147 | PASS |

The accepted AR-01D implementation suite contains **1,265 tests across 236 test classes with 0 failures, 0 errors and 0 skipped tests**, plus 31 Flow Agent Python tests. Both exact-head and synthetic merge-candidate jobs execute clean tests and standalone conformance.

## Successor boundary

AR-02, **Flow-Sensitive Workflow, Data and Failure Semantics**, is the next Architecture Recovery milestone. It remains `not-activated` here. Branch-local value lattices, phi/merge semantics, multi-workflow execution and first-class failure-path enrichment therefore remain outside PR #165 and require their own activation boundary.
