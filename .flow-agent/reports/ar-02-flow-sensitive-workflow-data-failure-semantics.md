# AR-02 semantic closure evidence

## Validation candidate

AR-02E is undergoing integrated validation in PR #172. This candidate does not claim completion: the work package and recovery roadmaps retain AR-02 as active. AR-03 remains planned and not activated. A completion transition may cite only already-successful exact-head and synthetic merge-candidate boundaries.

## Scope and historical implementation

The reviewed implementation revisions are recorded in `architecture-recovery/ar-02/closure-evidence.yaml`.

- F-02: PR #167 and #169 introduced shared path-sensitive availability, explicit merge contracts and exact producer provenance.
- F-08: PR #170 introduced workflow-owned compilation, trigger routing and non-flattening public plan sets.
- F-15: PR #171 introduced typed workflow failure policy, handler ownership and authorized target projections.

The closure now also repairs a production integration defect: typed merge ControlNode paths were rejected by a materialization validator that looked only at legacy task/approval dependsOn fields. Direct, well-formed typed ordering relations now back ControlNode paths. Independent task/approval dependency mirror checks remain mandatory. Negative tests prove that missing, malformed or unresolved ordering cannot authorize a value path.

## Executable matrices

### Frontends

A common single-approval program is compiled through Flow Source, Intent YAML and reviewed AI. The complete approval operation, inputs, output ownership, failure disposition and required topology kinds are compared. This bounded observation explicitly abstracts authored versus structural node identity and rejects additional structures; it does not assert equal raw hashes for different source identities. Intent and reviewed AI must retain exact canonical graph, digest and graph-derived public-view equality. Multi-workflow Intent/AI convergence and Source-specific merge/failure scenarios are checked separately.

### Mutation and authorization

Ten independently observed mutations cover path value, merge producer, required merge edge, workflow membership, trigger routing, failure disposition, handler identity/membership, error binding and handler-entry availability. Each must change the graph digest and reject stale authorization with both the old and recomputed digest. Invalid ownership/edge mutations must fail graph validation. Removing a merge arm must fail the typed constructor. Reordering merge input storage must preserve canonical meaning.

### Targets

Every registered target is observed for merge, multi-workflow and workflow failure. Missing providers remain explicitly unavailable. Existing providers must preserve diagnostic merge structure without rendering executable syntax. Both multi-workflow request factories must reject without flattening. Native Jenkins rendering must preserve body, catch, error binding, handler and PROPAGATE rethrow. These are projection/rendering checks, not new runtime behavioral certification or target-wide support claims.

### Public compatibility

Live single- and multi-workflow plan sets are validated against the checked-in 1.1 schema. A bounded fail-closed evaluator supports exactly the schema keywords used by that contract and refuses unknown constraints. Negative payloads prove that missing failure policy, wrong versions/disposition, missing handler entry and invalid entry types are rejected. Exact single-workflow compatibility views and multi-workflow accessor rejection remain required.

### Finding and lifecycle evidence

Finding entries bind historical PR/commit references, production declarations, regression functions and correctly polarized conformance checks. Every referenced check must have exactly one successful result from the current execution. The main runner executes predecessor suites once and passes their observations into closure. Strict YAML parsing preserves hierarchy and rejects duplicate keys. Completed lifecycle claims require passed, distinct exact-head/merge-candidate receipts; a coherent active candidate is not reported as a completion receipt.

## Boundaries

No public artifact version is advanced, no adapter is extracted, no target support is promoted and AR-03 is not activated. Final PR readiness requires complete official Flow CI on the final published head and its synthetic merge candidate, independently of historical receipts committed by the lifecycle transition.
