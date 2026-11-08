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

Conditional recovery/propagation obligations cover healthy and failed runs without
requiring a reviewer to predict the outcome. Handler verification remains mandatory
when recovery is reached.

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

The complete merge-candidate proof passed, while the exact-head run exhausted its
25-minute job budget after JVM tests and conformance had passed. Both full jobs now
have a bounded 35-minute allowance; the isolation budget and every validation gate
remain in place. Installed-host output is unbuffered for progress and failure evidence.

A subsequent native cross-runtime check exposed non-deterministic JSON property
order in the existing condition-composition fixture: `hasErrors` and `hasWarnings`
swapped positions in otherwise identical manifests. Its evidence serializer now
orders properties and map entries explicitly. Regression coverage checks both targets,
preserved values and reordered maps; exact-byte comparison remains mandatory.

Focused tests, full JVM regression, Python tooling, installed conformance, installed
local runtime proof and final-head/merge-candidate CI are required. The PR records
final revision receipts. Local validation uses the pinned toolchain with a temporary
offline dependency mirror; no repository override is included in this change.

Next: durable external observer integration, partial-run reconciliation and exact
producer-invocation lineage for dynamic handles. AI-A3, AR-06/AR-07, F-16/F-20 and
live/render gates remain open; EF-09 remains paused.
