# Horistum integrated language integrity acceptance

## Accepted implementation and next validation boundary

Corrected AR-04F implementation is accepted from PR #188. Flow CI #3298
(`36971285367`) passed exact head `fe59c65825a60ed918361d14adfb21350194b72f`
and synthetic merge `2c8dc365123d20db5b1b15dc307ae2dbd63543a3`. Flow CI #3299
(`36973061505`) independently passed actual merged main
`1d88151f0f3d6ccc9185522b967934724cc4d5b4`. All three revisions share tree
`49bcdf2513379b0d8eb0f2a6486a8e578f77900c`.

All three downloaded JUnit archives contain the same 1,733 unique tests in
305 suites, with zero failures, errors or skips. All 1,728 predecessor test
identities remain, with five additions. Standalone conformance preserves the
same ordered 264 passing checks. PR and post-merge physical isolation proofs
match every listed input hash against the accepted tree: kernel 19, compiler
95, adapter 215 and product 284 inputs. Archive IDs, SHA-256 fingerprints,
job identities, merge parents and test inventories are recorded in
`.flow-agent/evidence/integrated-language-implementation-acceptance.json`.

The lifecycle accepts F and advances only `implementationBoundary`. It verifies
the immutable evidence document before parsing it, then checks every slice and
boundary receipt field against that authenticated document. Missing documents,
changed bytes, malformed documents, substituted predecessor receipts and
self-declared replacement digests fail closed. Historical A through E scenarios
retain their original pending milestone-wide implementation boundary.

This transition must pass its own exact-head and synthetic-merge Flow CI.
`validationBoundary` and `completionBoundary` remain pending; the implementation
receipt cannot certify this new candidate or a future completion transition.
All five findings remain open until whole-AR-04 completion. AR-05 stays planned,
EF-09 stays paused and F-20 remains AR-07-owned.

## Accepted bounded correction: Intent source types

Inspection of main `b7e53bc16849ac0e59cbf5cf0e86c3d2696c2157` found a
remaining normalization defect after PR #187. Strict token parsing preserved
`null` and quoted text, but `IntentYamlLoader` then interpreted explicit null
as an absent field and converted quoted booleans into boolean values. For
example, `policies: null` became an empty policy list, `failure: null` became
the default failure policy and `stopOnError: "false"` became `false`.

`AR-04F-INPUT-TYPES` corrects this map-to-model boundary in its existing owner.
Absent optional fields retain their documented defaults. Authored null at a
non-nullable field now reports `INTENT_FIELD_TYPE_MISMATCH` with its exact source
and path. Quoted booleans report `INVALID_INTENT_BOOLEAN`. Explicitly nullable
metadata remains accepted, and null inside `IntentValue` still becomes
`IntentNull`, including defaults and nested values. The published Intent 2.0
schema already rejects the corrected malformed forms; no schema or version
change is needed. Scalar-to-list source shorthand is outside this correction.

The regression matrix covers 35 non-nullable paths through both map entry
points and the legacy and adapter-facing YAML/JSON text and file loaders.
It also covers four boolean fields, omitted and empty counterparts, nullable
metadata, and nested null data. Installed conformance compiles positive
counterparts and rejects null/coercion mutants at source capture, before AST
construction. The existing malformed-document probe now checks the expected
exception type and, for model validation, exact code, path and source.
CLI regressions prove rejection publishes no new artifacts and preserves every
byte of a previously accepted output directory.

Historical PR #187 Flow CI `35804817305` and post-merge Flow CI `35807790103`
both succeeded. Those runs establish the inspected baseline, not validation
of this correction. The archived PR exact-head JUnit report was inspected:
1,728 unique tests in 304 suites, with zero failures, errors or skips. Its
archive SHA-256 is
`3cf5c98ff5e676de3910d91690e9a73740383813248827d7a28c6938382bf852`.
The correction subsequently passed independent PR #188 and post-merge validation
as recorded above. Complete AR-04 finding closure remains pending.

## Baseline and architectural decision

The inspected main is `f1071f389111e1c406a82c46ead45187f2fc3948`, complete tree
`13e2125744281e9e69bcbdb399765f67e2ddeaa9`, after strict-loader PR #186.
AR-04F is the next slice of the existing AR-04 milestone. This change composes
the already implemented A through E invariants through public compiler entry
points and the installed conformance runner. It does not introduce a second
compiler, a runtime executor or target-owned semantic meaning.

