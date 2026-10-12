# AR-06T: Parallel Branch Contract Preservation

## Purpose and boundary

The canonical graph already carries parallel branches and failFast. Target
lowering retained wrappers but the manifest boundary accepted flattened bodies,
detached wrappers and missing or malformed failure policies. Presentation names
also conflated an absent label with an authored ordinal-looking label. This slice
preserves and validates that existing meaning before native parallel certification.

## Production and negative evidence

Parallel metadata now records exact branch count and failFast. Each wrapper keeps
its authored ordinal and optional label separately from its presentation name.
Labels remain presentation: duplicate and whitespace-bearing labels are retained, and owner plus
ordinal still identifies each wrapper. Body order and nesting remain unchanged.
The contract rejects flattened, omitted, reordered or misplaced wrappers, malformed
counts, ordinals and booleans, and independent execution bindings on wrappers.
Empty groups and branches remain valid diagnostic structures; executable admission
still requires provider-owned structural evidence, which parallel does not have.

Frontend-to-manifest regressions cover all three reference providers, optional and
duplicate labels, independent nested policies, empty bodies, collision rejection,
malformed manifests, blank-label rejection and unchanged unsupported execution. Lifecycle regressions keep
historical receipts and finding ownership enforced. No public syntax, versioned
manifest field, canonical meaning or generic target support changes.

## Validation and predecessor

Local 162 Python tests, Flow Agent validation, context generation and whitespace
checks passed before the release-state update. The pinned Kotlin 2.4.10 plugin is
absent from the offline cache; the local Gradle attempt stopped before compilation.
Fresh exact-head and synthetic-merge compilation, tests, ordered conformance,
isolation, every existing native runtime producer and the portfolio remain required.
Final candidate receipts will be attached to the PR without preclaiming CI success
inside the committed candidate.

PR #216 merged as 8e9a1f217138db3f364bc9210eca341aa0892486 with the same tree as its
validated PR revisions. Those revisions passed 2095 tests and 274 ordered checks
each. All 21 assessments, 65 signatures and 17 archive digests were independently
verified. The immutable baseline records those receipts, separate actual-main
workflow observations and the selection transition.

## Limits and next work

This prerequisite does not certify native parallel scheduling, cancellation,
failure aggregation, workspace isolation or continuation. Manifest shape validation
is not proof of canonical equivalence; compiler authorization and artifact receipts
remain responsible for exact source and artifact binding. A later bounded adapter
slice must independently observe parallel behavior before granting execution.
AR-06 remains active with only A/B formally accepted. AR-07 stays planned, EF-09
paused and F-20 AR-07-owned; no support promotion or portability claim is made.
