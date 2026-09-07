# AR-02 semantic closure evidence

## Completion decision

AR-02 is complete through AR-02E. Findings F-02, F-08 and F-15 have shared production contracts, current executable conformance results and negative regression evidence. The work package, Architecture Recovery roadmap, post-toolchain roadmap and release recovery state record one consistent completion transition. AR-03 is next and remains **not activated**. EF-09 remains paused; the terminal global roadmap stays at 0.9.7.10 with no global successor.

This report records already-successful validation boundaries, not an assertion that a future workflow run passed. The final completion-transition commit must independently pass both official PR Flow CI jobs before the PR is marked ready for review. Its exact head and final run identifiers belong in the PR verification record, outside the commit being verified.

## Scope and historical implementation

The typed finding catalog is `architecture-recovery/ar-02/closure-evidence.yaml`.

| Finding | Implementation PR | Merged revision |
| --- | --- | --- |
| F-02, shared path-sensitive availability | #167 | `2346f8bd50636b2647cd31cb06115bf03b582157` |
| F-02, explicit merge and producer provenance | #169 | `63aa873143c51184df6c17cb47fe611caa9089a3` |
| F-08, workflow ownership and non-flattening projection | #170 | `ca0fe0e563eab0b200d1f229ef6e4a0df69e6e5d` |
| F-15, typed workflow failure policy | #171 | `4e045991d6a9bb1824f08d80140710142ebf807d` |

The integration review also found and repaired a production defect: valid typed merge ControlNode paths were rejected by a materialization validator that inspected only legacy task/approval `dependsOn` fields. Commit `3f9e7538ba049a3aa52ca28d234ce325e4f51077` permits a ControlNode path only when a well-formed, direct, resolved typed ordering relation backs it. Task and approval dependency mirror checks remain mandatory. Six regression tests cover real parsed merge, valid control paths, missing ordering, malformed or unresolved evidence, and approval mirror integrity.

## Executable matrices

### Frontends

A common single-manual-approval program is compiled through Flow Source, Intent YAML and reviewed AI. The bounded observation compares the complete approval operation, inputs, output ownership, failure disposition and required topology kinds. It explicitly abstracts authored versus structural node identity and rejects additional structures rather than silently deleting them. It does **not** assert equal raw graph hashes across different canonical source identities.

Equivalent Intent and reviewed AI inputs must retain exact canonical graph, digest and graph-derived public-view equality. Multi-workflow Intent/AI convergence and Source-specific explicit merge plus workflow failure are separate scenarios. Every resulting compilation independently verifies graph validation and authorization integrity.

### Mutation and authorization

Ten independent mutation observations cover path-local value, merge producer, required merge edge, workflow membership, trigger routing, failure disposition, handler identity, handler membership, error binding and handler-entry availability. Each must change the graph digest and reject stale authorization with both the old and the recomputed digest. Invalid ownership/edge mutations must also fail graph validation. Removing a merge arm must fail the typed constructor; changing merge input storage order must preserve canonical meaning.

Matrix-level negative tests reject an unchanged digest after semantic mutation, accepted stale authorization, and missing or duplicated witnesses.

### Targets

Every registered target is observed for explicit merge, multiple workflows and workflow failure. A missing provider is explicitly unavailable. Both multi-workflow request factories must reject without selecting or flattening a workflow.

Existing providers must preserve diagnostic merge and failure structure without rendering unsupported executable syntax. Execution-candidate rendering and diagnostic rendering are checked separately, and an unexpected successful render is recorded rather than discarded. A blocked diagnostic cannot conceal an executable candidate.

Failure projection evidence follows the provider-owned layout: Jenkins carries the workflow boundary on a structured step, while GitHub Actions and Tekton carry it on handler jobs. The comparison uses the expected typed policy and verifies exact handler identity/membership, error binding, prior-success availability and protected-work dependencies. One matching metadata record cannot hide an omitted handler or metadata moved onto a normal job.

Native Jenkins rendering must retain body, catch, error binding, handler and one PROPAGATE rethrow in order. These are compilation, projection and rendering checks, **not new runtime behavioral certification** or target-wide support claims. Eight focused regression tests cover the provider layouts, every required policy field, displaced membership, partial handler loss, missing handler region, missing guard, independently executable candidate and merge/failure interaction.

