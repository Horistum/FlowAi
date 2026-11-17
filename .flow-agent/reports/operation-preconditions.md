# AI-A3-S3.6: fresh existing-resource preconditions

Baseline: merged PR #273, `4c122ec83b8e0d1a2cb819bf8581899d0aea28bb`.

## Problem and result

An accepted plan and a completed worker evaluation do not establish whether an
existing target still has the reviewed owner, native instance or necessary state.
The new installed `preconditions` command evaluates independently frozen
expectations against fresh authenticated snapshots for the exact canonical graph.
Changed authenticated values are violations; missing, stale, ambiguous or
substituted evidence cannot satisfy the contract. Reports carry the evaluation
clock, earliest expiry and no execution authorization.

## Ownership and implementation

The existing outcome frontend owns the new version 1 snapshot protocol. It reuses
canonical operation selection, resource operand interpretation, exact scalar
comparison, bounded input capture and signature framing. A distinct signing domain
and trust document separate preconditions from post-run outcomes. Existing outcome
versions 1–3 retain their behavior. No second Python semantic evaluator or runtime
executor is introduced.

Explicit literal resource addresses require native instance pins; managed
resources require ownership. Shared resources only allow reads. Every changing
canonical task must be declared, while complete effect-target and scalar fact
coverage remain the independent reviewer's responsibility. Dynamic operands are
explicitly unresolved. The command uses the current clock after compilation and
publishes schema-validated artifacts atomically.

## Review and falsification

- Unit regressions cover cross-domain acceptance, owner/instance/state drift,
  absence and failed observations, freshness boundaries, shared-resource mutation,
  omitted changing tasks, ambiguous selectors and duplicate bindings, nested
  operands, dynamic inputs, wrong known nodes, replay and trust substitution,
  signature/source corruption, decimal precision and immutable input snapshots.
- Seven conformance vectors run across infrastructure, data-copy and credential
  rotation and validate all five public schemas.
- The installed proof uses independently initialized JSON/SQLite provider state.
  Actual persisted ownership transfer, instance replacement at the same address
  and state mutation fail unchanged requirements. Missing, stale, replayed and
  tampered evidence provide additional negative outcomes: 27 installed cases.
- CLI tests cover rejected inputs, current-time expiry, schema identity and atomic
  publication. Production distribution includes only the five new schemas;
  test fixtures do not consume its bounded resource inventory.

## Validation

Local results: all 234 frontend and 80 CLI tests pass, including 19 new frontend
and four new CLI regressions. The complete installed conformance run passes all
326 checks. The installed precondition proof passes 27 cases, and all 71 existing
worker tests pass. The full 495-test Python run has 12 failures caused by the local
Unix-socket restriction; its other 483 tests pass. No tests are disabled in CI.

The initial broad JVM run exposed four inventory-dependent failures from the
missing seven-check registration. After registering the actual new checks, all
four affected suites (26 tests) pass; the remaining module regressions pass too.
No assertion, gate, input budget or historical contract is relaxed.

Final exact-head/merge-candidate CI evidence is recorded in the PR. Both full CI
paths run the installed proof through the existing authoring verification entry
point. Adapter certification and all 495 Python tests remain required there.

## Remaining roadmap boundary

This is an as-of assessment of declared existing-resource preconditions, not a
lease or dispatch authorization. The external executor must reassess and enforce
its own trusted-clock and concurrency boundary. Native observer truth, snapshot
atomicity, complete multi-resource effect coverage and live-model reasoning are
not established by this slice. Independent live portfolio acceptance and external
dispatch enforcement remain next work; AI-A3, AR-06 and AR-07 remain open.
