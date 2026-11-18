# AI-A3-S3.8 independent dispatch outcomes

PR #275 merged at `3966e23f496d6a9a58c1c01333c673821dc9f997`. Its external
runtime exchange attached fresh preconditions to a reviewed plan. The host could
record successful completion even when the destination held the wrong data. The
existing Kotlin outcomes command could assess signed observations but had no
connection to the planning dispatch that produced the effect.

## Implementation

An optional private dispatch policy declares the postconditions and independent
observer trust before runtime invocation. Admission passes the selected proposal
and declared obligations to the installed compiler with an empty bundle. It
requires resolved observation targets, verifies the published plan set and typed
source against independent planning review, and rejects shared observer keys.
The host rechecks admission before sending its assessment. The decision nonce
commits to the exact policy and trust bytes plus a fresh random salt. Existing
runtime consumers already acknowledge the digest covering that nonce.

The new review CLI has a read-only path to recover dispatch evidence. It derives
the final contract and observer request from the acknowledged decision and result.
The observer receives addresses and correlation pins. The reviewer retains private
expectations. The compiler authenticates the supplied bundle and assesses the
bound requirements using the existing outcome v1 semantics. Both complete and
partial review directories prohibit implicit recompilation. Dispatch receipts keep
their original meaning, including the status of uncertain invocations.

## Falsification

The installed proof executes a local copy and correlates the runtime decision with
a committed SQLite audit row. A separate process opens the database read-only,
reads the actual destination and signs its observations using an independent key.
The compiler satisfies the copy policy with the correct data. A runtime mutant
that records completion without copying produces a violated postcondition.
A missing audit event and replay of a different dispatch's signed bytes produce
unresolved results. An invalid selector stops before runtime launch. Recovery
with an unavailable JVM, tampered outcome policy or salt, denied dispatch and a
lost reply after commit exercise the public CLIs.

Worker regressions cover changes to the compiler inventory, plan or typed source,
exit-code disagreement, substituted input hashes, key aliasing, nonce commitments,
missing dispatch evidence and precision of authored numeric facts. The general
conformance suite remains at 326 checks. The installed dispatch proof runs via
the normal planning worker verification in both full CI jobs. Final revision
validation is recorded in the pull request.

## Remaining acceptance

The fixture establishes the observed result in its local database. A production
deployment must independently commission the runtime, correlate effects with its
provider's audit records and manage observer key custody. The live portfolio,
held-out rendering and AI-A3/AR-06/AR-07 acceptance gates remain open.

## Serialization defect found by repeating the installed proof

Two JVM processes published the same dependency relation with its computed
`continuity` and `blocking` fields in opposite orders. The installed proof stopped
at the exact plan-set comparison. The retained byte diff isolated the change to
those two fields. The artifact JSON mapper now specifies the wire order of
`PlanDependencyRelation` and leaves the compiler model free of serialization
dependencies. A regression checks all populated fields and forces an alternative
property-discovery default. Plan comparisons still use the exact published bytes.
