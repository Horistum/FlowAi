# Attempt-aware run and recovery evidence

Baseline: `b1bc61b313e7e1bde3141cc1f82675dbb1780293`, the head of open PR #262
after PR #263 was merged into that branch. This follow-up does not merge #262.
Work package: [AI-A3-S2.4](../work-packages/attempt-aware-run-evidence.yaml).
Public contract: [Run evidence](../../docs/RUN_EVIDENCE.md).

## Result

Opt-in outcome contracts version 2 add scoped, signed run closure and invocation
history. The existing canonical graph remains the only planned meaning. A bounded
trace checker correlates each actual task/approval, retry number, failure and handler
cause with that graph, without dispatching work or adding a second graph.

Resource claims belong to exact invocations. All attempt results remain visible;
expected failure is distinct from successful execution. Declared recovery requires
an independently selected read-only handler verification and matching state after
run completion. Unknown or incomplete runs cannot supply proof of skipped work or
successful recovery. Pre-completion state cannot survive later changes as final
state evidence. Ownership is required before every changing invocation, including
failed ones. Exact creation evidence remains separate from a successful command.

Version 1 retains its contracts, signature domain and historical operation-local
semantics. Version 2 does not silently enable dynamic resource handles, parallel or
conditional trace proof. Unsupported shapes and missing evidence remain unresolved.
Native exception identity, external atomicity, complete multi-resource coverage,
authenticated human approval and global workflow correctness are not certified.

## Evidence and remaining boundaries

The independent service, SQLite and credential portfolio uses one production
assessment path. Only the test harness starts a loopback service, copies a SQLite
baseline or replaces a local test credential. It captures actual results and a fresh
final state, then signs them using an ephemeral test observer key. Evidence mutations
exercise omission, false causality, incomplete closure, stale state and bad signatures.
This is actual isolated local integration evidence, not a production cloud trial or
provider support promotion. Observer truth remains a caller-owned trust assumption.

The new JVM tests additionally exercise propagation, recovery failure, preserved error
correlation, unknown states, timestamps/delays, replay, strict version boundaries,
resource substitution, ownership and unsupported shapes. Conformance vectors check
independent contracts and refuse to treat compilation as runtime evidence.

## Validation

Focused tests, full JVM regression, Python tooling, installed conformance, installed
local runtime proof and final-head/merge-candidate CI are required. The PR records
final revision receipts. Local validation uses the pinned toolchain with a temporary
offline dependency mirror; no repository override is included in this change.

Next: durable external observer integration, partial-run reconciliation and exact
producer-invocation lineage for dynamic handles. AI-A3, AR-06/AR-07, F-16/F-20 and
live/render gates remain open; EF-09 remains paused.
