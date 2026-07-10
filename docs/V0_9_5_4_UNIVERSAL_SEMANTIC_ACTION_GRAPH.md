# v0.9.5.4 Universal Semantic Action Graph

## Purpose

v0.9.5.4 introduces the first target-neutral semantic action graph model.

The graph describes what Flow means before runtime negotiation, target projection or materialization. It is deliberately not an executable plan, renderer model, SDK entrypoint, plugin lifecycle, shell projection or command representation.

## Added

- `SemanticActionKind`
- `SemanticActionEdgeKind`
- `SemanticActionNode`
- `SemanticActionEdge`
- `SemanticActionGraph`
- `SemanticActionGraphValidator`
- `StandardSemanticActionGraphs.baseline()`
- `FlowSemanticActionGraphTests`

## Contract relationship

Every semantic action node must bind to a declaration from a notes package:

- domain actions bind to domain notes
- capability actions bind to capability notes
- safety actions bind to safety notes
- runtime requirement actions bind to runtime notes
- target requirement actions bind to target notes
- projection requirement actions bind to projection notes
- conformance requirement actions bind to conformance notes

This keeps semantic meaning anchored in notes contracts instead of accidental target wording or renderer behavior.

## Validation rules

The validator enforces:

- graph id format
- node id format
- unique node ids
- at least one graph node
- known notes package references
- matching notes package kind for each node kind
- declared notes item binding for each node
- no command, shell, script, bash, cmd.exe or powershell vocabulary as universal action meaning
- known edge endpoints
- no self-referential edges
- acyclic graph structure

## Correction result

The project now has a universal semantic action graph boundary that can carry target-neutral automation meaning without treating commands, shell snippets or target renderers as the source of truth.

This does not claim materialization support. Materialization is intentionally left to v0.9.5.5.

## Versioning boundary

- Current package version remains `0.9.4`.
- Active public standard version remains `0.7.6`.
- This is roadmap correction work inside the v0.9.5.x correction track.
- No artifact schema version is changed.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion, target materialization or Flow syntax change is introduced.
