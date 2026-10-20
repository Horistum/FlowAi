# AR-06AE-C10 — Retry and finite iteration composition

Based on merged PR #237, `1734e2f77f943f4fe41b515116965539332c2f56`.

## Problem and correction

C9 reconciled existing observations without claiming complete control interaction
coverage. The retry and loop resolvers still had mutually exclusive recursive
allowlists: a valid finite loop could not appear inside a supported retry, nor a
supported retry inside a loop. This was an implementation gap between two existing
canonical controls, not missing Core meaning.

A shared adapter-owned sequential control validator now accepts tasks, conditions,
fixed zero-delay retries and finite literal-string loops recursively. It preserves
all policy and lexical binding checks, including a loop item captured across an
intervening retry. The renderer validates the same control boundary for forged
manifests. Materialization still owns leaf eligibility, producer values, continuity
and provider availability. The change introduces no Core dependency or public DSL.

The observable invariant distinguishes whole-body replay from per-iteration retry:
retry around iteration replays its completed prefix and prior iterations; retry
inside iteration retains the item and repeats only its own body. Success stops
replay, empty collections do not invent calls, and exhaustion suppresses later
iterations and successors. Alternating nesting, renamed bindings, different task
counts, unsupported leaves, missing continuity and scope escapes have projection
regressions. No fixture name or native checkout count authorizes production work.

## Independent evidence and limits

The existing loop producer keeps its original fixtures and adds success, failure
and empty fixtures for both nesting directions, including false-condition runs.
An independent AST model derives invocation order, multiplicity, attempt boundaries
and terminal result. Canonical child membership and manifest policies are checked
before unchanged native calls are observed. Mutants flatten retry, repeat success,
change collection order/multiplicity, invent iterations and swallow failures.

The existing C9 consumer regenerates all inputs/envelopes and replays all native
cases from the same run and attempt. The primary matrix row remains `LOOPS`, with
retry interactions explicit in its claim and cases. No second producer, duplicate
support catalog, signed assessment or artifact-format change is introduced.

Native evidence covers checkout and permanent-failure/success cases. Transient
fault recovery and per-iteration budget reset have independent model tests, not a
native certification claim. Runtime, compiler and native observer trust boundaries
remain as documented. There is no rollback, new workspace, idempotency, general
value/state transfer or cancellation claim. Other control interactions and dynamic
collections remain unsupported by this contract.

AR-06 remains active; AR-07 planned, EF-09 paused and F-20 AR-07-owned. Historical
acceptance receipts, public support declarations and signed portfolio are unchanged.

## Validation

Local validation passed: 238 Python tooling tests, including seven new mixed-control
oracle regressions; Flow Agent structure and whitespace checks passed. Six new JVM
regressions cover production mapping and rejection behavior. The existing loop job
runs those focused tests before native execution, while full tests, standalone
conformance and module isolation remain required.

The first CI run compiled the change and passed five of the six new JVM tests.
It exposed an incorrect expectation in the remaining test: a definitely available
Core value can produce a diagnostic `REVIEW_ONLY` manifest when the adapter lacks
a child-scope export; manifest creation need not throw. The regression now asserts
that exact review-only state and executable-render rejection. Production checks
remain unchanged.

Validation status: current-revision CI required. The local JDK is 17, not the pinned
JDK 25 toolchain. Exact-head and synthetic merge compilation/tests/conformance, all
native checks and final composition evidence replay must pass before acceptance.
Previous successful CI is baseline evidence only.
