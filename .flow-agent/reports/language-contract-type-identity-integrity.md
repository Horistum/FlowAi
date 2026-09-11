# Horistum language and contract integrity

## Accepted activation boundary

This transition accepts the completed compiler-module boundary (AR-03/F-10) and
independently activates language, contract, type and identity integrity (AR-04).
That activation-only commit did not implement parser corrections. It is preserved as
the parent of the AR-04A implementation, not rewritten or reused as its validation.

AR-04A selects duplicate authored declarations and stable source diagnostics.
Its activation HEAD `7974f0a2480ccb0f15e3ae3eaecb837b8b1f4558` and synthetic
merge `6e61c8d37c70440b5d4285e75c76bb7fc76d0413` passed Flow CI #3262
(`34478865620`), each with 1,544 Kotlin tests and 242 conformance checks. The following bounded slices cover schema types/defaults,
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

## Implemented source declaration boundary

AR-04A now rejects duplicate action/approval arguments, safety declarations,
transform filters and selected fields, aggregate fields, match singleton branches,
global error handlers, system fields, input modifiers and decoded map keys before
an AST can lose the first declaration. Both original positions and the owning
path are reported through `FLOW_DUPLICATE_DECLARATION`. Interpolation diagnostics
map back to the original source without moving valid AST locations. Additive
blocks and ordered rule lists remain legal. See `docs/SOURCE_DECLARATION_INTEGRITY.md`.

Four live behavioral checks cover rejected singletons/maps, preserved scopes and
source rejection before module lookup. Product and installed-reference CLI tests
cover invalid-input exit 2 and absence of partial planning artifacts. All prior
activation regressions are retained against their exact historical work-package
fixture, and implementation-phase regressions exercise the new live transition.

The committed activation evidence is `.flow-agent/evidence/language-activation-acceptance.json`.
Its bytes are fingerprinted in the same read used to parse them. The real accepted
activation does not certify this later implementation; its own final HEAD/merge
results are recorded in PR metadata after CI completes. Whole-AR-04 boundaries
remain pending and the other five slices remain planned. F-07 awaits integrated
finding acceptance; no unrelated finding is closed and AR-04B is not implemented.
