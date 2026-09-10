# Horistum language and contract integrity

## Activation boundary

This transition accepts the completed compiler-module boundary (AR-03/F-10) and
independently activates language, contract, type and identity integrity (AR-04).
It does not implement a parser correction, change schema acceptance, migrate
semantic identities, or claim that any AR-04 finding is closed.

AR-04A selects duplicate authored declarations and stable source diagnostics.
Its implementation starts only after this activation's exact HEAD and synthetic
merge candidate pass. The following bounded slices cover schema types/defaults,
system identity, lossless semantic identity, strict public loaders and integrated
acceptance. They belong to the existing eight-item recovery roadmap, not a second
competing product roadmap.

## Accepted predecessor

PR #177 implementation HEAD `a9e63289bef71293d59cdc4b9a48b47cd8aff9ed` and its
original synthetic merge `298a0409a8c0b2570d9a54573b973b7c6172abed` passed Flow CI
#3259 (`34358811924`). The actual merge, including the separately merged Horistum
documentation, is `0a863eb10f60a64945f9657b0fcf090714ab543a`, tree
`084e5cc4229f60bad01e5c036f249dad50816810`. It independently passed Flow CI #3261
(`34370712591`), full job `102534450799` and isolation job `102530970478`.
A push receipt is not misrepresented as a second PR merge-candidate job.

The immutable acceptance report is `.flow-agent/evidence/compiler-module-acceptance.json`.
Its recorded SHA-256 is `4e16d5d516b24c977f6d5f30915c1f1d6d7d7b5d35a0c92fdc95dfb8c0804b48`.
It documents independently inspected artifact ZIPs and 1,512 identical Kotlin
test identities in all three complete runs, zero failures/errors/skips, 242
conformance checks, 151 Python tests on main and all four actual physical module
proofs. Its historical scope statements apply to the verification it records.
It does not certify a later activation commit or claim that JAR binaries were
independently downloaded.

## Enforced transition

The existing live workflow-semantics lifecycle gate now validates the successor
without rewriting completed workflow semantics. Module acceptance binds the
integrated PR and actual-main receipts to the reviewed evidence bytes, successful
test identities, conformance and physical proofs. Missing, malformed, mismatched
or reused records reject completion rather than defaulting to success.

The language-integrity lifecycle requires the exact accepted-main baseline,
consistent recovery/release pointers, the five original finding owners and a
selected but unimplemented first slice. Pending activation cannot carry a future
success receipt, even when copied from a genuinely successful predecessor run.
Historical candidate regressions remain positive/negative fixtures instead of
being deleted or changed into assertions that only accept the newest state.

Only F-10 closes. F-20 remains contained with an explicit AR-07 retirement owner.
AR-05/06/07 remain planned, EF-09 remains paused, and terminal global/Core roadmap
state is unchanged. Product branding is Horistum; all existing Flow technical
identities, public versions and target support claims remain unchanged.

## Validation and CI cost

Development uses restored, existing JDK 25 and offline Gradle inputs. No new
input-transfer workflow is required. Full local tests, installed conformance,
predecessor identity preservation and one final exact-HEAD/merge Flow CI are the
acceptance requirements. Actual results and immutable candidate IDs are added
to the PR only after execution; this document does not assert future CI success.

No workflow file, check identity, timeout, draft gating, cancellation policy or
cache policy changes. The relocated offline portability proof remains manual;
all four physical module proofs and both complete final validation jobs remain.