## Independently accepted strict-loader predecessor

PR Flow CI #3291 (`35670644502`) passed exact head
`ca502fbfdbe07239df94840da10b373b7e3fd05e` and synthetic merge
`586dff9daf3b643ac3b7a987c8c03d3d4c4ad091`, jobs `106567432474` and
`106567432535`. Actual main passed Flow CI #3292 (`35705768665`), job
`106676944077`. All three inspected JUnit archives contain the same 1,722
unique tests in 302 suites with zero failures, errors or skips. All three
installed conformance reports contain the same ordered 257 passing checks.

PR isolation job `106566207799` and post-merge isolation job `106674240608`
passed all four physical module proofs. Every listed input hash was compared
with the inspected source tree. The downloaded archives, test identities and
conformance identities are fingerprinted in
`.flow-agent/evidence/strict-loader-acceptance.json`. Identity hashes explicitly
declare their encoding; they are not compared across different encodings.
This receipt accepts E and cannot certify the new F candidate.

## Integrated evidence

| Finding | Public composition and negative counterpart |
| --- | --- |
| F-07 | Compile a real Flow file using a loaded external contract; duplicate system fields and action parameters must stop during parsing with both source occurrences and the owning path. Existing A probes retain the complete grammar inventory. |
| F-12 | Load numeric schema/defaults and compile valid authored numbers; reject unknown types, invalid/null defaults, and quoted numbers through descriptor text/file, Flow, YAML/JSON Intent and reviewed AI boundaries. |
| F-13 | Compile distinct external catalogs in both orders with equal graph identity; duplicate system owners fail at descriptor composition and direct compiler composition. |
| F-14 | Preserve exact step/dependency identity and actual module/version through YAML, JSON and reviewed AI; a colliding derived result symbol fails before AST construction. Existing D probes retain wire and adapter collision coverage. |
| F-21 | Compile positive YAML/JSON text and files; reject duplicate keys, unknown fields and trailing documents with source provenance. Existing E probes retain parser budgets and typed-reader coverage. |

Seven independent conformance checks cover these compositions, frontend parity
and authored-value types. Each rejection starts from a successful baseline and
checks the expected failure boundary. A rejected compiler result cannot expose
an accepted compilation unit for planning/materialization. Additional integration
tests exercise reuse after failures and the real CLI output directory boundary.

Fixture conversion explicitly preserves null-valued fields and verifies a map
round trip. The artifact serializer omits optional nulls, so using it unchanged
would silently remove an invalid authored `default: null` from the JSON mutant.
The exact conformance inventory includes all seven new checks, and historical
lifecycle scenarios explicitly reset F to planned when replaying earlier phases.

The review found a concrete diagnostic mismatch: `FlowYamlException` inherited
directly from `RuntimeException`, so malformed YAML reached the CLI's internal
error branch, while the corresponding JSON parser exception already represented
invalid input. It now inherits `IllegalArgumentException`, preserving its public
class, message and cause. Malformed YAML is reported as `CLI_INVALID_INPUT` with
exit 2; semantic integrity rejection retains `CLI_INTEGRITY_BLOCKED` with exit 4.
Neither path publishes planning or target artifacts. No general CLI argument or
artifact-publication redesign is included.

## Candidate validation and lifecycle

The active parent work package selects F and accepts its implementation from the
byte-pinned PR #188 and actual merged-main receipt. Historical A through E fixtures and regression identities remain live.
Roadmap and release notes no longer describe D as selected or E as unimplemented.
The candidate must pass its own exact-head and synthetic-merge compilation,
complete tests, standalone conformance, tooling and physical isolation in the
existing Flow CI. Local tooling validation is available; this container does not
have JDK 25 or the project's Gradle cache, so no local Kotlin/offline Gradle pass
is claimed. Actual candidate validation belongs in PR metadata after execution.

Whole-AR-04 completion remains pending until independent integrated evidence is
accepted. AR-05 remains the successor, without activation; EF-09 remains paused
and F-20 remains owned by AR-07. Package/schema versions, canonical semantics,
target support and required CI check identities are unchanged.
