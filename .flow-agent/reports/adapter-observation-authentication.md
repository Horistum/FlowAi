# AR-06C: authenticated adapter execution observations

AR-06B bound compiler inputs and rendered artifacts but accepted runner identity
as a caller assertion. AR-06C adds `AuthenticatedAdapterCertificationAdmission`
in `flow-adapter-evidence`. An assessment owner independently supplies trusted
Ed25519 public keys, a fresh challenge and an exact scenario/mutant/run/runner
inventory. Every observation must carry a valid, domain-separated signature over
all fields. Authentication completes before any external evidence resolution.
Bound admission then retains all existing compiler, occurrence, byte, runtime and
mutation checks. Flow neither signs observations nor executes workflows.

Mutable signature bytes, runtime lists and trust inputs are defensively copied.
The internal signing format uses bounded, length-prefixed strict UTF-8 with an
explicit nullable-mutant marker. It rejects malformed Unicode and limits both
individual statements and run counts. JDK Ed25519 provides cryptography; no new
dependency or concrete adapter coupling is introduced.

## Accepted predecessor

PR #199 merged into `4bea533762e3cff83192c7f9d8ba8244dd3c2598`. Final PR CI #3342
(37483446382) and actual-main push CI #3343 (37636042462) passed. Eight downloaded
archive hashes were independently checked. Exact head, synthetic merge and main
contain identical 1878-test/326-suite and 274-check inventories with no failures,
errors or skips. All 1859 preceding test identities and ordered conformance checks
remain. Both isolation archives match their source trees and all 762 input hashes;
their installed-product proofs retain 68 tests, 132 CLI invocations, 24 publication
manifests and 48 negative cases. This completes the final-archive inspection that
was blocked by the local environment during PR #199.

The immutable binding-acceptance receipt pins those observations. Lifecycle
validation accepts AR-06B, selects AR-06C and replays only checked predecessor
fields. Historical acceptance gates remain live. AR-06 findings stay open,
AR-07 stays planned, EF-09 stays paused and public versions remain unchanged.

## Verification and limits

Tests cover an independently generated Python Ed25519 vector; every signed field;
unknown/substituted keys; reused challenges; runner-slot substitution; omitted,
duplicate and additional runs; byte limits; immutable inputs; and signed runtime
or evidence failures. A generic external compiler probe runs under physical
adapter isolation. Integration fixtures use real compiler/rendering captures but
synthetic observations: they test admission, not real Jenkins/GitHub Actions runs.
No public contract changed, so standalone conformance vectors remain unchanged.

Local tooling and structural checks precede publication. Current-revision JVM
tests, conformance and source-deletion isolation require exact-head and synthetic
merge CI; final results belong in the PR rather than a self-attested receipt.

A signature establishes the configured runner as the statement source. It cannot
prove runner honesty, secure key custody, semantic adequacy or actual execution.
Callers own trusted composition, key provisioning and revocation, unpredictable
non-reused assessment challenges, persisted run authorization and bounded I/O.
There is no durable replay database in Flow. The unsigned and compiler-bound APIs
remain integrity-only APIs. No support/maturity publisher consumes this path yet.
The next work must provide adapter-owned behavior/mutant scenarios and external
execution on two materially different platforms before portability claims.
