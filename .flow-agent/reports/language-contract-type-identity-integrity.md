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

AR-04 remains active. AR-04B is the selected implementation slice and AR-04C is next.
F-07, F-12, F-13, F-14 and F-21 remain open until the milestone's integrated
acceptance; no finding is closed merely because source code exists on a candidate
branch. AR-04C/D/E behavior is intentionally not implemented here. AR-05/06/07 remain
planned, EF-09 remains paused and F-20 remains AR-07-owned.

Product presentation is Horistum. Existing Flow technical identities, public
versions, canonical graph semantics, target support and CI check identities remain
unchanged.

## Validation and CI cost

Development keeps the existing Flow CI topology. No workflow, required-check identity,
timeout, cancellation, cache policy, extra matrix or automatic offline-portability
run is added by AR-04B. The candidate must pass the existing exact-HEAD and synthetic
merge compile/test/conformance checks plus physical module isolation. Final run IDs,
counts and review outcome belong in PR metadata only after GitHub has actually
produced them; this report deliberately does not predict a green future run.