### Public compatibility

Actual single- and multi-workflow plan sets are validated against the checked-in WorkflowExecutionPlanSet 1.1 schema. A bounded fail-closed evaluator supports the keywords used by this contract and reports unsupported validation keywords rather than ignoring them. Negative payloads prove rejection of missing failure policy, wrong version or disposition, missing handler entry and invalid entry types. A weakened schema that stops requiring failurePolicy also fails the matrix.

Exact single-workflow compatibility views, multi-workflow legacy-accessor rejection and graph-derived failure-tail parity remain required. The synthetic tail is checked as a compatibility mirror and is not accepted as semantic authority.

### Finding and lifecycle evidence

Finding entries bind historical PR/commit references, production declarations, regression functions and correctly polarized conformance checks. Every referenced check must have exactly one successful result from the current execution. The main runner executes predecessor suites once and passes those observations into closure.

Strict YAML parsing preserves hierarchy and rejects duplicate keys. A completed claim requires four passed boundaries with distinct workflow runs, exact heads and synthetic merge candidates. Each receipt separates the two job IDs. `localValidation` must match the entire validation receipt, not just its run ID. Seven dedicated lifecycle regressions reject pending completion, mismatched local receipts, reused runs or heads, premature AR-03 activation and completed work packages with active roadmaps, and accept a coherent completed snapshot.

Offline conformance checks the committed evidence structure and live declarations/results. Historical revision identity and actual CI outcomes are separately verified through GitHub; syntactically valid IDs are not themselves proof that a workflow ran.

## Verified validation boundaries

All four rows below record completed, successful official Flow CI runs. Exact-head and merge-candidate jobs passed Flow Agent tooling/structure, complete Kotlin compilation and tests, and standalone conformance.

| Boundary | Flow CI run / number | Exact-head job | Merge-candidate job |
| --- | --- | --- | --- |
| Historical activation | `33584116350` / `3150` | `100104531780` | `100104531585` |
| AR-02A through AR-02D implementation baseline | `33844548559` / `3198` | `100933507975` | `100933507963` |
| Integrated validation and target hardening | `34113587213` / `3226` | `101715143196` | `101715142761` |
| Independent closure and lifecycle integrity | `34116599418` / `3228` | `101724761998` | `101724761699` |

| Boundary | Exact head | Checked synthetic merge candidate |
| --- | --- | --- |
| Activation | `0ad7157d425ceb2152457fed8d44bb123e4a67fc` | `a22ec74c12d75e3862d1796710c7c5bddb2f24b8` |
| Implementation | `d0c42002519f5c1a4a48a0c4115097f116bfcc6d` | `679509d25501b508ef8ec7f79d2eb5e00da05ebe` |
| Validation | `cbf345cfdf7d506c90ecd97f18359185f2d1387f` | `e7a549606d617fa5e6fb57c8f53e3783c568580a` |
| Completion | `748fa4390f5e6e404e691f8d3e6a3318af9ed746` | `60f370f6d98446f9c01a6d6511d4ed7a885ebab8` |

The implementation row validates the merged AR-02A through AR-02D baseline, not later AR-02E repairs. The two subsequent boundaries validate those repairs and the integrated closure. Validation includes 1351 Kotlin tests; the independent closure adds seven lifecycle tests for 1358 tests with zero failures. Flow Agent tooling ran 31 Python tests. These checks ran in GitHub Actions; no local Gradle execution is claimed.

## Remaining boundaries outside AR-02

No public artifact version is advanced by AR-02E. WorkflowExecutionPlanSet remains 1.1, ExecutionPlan 2.4, AST 2.2, Intent 2.0, lowering evidence 2.1, TargetManifest 3.0 and TargetRegistry 3.2. No adapter is extracted, no target support is promoted, and no temporary helper workflow or patch payload is part of the implementation.

AR-03 requires its own work package and activation transition. Module extraction, broader language/type/I/O corrections and runtime adapter certification retain their existing later recovery owners; this closure does not claim that every project-wide audit finding is resolved.
