# Semantic identity and derived names

## Selected correction

This work implements the semantic-identity slice after accepted system-contract ownership.
A semantic identity is exact, case-sensitive authored data. A display label and a generated
Flow result symbol or target identifier are separate values. Neither stripping punctuation,
case folding, map insertion order nor a lookup fallback may resolve identity.

Existing unambiguous Intent result symbols retain their spelling (hyphens become underscores).
This is a checked projection, not an identity: distinct authored IDs that share that symbol
are rejected at Intent validation before constructing an AST. Source IDs and descriptions
remain separate in existing wire fields. No public field or version is renamed.

Flow references use exact declared binding names. An Intent result reference must name the
published result symbol or an explicitly declared output; there is no implicit punctuation
alias. Authored ordering dependencies continue to name the original step ID, resolved through
the checked identity index. The core does not derive target-specific names.

The shared wire-segment codec escapes `~` as `~0` and `/` as `~1`, preserving existing evidence
paths. Its decoder rejects malformed/noncanonical escapes rather than silently aliasing them.
Typed semantic IDs reject blank names and unpaired UTF-16 surrogates. Unicode normalization,
case folding and whitespace trimming are never identity operations.

Target projection and rendering check the complete relevant declaration namespace before
emitting lossy target spellings. A collision is an error, not a suffix allocation or a choice
of the first owner. Repeated references to the same identity are not duplicate declarations.
Human-readable descriptions cannot authorize dependencies or approval relationships.
Parallel branch wrapper IDs use their owning structural ID and ordinal, not a branch label.
Tekton workspaces retain raw names until the complete workspace inventory is checked;
repeated references to exactly the same workspace deliberately share one declaration.
Jenkins image variables and secret environment variables have separately checked namespaces.
GitHub workspace composition checks additional generated step IDs before publishing its jobs.

## Failure contract

For authored step IDs `audit-step` and `audit_step`, both would project to result symbol
`audit_step`. The compiler returns `INTENT_SYMBOL_COLLISION` at `INTENT_VALIDATION`,
including the conflicting declaration paths, with no AST or compilation authorization.
The caller must choose distinct IDs explicitly. No first-writer rule or automatic suffix
rewrites the authored program. This also applies to input, system and declared-output
collisions in one workflow. Independent workflows keep their own result/output scopes;
step IDs retain their existing document-wide uniqueness contract.

An unambiguous step ID `audit-step` remains that exact `sourceId`. Its existing result
symbol is `audit_step`; `requires` refers to `audit-step`, while a value reference refers
to `audit_step` or an explicit output. The textual Flow identifier grammar is unchanged.
A description edit changes source evidence, not the step's identity or dependency owner.
This is not a claim that every field named `description` is excluded from program meaning.
Bound-action source descriptions are metadata and do not change the graph digest. The existing
unbound `standard.execute` projection also carries that text as an action parameter; its payload
and therefore its whole-program digest still change. Node identity remains stable in both cases.
This slice does not erase action parameters or redefine the canonical graph digest contract.

New public Intent diagnostics are `INVALID_SEMANTIC_ID`, `DUPLICATE_INTENT_INPUT`,
`DUPLICATE_INTENT_SYSTEM`, `DUPLICATE_INTENT_TRIGGER`, `DUPLICATE_INTENT_POLICY`,
`INTENT_SYMBOL_COLLISION` and `INTENT_SYSTEM_COLLISION`. Previously published duplicate
step, output and workflow codes remain in use. Invalid-identity reports preserve authored
structure, but their binding/control/topology collections are empty because competing
owners cannot support derived evidence. Such reports are explicitly invalid, not a
partial successful compilation.

Projection collisions raise `IdentityCollisionException`, an `IllegalArgumentException`,
before a concrete artifact is returned. This scope checks name collisions; it does not
claim full target grammar conformance or certify arbitrary external symbolic bindings.

## Scope boundaries

This correction does not redesign control flow, authorize an additional target capability,
change compatibility retirement, harden all public loaders, or complete the language-integrity
milestone. Historical evidence is retained; current validation is recorded only after execution.
