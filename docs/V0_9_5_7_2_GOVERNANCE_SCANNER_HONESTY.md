# v0.9.5.7.2 Governance Scanner Honesty

## Purpose

v0.9.5.7.2 replaces text-only governance checks and source-word obfuscation with structural mechanism validation.

Architecture governance must detect forbidden API ownership, lifecycle hooks, runtime executors and projection mechanisms. It must not reject comments, diagnostics or legitimate target-native vocabulary merely because a word appears in source text.

## Changes

### Structural Kotlin source scanning

`ArchitectureGovernanceAnalyzer` now uses `KotlinSourceBoundaryScanner`.

The scanner removes comments, ordinary string literals, raw string literals and character literals before matching forbidden architecture symbols. A forbidden class, function, package or API symbol is still detected. A diagnostic message explaining why that symbol is forbidden is not treated as an implementation.

### Readable target vocabulary

Target renderers now use direct readable vocabulary for:

- `withCredentials`
- `credentialsId`
- `secrets`
- `secretKeyRef`

The character-code word builder has been removed. Target-native secret binding concepts are legitimate inside target renderer boundaries and no longer need to be hidden from repository scanners.

### Structured semantic validation

`SemanticActionGraphValidator` no longer scans descriptions and arbitrary attribute values for forbidden words.

It rejects raw runtime mechanisms through:

- forbidden declaration prefixes,
- raw payload field names,
- explicit representation fields.

Diagnostic descriptions may name a rejected mechanism without becoming the mechanism.

### Structured projection validation

`TargetProjectionPlanValidator` applies raw-runtime checks only to materializable target-native and notes-backed artifacts.

It rejects:

- raw payload fields such as `command`, `script`, `shell` or `run`,
- explicit raw runtime representation values,
- raw runtime notes references.

Adapter-boundary and review records may explain unresolved runtime mechanisms in normal language.

## Result

Governance now checks architectural structure instead of rewarding source obfuscation. The generated target output remains unchanged by the vocabulary cleanup.

The next repair item is `v0.9.5.7.3 Renderer Failure Semantics Unification`.
