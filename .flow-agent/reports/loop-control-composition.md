# AR-06AE-C5 — Finite sequential loop composition

PR #232 merged at `8bad3e11f2d70d3dd316fc3276f6a80b380e88c0`.
This slice removes the checkout-only, nonempty-body loop exception. A finite
sequential loop visits every authored element in order, preserving duplicates.
Each iteration runs the complete body until failure. Empty collections run no body.
The invariant is independent of task identity, cardinality or implementation binding.

## Implementation

The adapter accepts literal string collections with independently materialized tasks,
conditions and nested finite loops. Each nested loop establishes a distinct lexical
item binding; sibling scopes can reuse a name. Input and active outer-item collisions
are rejected. Renderer validation repeats the control/scope boundary for forged
manifests. Existing leaf payload, expression, result-binding and continuity owners
still decide whether the composed artifact is executable. Empty bodies are valid.

No Core meaning or public syntax changes. Dynamic collections, retry, match, parallel,
approval and error-handler bodies remain outside this mapping. Values produced in a
loop cannot escape it. No rollback, workspace isolation, cancellation equivalence,
portable execution or generic target-support promotion is claimed.

## Evidence

Source-frontend tests cover different task counts and identities, nested and empty
loops, conditions and iteration-local result bindings. Checkout plus image-build
projection requires its explicit parsed-AST workspace dependency; mere ordering stays
blocked. Image-build coverage is projection evidence, not native invocation evidence.
Negative tests reject unsupported leaves/controls, collection expressions, lexical
collisions, scope escapes and forged manifests.

An independent verification-only model reads the source AST and declared faults.
It evaluates typed Boolean/string conditions and finite iteration without executing
actions. Canonical root/child edges, collections and item bindings are checked against
that source model. Node storage order has no semantic authority.

The existing disposable native observer and runner are reused. Observation wrappers
must restore generated artifacts byte-for-byte. Four native baselines cover both
Boolean inputs, empty collections and nested failure. Six mutants deduplicate or
reorder outer elements, flatten inner iteration, omit a prefix, invent an empty-loop
iteration or swallow a native failure. Actual checkout FlowNodes, completed stage
boundaries, SCM callbacks and terminal results distinguish their behavior.

Source, AST, graph, manifest, generated/mutated artifacts, observations and logs are
content-bound to the exact CI revision/run. This separate regression proof does not
expand the 39-scenario signed portfolio. All prior runtime and composition checks
remain required. Exact-head and synthetic-merge JVM/conformance results and native
receipts are recorded in the PR after CI; local Java 17 and no Docker do not prove
those pinned runtime results. Local Python and Flow Agent checks run separately.

AR-06 remains active; AR-07 is planned, EF-09 paused and F-20 AR-07-owned. Subsequent
slices address remaining match/parallel restrictions and separately justified control
interactions. Scenario counts and green checks alone do not close the milestone.
