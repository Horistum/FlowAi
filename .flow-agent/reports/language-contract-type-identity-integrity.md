# Horistum language and contract integrity

## Accepted activation boundary

This transition accepts the completed compiler-module boundary (AR-03/F-10) and
independently activates language, contract, type and identity integrity (AR-04).
That activation-only commit did not implement parser corrections. It is preserved as
the parent of the AR-04A implementation, not rewritten or reused as later validation.

The AR-04 activation HEAD `7974f0a2480ccb0f15e3ae3eaecb837b8b1f4558` and synthetic
merge `6e61c8d37c70440b5d4285e75c76bb7fc76d0413` passed Flow CI #3262
(`34478865620`), each with 1,544 Kotlin tests and 242 conformance checks. The bounded
AR-04 slices cover duplicate declarations, schema types/defaults, system identity,
lossless semantic identity, strict public loaders and integrated acceptance. They
belong to the existing recovery roadmap, not a second competing roadmap.

## Accepted predecessor

PR #177 implementation HEAD `a9e63289bef71293d59cdc4b9a48b47cd8aff9ed` and its
original synthetic merge `298a0409a8c0b2570d9a54573b973b7c6172abed` passed Flow CI
#3259 (`34358811924`). The actual merge is
`0a863eb10f60a64945f9657b0fcf090714ab543a`, tree
`084e5cc4229f60bad01e5c036f249dad50816810`. It independently passed Flow CI #3261
(`34370712591`), full job `102534450799` and isolation job `102530970478`.

The immutable acceptance report is `.flow-agent/evidence/compiler-module-acceptance.json`.
Its recorded SHA-256 is `4e16d5d516b24c977f6d5f30915c1f1d6d7d7b5d35a0c92fdc95dfb8c0804b48`.
It records 1,512 identical Kotlin test identities in the accepted complete runs,
zero failures/errors/skips, 242 conformance checks, 151 Python tests on main and
all four physical module proofs.

## Completed AR-04A boundary

AR-04A rejects duplicate action/approval arguments, safety declarations,
transform filters and selected fields, aggregate fields, match singleton branches,
global error handlers, system fields, input modifiers and decoded map keys before
an AST can lose the first declaration. Both original positions and the owning path
are reported through `FLOW_DUPLICATE_DECLARATION`. Additive blocks and ordered rule
lists remain legal.

PR #178 implementation HEAD `5d19cef68c0cf84c26a9bfd4004c5d7579ec43c0`
and synthetic merge `eca3cc2976b4eb8fd29ac37114ace08af1efc719` passed Flow CI
#3265 (`34493567009`). Exact-HEAD job `102929485718` and synthetic-merge job
`102929485758` each ran 1,590 unique Kotlin tests with zero failures/errors/skips,
246 installed conformance checks and the same source tree
`54bcfd11fc641bb1357b842af931cac841cee436`. Tooling reported 151 Python tests.
The accepted PR was merged as `9053658838dd7c06622717a34007c67c5cb1c38c`.

AR-04A therefore becomes an immutable predecessor slice for AR-04B. Its success
cannot be reused as AR-04B validation, and the milestone-wide AR-04 completion
boundary remains open.

## Implemented AR-04B type boundary

AR-04B replaces `SchemaField.type: String` with a closed `SchemaType` enum for the
module contract vocabulary: `any`, `text`, `number`, `boolean`, `list`, `map`,
`secret`, `duration`, `artifact`, `json` and `yaml`. The public Kotlin contract no
longer exposes a String-based `SchemaField` constructor. Descriptor strings cross
exactly one wire boundary in `SchemaType.fromWireName`; unsupported values fail in
the canonical module loader before decode or planning. Existing undocumented aliases
such as `string`, `int`, `bool`, `array` and `object` are not preserved.

`SchemaTypeCompatibility` is the single module-schema compatibility authority.
Descriptor defaults, Flow expressions and Intent values are first classified into
semantic value kinds and then checked against that authority. This removes the old
Flow and Intent `else -> true` fallbacks and removes Intent-only number/boolean string
coercion. A quoted `"12"` is text, not a module `number`; a native numeric value is
accepted consistently from both frontends. Runtime references remain dynamic where
the compiler cannot prove their concrete value type, preserving existing reference
semantics while literal mismatches fail early.

The canonical descriptor loader now rejects unknown schema wire types, explicit null
defaults that cannot be represented distinctly from an absent default, unsupported
nested default value shapes and defaults whose semantic kind does not satisfy the
declared `SchemaType`. Programmatic `SchemaField` construction validates non-null
defaults through the same authority. The YAML decoder is no longer permitted to
manufacture a free-form type after canonical validation.

