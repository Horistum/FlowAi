# AI-A3-S3.6: reviewed-plan resource preflight

Baseline: merged PR #273, `4c122ec83b8e0d1a2cb819bf8581899d0aea28bb`.
PR #274 was reopened for semantic correction after two installed-CLI counterexamples
and a missing planning integration were confirmed on head `f479583f`.

## Problem and corrected behavior

The initial reviewer counted a task as covered after checking any one of its
parameters. A DATA_SYNC source-only contract therefore passed without checking the
write target. It also applied all task effects to each operand, incorrectly
rejecting a shared read-only source because the destination was written.

CanonicalIntentEffectAuthority now defines resource operand bindings beside the
existing effects. Both old effect APIs and the new binding API consume those same
definitions. Historical effect values, graph serialization and fingerprints retain
their meaning. The frontend checks that canonical task effects match their owning
contract, resolves the bound operands, and requires coverage of every effect of a
changing task. Unknown, ambiguous, dynamic and omitted bindings remain unresolved.
A declaration for an unrelated parameter or nested object cannot cover an effect.
Shared sources permit reads; writes to the same address, including aliased input
and output operands, are violations. PROVISION resource/stack alternatives and
VERIFY address alternatives must be unambiguous.

The existing authority responsibility catalog records the actual new frontend
caller and API. No second capability switch or semantic authority was introduced.
The effect contract remains in the semantic kernel; address interpretation and
snapshot assessment remain in the outcome frontend.

## Connected planning behavior

The planning worker entrypoint now supports independent preflight of its retained
selection after a completed independent goal review. The host recovers and checks
the exact candidate, compiler distribution, original discovery, goal requirements
and fidelity receipt. The installed compiler first admits the precondition contract;
missing effect coverage blocks before observation. An operator-configured bounded
observer process then receives only selected addresses and assessment pins. Expected
facts, owner and instance IDs are withheld. Its exact signed output is assessed by
the compiler using the current clock.

A completed preflight receipt binds the candidate, fidelity review, compiler
assessment and captured artifacts. Recovery verifies retained evidence and compares
expiry with the current host clock without repeating a model, observer or compiler
process. Expired evidence returns unresolved while historical receipts stay intact.
Changed inputs or output evidence invalidate recovery. Incomplete directories never
silently retry; a fresh assessment uses a new explicit directory and independent
assessment pins. Neither fidelity review nor preflight authorizes execution.

## Independent falsification and validation

- Frontend regressions cover omitted destinations, shared sources, changed target
  generation, aliased read/write operands, unrelated parameters, nested objects,
  ambiguous alternatives and the existing signature/freshness/identity failures.
- The data-copy conformance fixture now contains two real literal operands with a
  shared READ source and managed UPSERT destination. All seven existing conformance
  vectors continue to run across infrastructure, data and credential domains.
- The installed observer proof checks 27 cases against independently initialized
  local JSON/SQLite state. Ownership transfer, same-address generation replacement
  and state mutation fail unchanged expectations; missing and invalid evidence
  remain unresolved.
- Nine connected installed cases run through worker selection, independent goal
  review and preflight. They cover valid shared input, destination owner/instance/
  state drift, omitted destination, missing/stale evidence, process-free recovery
  and expiry of a genuine retained compiler assessment. Expiry uses a controlled
  host clock; production preflight exposes no clock override. The worker peer is
  scripted and explicitly not evidence of live model reasoning.
- Twelve host tests exercise process boundaries, independent-expectation isolation,
  immutable receipts, current expiry and clock rollback, partial-output recovery,
  observer failure, tampering and changes during observation. All 83 worker tests
  pass locally.
- The complete local Python tooling run executes 495 tests: 483 pass and 12 existing
  Unix-socket service tests encounter the environment's AF_UNIX restriction. All
  495 remain enabled in GitHub CI.
- The first broad JVM run exposed four failures from the missing actual caller in
  the responsibility catalog, including its dependent conformance gates. The
  catalog is corrected; no gate, assertion or test budget is weakened.

Local verification passes all 238 frontend and 80 CLI tests, 19 kernel tests, the
16 tests in the four catalog-dependent suites after correction, and all 326
installed conformance checks. Product isolation passes 102 tests plus installation
and ZIP relocation checks after physical source deletion. The final focused
precondition suite also passes the explicit stack-address and ambiguous-binding
cases. No production test fixture is added to the distribution resource inventory.

Exact-head/merge-candidate CI evidence is recorded against the final commit in the PR. Both full CI jobs run
the connected proof through the existing installed-worker verification entrypoint.

## Scope and acceptance boundary

This implements checks of supported canonical effects on existing literal resources.
The supported operand bindings and unresolved cases are listed in
[the operator contract](../../docs/OPERATION_PRECONDITIONS.md). This is not physical
resource discovery from arbitrary stack descriptions or a claim about unspecified
provider side effects. Dynamic/newly allocated addresses, resource locking and
atomic snapshots remain outside this contract. A deployed observer supplies trusted
assertions; cryptographic authenticity does not establish remote truth.

A real executor must collect and enforce current evidence at its own use boundary.
Live-model portfolio acceptance, deployed-observer commissioning and external
execution enforcement remain separate roadmap gates. AI-A3, AR-06 and AR-07 remain
open; this correction does not claim completion of integrated system automation.
