# AR-06AE-C6 — Exclusive match branch composition

PR #233 merged at `0c62d57e5edb382422cf34d59df92ec1634a26d1`.
The remaining checkout-only match body gate conflated leaf implementation with
branch selection. This slice replaces that gate with a sequential control contract:
execute exactly the selected branch, including an empty branch, or the default
when no case matches. Preserve branch order, lexical values and failure propagation.

## Implementation and boundary

The adapter accepts independently materialized tasks, conditions and nested matches
in case and default bodies. Leaf identity and cardinality do not determine selection
support. Renderer validation repeats the control boundary for caller-supplied
manifests. Existing native payload, expression, value-binding and workspace
continuity owners still decide whether the complete composition is executable.

Sources remain inert string literals; cases remain disjoint literal Booleans and
error branches empty. This avoids inventing predicate precedence, source evaluation
or implicit results. Retry, loop, parallel, approval and error-handler interactions
inside match require separate evidence. Branch values cannot escape or become
available in sibling branches. No Core meaning, public syntax or version changes.

## Evidence

Source-frontend tests vary task/case counts, identities and selected case positions.
They cover nested conditions/matches, empty selected/default bodies, local result
consumers and negative scope escapes. A checkout plus image-build projection requires
its explicit parsed-AST workspace dependency; authored order alone remains blocked.
Image-build coverage is projection evidence only. Unsupported native operations,
controls and forged payloads remain rejected.

The verification-only source model computes selection, task order and failure
routing without executing actions. It validates even unselected bodies. Canonical
root/child edges and manifest branch wrappers must preserve source membership,
guards, source literals and order; node storage order has no semantic authority.

The existing disposable native runner and observer are reused unchanged. Six
baselines cover selected/default paths with both Boolean inputs, an empty selected
case and nested failure. Six mutants substitute selected/default branches, omit a
prefix, reorder body work, fall through an empty case or swallow failure. Native
checkout FlowNodes, SCM callbacks, stage boundaries and terminal results are compared
with source expectations. Observation envelopes must restore generated calls
byte-for-byte. The independent model also tests malformed graph/manifest bindings.

Source, AST, graph, manifest, generated/mutated artifacts, native observations and
logs are content-bound to the CI revision and run. This is separate regression
evidence, not a signed-portfolio expansion. The existing 39 assessments and all
composition checks remain required. Exact-head and synthetic-merge compilation,
tests, conformance and physical module isolation must pass on GitHub. Final receipts
belong in the PR description after CI; this report does not preclaim future results.
Local Python/Flow Agent checks are separate from the pinned JVM/native runtime.

No broad support, portability, rollback, workspace isolation or cancellation claim
is added. AR-06 remains active; AR-07 is planned, EF-09 paused and F-20 AR-07-owned.
Next, address the remaining parallel topology gate and separately evidenced control
interactions. Counts and green CI alone do not close the architecture milestone.

## CI rerun repair found during validation

Runtime run `39206861642` initially failed to download Jenkins plugin metadata
(403/404). Its targeted rerun passed the producer, but the portfolio download chose
the earlier 2,395-byte incomplete archive instead of the new 195,873-byte archive
with the same name. Admission correctly rejected the missing proof.

Every producer-owned upload in the runtime workflow now replaces its earlier
same-name archive on rerun (`overwrite: true`). Names remain distinct across
producers; successful job receipts, proof/trust digests and authenticated admission
remain mandatory. This repairs artifact selection, not semantic acceptance. Job logs
retain the earlier failed attempt. The updated revision requires fresh CI.