Regression coverage includes public-loader rejection of an unknown type, mismatched
and explicit-null defaults, preservation of a valid numeric default, programmatic
default validation and paired Flow/Intent tests proving the same wrong text value is
rejected for `number` while native numbers are accepted. Live lifecycle tests bind
AR-04B selection to the exact accepted AR-04A receipt and reject borrowed or future
milestone-wide success records.

## Scope and lifecycle

AR-04 remains active. AR-04A through E have independently accepted receipts.
AR-04F is the selected integrated acceptance candidate. F-07, F-12, F-13, F-14
and F-21 remain open until that candidate's independent acceptance; implementation
alone does not close a finding. AR-05/06/07 remain planned, EF-09 remains paused
and F-20 remains AR-07-owned.

Product presentation is Horistum. Existing Flow technical identities, public
versions, canonical graph semantics, target support and CI check identities remain
unchanged.

## Validation and CI cost

Development keeps the existing Flow CI topology. No product workflow, required-check identity,
timeout, cancellation, cache policy, extra matrix or automatic offline-portability
run is added by these slices. The candidate must pass the existing exact-HEAD and synthetic
merge compile/test/conformance checks plus physical module isolation. Final run IDs,
counts and review outcome belong in PR metadata only after GitHub has actually
produced them; this report deliberately does not predict a green future run.


## Accepted schema predecessor and system-contract identity

Schema-integrity PR #179 was merged as `dc3cfab1ec2e16ff315f6af0b8f3a7c1ef62707c`.
Its final head `e641753e48ff3465cf2736ae56a70ac71c83ff74` passed Flow CI run
`34572383170`, including exact-head job `103179363019`, merge-candidate job
`103179362952` and physical isolation `103177235461`. Both inspected test archives
contain the same 1,600 distinct tests in 281 suites with no failures, errors or skips;
both installed conformance logs report 246 passing checks. The immutable receipt is
`.flow-agent/evidence/schema-integrity-acceptance.json`. This accepts B, not C.

The historical system-contract implementation started from merged correction main
`e1574def2dbe8ef82dd2903eb551bd4c61807770`. Its separate post-merge Flow CI run
`35171357137` passed source isolation and full compile/test/conformance; the
synthetic-merge job was correctly skipped for a push event. No CI workflow is changed.

AR-04C introduces one loader-independent `ModuleCatalogIndex`. A composed catalog
has globally unique module and system-type names, matching identity keys and nonblank
module versions. Duplicate system types are rejected even if their schemas happen
to match. Diagnostic ordering is deterministic; renaming descriptor files or changing
registration order cannot choose a different owner. Qualified multi-version type
selection is not invented as a fallback.

Compiler, validator, planner, report and adapter composition capture the declarations
once. The index ignores a provider's custom lookup override and detaches identity-bearing
collections so subsequent source mutation cannot replace an owner or its schema map.
Intent imports use the actual declaring module and version, not a system type label
or hardcoded version 1.0. Unknown types stay explicit; they do not invent module imports.
This is not a general deep-freeze of arbitrary schema defaults or an AR-04D identity redesign.

Regression evidence covers descriptor loaders, direct registry construction, programmatic
catalogs, dormant conflicts, both registration orders, three-module permutations, source
and returned-collection mutations, compiler/validator/planner/report/adapter entry points,
and external owner/version preservation. Three executable conformance probes independently
exercise ambiguity rejection, owner preservation and snapshot stability. Source-rejection
probes now distinguish catalog construction from per-source lookup: a syntax failure must
still stop before any additional lookup, planning or artifact construction.

The existing lifecycle checker accepts the pinned B receipt and keeps all milestone-wide
boundaries pending. C requires its own actual exact-head and merge-candidate CI before merge.
No F-13 closure, target support promotion, AR-05 activation or EF-09 resumption is asserted.


Integration review retained the existing compiler-axis guard for the former raw-catalog
proposal-review spelling and added the exact captured-catalog spelling. A negative
regression rejects either spelling from an unlisted caller. Metadata-copy tests now
include the newly pinned predecessor receipt before exercising byte-tampering rejection.
No conformance or test assertion was disabled to accommodate the new implementation.


## Accepted system identity and selected semantic identity

