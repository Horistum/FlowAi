# Horistum integrated language integrity acceptance

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

The review found a concrete diagnostic mismatch: `FlowYamlException` inherited
directly from `RuntimeException`, so malformed YAML reached the CLI's internal
error branch, while the corresponding JSON parser exception already represented
invalid input. It now inherits `IllegalArgumentException`, preserving its public
class, message and cause. Malformed YAML is reported as `CLI_INVALID_INPUT` with
exit 2; semantic integrity rejection retains `CLI_INTEGRITY_BLOCKED` with exit 4.
Neither path publishes planning or target artifacts. No general CLI argument or
artifact-publication redesign is included.

## Candidate validation and lifecycle

The active work package selects F and accepts E from the byte-pinned predecessor
receipt. Historical A through E fixtures and regression identities remain live.
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
