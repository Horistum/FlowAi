# AR-06K candidate: authenticated construct behavior matrix

Each external runtime assessment now emits a construct/target/behavior/mutant
matrix. The eleven AR-06 construct categories are always present, plus the
existing bounded native checkout case. Exactly the scenario's conformance-owned
category carries evidence; all others explicitly lack behavioral evidence in that
assessment. Missing evidence is not an unsupported-feature declaration.

Rows retain the compiler-bound source and graph, adapter identity, runtime
prerequisites, signed run references, exact UTF-8 expected and observed results,
and every distinguishing mutant. They reuse the existing oracles and authenticated
admission. The matrix rechecks reference sizes and hashes when resolving display
bytes; a stateful resolver cannot substitute data after admission. Rejected or
cross-scenario assessments cannot publish a matrix. Output snapshots are immutable
and deterministic, and Markdown escapes external text.

This matters for approvals and local recovery: a mutant can have the same final
SUCCESS result and workspace marker while violating approval ordering or native
step execution. The full observation is visible together with its baseline.
Checkout markers never grant artifact/workspace, secret or value/state continuity
coverage. Graph occurrence alone never assigns a behavioral category.

The six runtime jobs publish the new Markdown and archive both matrix formats.
Each proof binds them by SHA-256 alongside the existing evidence views. These are
single-scenario diagnostic matrices, not an aggregate target portfolio, a public
schema, runtime authorization or general construct certification. The implementation
currently consumes the ten Jenkins scenarios; no other target has been assessed.

## Predecessor boundary

PR #207 merged as `9232061af22922af33ff3a65ada81d1b0d4eedd6`.
Both actual-main workflows passed: Flow CI 38022817327 and runtime CI
38022817342. Nine independently downloaded archives match GitHub SHA-256
metadata and contain 1,976 tests in 341 suites, no failures/errors/skips, the
unchanged ordered 274-check conformance inventory, 769 verified isolation input
hashes and 32 verified Ed25519 observations across ten scenarios. The test
inventory matches PR #207 head and synthetic-merge validation.

The immutable receipt is `.flow-agent/evidence/adapter-behavior-matrix-baseline.json`
(SHA-256 `eecb0fa4dbcaf567d48d989103d3abf56db68ae9fd7502f1812c4c64946bd984`).

## Validation

All 157 Python tooling tests and Flow Agent structure validation passed locally.
Local Kotlin validation was attempted but could not resolve the pinned Kotlin
2.4.10 Gradle plugin in this environment. Kotlin tests and standalone conformance
therefore require the published revision CI; no local Kotlin pass is claimed.

Exact-head and synthetic-merge CI, physical isolation and all six real Jenkins
jobs are required on the published revision. Candidate CI receipts belong in the
PR; this source does not claim its own future validation.

## Limits and next work

Public support and portable execution remain unclaimed. Equivalent execution on
a materially different adapter and the remaining construct oracles are still
required. Core semantics, renderer behavior and package versions are unchanged.
AR-06 remains active, AR-07 planned, EF-09 paused and F-20 AR-07-owned.