System identity PR #184 was merged as `1d125c154394cd8d6e1083eddd20c6b217a89bb4`,
source tree `d86118179709fde20d8c91b1260b51c52c1116d6`. Its own exact-head and
synthetic-merge Flow CI `35175006081` and actual post-merge Flow CI `35176517700`
passed. All three independently inspected archives contain the same 1,665 test
identities in 293 suites, no failures/errors/skips, and the same ordered 249
conformance checks. Every input hash in the PR and post-merge physical isolation
proofs matches that source tree. Archive checksums, actual job IDs and counts are
pinned in `.flow-agent/evidence/system-identity-acceptance.json`; its byte hash is
`1f304dbfd544df93ea906086d935cff0c41067f9649f7d76cf8a8ed78a51b075`.
This accepts C only. A/B receipt bytes and their prior acceptance fields are unchanged.

The maintainer explicitly selected AR-04D. `SemanticId` now owns exact authored
identity and is used by the compiler's step index and producer keys. One reversible
wire-segment codec is used by real lowering evidence, including strict malformed-escape
rejection. Descriptions are independent display values. Existing noncolliding Intent
result spellings remain compatible; a conflicting declaration set is rejected before
AST construction with path-aware identity diagnostics, not repaired with a suffix.
Rejected reports retain authored structure but publish no derived control/topology or
binding evidence for ambiguous owners. No rejected report grants compilation authority.

The compiler no longer treats a hyphen and an underscore as implicit reference aliases.
Ordering resolves exact authored step IDs through the checked result-name index, while
value references use exact declared symbols. Adapter lowering checks the namespaces
that would otherwise lose distinctions through case folding or sanitization. Structural
parallel wrappers derive from their owning node and ordinal, never their display label.
Renderer checks cover inputs, jobs, steps, payload keys and secret environment names;
Jenkins image variables and Tekton workspaces retain their separate namespace checks.
Workspace identities remain raw until checked and rendered. GitHub workspace composition
also rejects competing job names and generated-step collisions without changing its
bounded capability claim. Approval and environment relationships use exact dependencies.

The patch contains positive and negative compiler, frontend, producer, serialization,
projection and lifecycle tests, including a finite sweep of non-surrogate UTF-16 code
units. Four executable conformance probes test wire preservation, early collision
rejection, exact producer references and derived-name collision rejection. The complete
current candidate must still pass its own offline and exact-head/synthetic-merge CI.
Validation run IDs and final outcomes are recorded in the pull request only after
execution; this committed report does not certify its own future commit.

No public artifact layout, public contract version, target support claim, execution
engine, compatibility retirement or successor milestone is changed. The scope is the
identity invariant, not full grammar validation for every target-native name or a new
runtime implementation of generic symbolic bindings. E/F and whole-AR-04 completion
remain pending, and the existing failure, path, merge and workflow semantics remain
owned by their established authorities.


Integration review distinguished node identity from whole-program payload identity. Bound-action
source labels remain metadata. The historical unbound standard action also carries descriptions
as action parameters; this slice preserves that payload rather than filtering it from the graph
digest. Separate tests exercise stable node identity and retained parameter sensitivity. The old
AST duplicate-output regression now checks early Intent rejection and independently injects a
duplicate into a previously valid AST, retaining its original DUPLICATE_RESULT assertion.

## Accepted semantic identity and selected strict loaders

PR #185 and its post-merge Flow CI 35193433327 were independently inspected:
1,707 identical test identities and 253 conformance checks pass on exact head,
synthetic merge and actual main. The new byte-pinned receipt is
`.flow-agent/evidence/semantic-identity-acceptance.json`.

The maintainer-selected successor is AR-04E. Every public YAML entry point is
strict; JSON contract readers now share duplicate, scalar, trailing-content and
complexity rules. See `docs/modules/STRICT_CONTRACT_LOADING.md` for the explicit
policy and `.flow-agent/reports/strict-public-contract-loaders.md` for the branch
review and validation scope. Existing contract vocabulary owners remain in place.
E requires its own candidate CI. AR-04F and whole-milestone closure remain pending.

## Current integrated acceptance candidate

PR #186 and post-merge Flow CI 35705768665 have been independently inspected:
1,722 identical test identities, 257 ordered conformance checks and all four
physical isolation proofs pass on the accepted strict-loader source tree.
The receipt is `.flow-agent/evidence/strict-loader-acceptance.json`.
AR-04F now composes the five finding families through real public frontends,
requires positive counterparts for negative probes, and checks that failures
cannot publish accepted plans or CLI artifacts. See
`integrated-language-integrity-acceptance.md` for the current scope and evidence.
Earlier sections above describe historical slice decisions, not the current selection.
