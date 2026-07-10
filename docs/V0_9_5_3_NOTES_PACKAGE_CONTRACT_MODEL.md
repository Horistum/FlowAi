# v0.9.5.3 Notes Package Contract Model

## Purpose

v0.9.5.3 introduces the first explicit contract model for notes packages.

This step defines what a notes package may declare before later correction items move semantic actions, materialization, target honesty, safety policy and trigger behavior into notes-driven layers.

The model is intentionally a contract boundary. It is not a loader framework, plugin lifecycle, SDK entrypoint, runtime executor, target renderer, target-specific public DSL or shell projection path.

## Contract kinds

A notes package has exactly one primary kind:

- `DOMAIN`: target-neutral domain meaning
- `CAPABILITY`: target-neutral capability vocabulary
- `SAFETY`: safety and approval policy vocabulary
- `RUNTIME`: runtime requirement vocabulary without execution ownership
- `TARGET`: declared target capability vocabulary
- `PROJECTION`: projection rule vocabulary
- `CONFORMANCE`: evidence and verification vocabulary

Each kind owns only its own declarations. For example, capability notes may not declare projection rules, and projection notes may not own runtime requirements.

## Baseline contracts

The baseline contract set contains:

- `flow.domain.core`
- `flow.capability.core`
- `flow.safety.core`
- `flow.runtime.core`
- `flow.conformance.core`

These contracts are target-neutral and deliberately avoid concrete target names such as Jenkins, GitHub Actions or Tekton. Concrete targets belong in target and projection notes, not in Flow semantic truth.

## Validation rules

The validator enforces:

- package id format
- package version presence
- package description presence
- declarations for the package kind
- architectural boundaries
- unique package ids
- valid non-self dependencies with version constraints
- no runtime executor, SDK, plugin lifecycle, public DSL, shell projection or command execution claims
- no target capability declarations outside target notes
- no projection rule declarations outside projection notes
- no runtime ownership inside projection notes

## Correction result

v0.9.5.3 creates the contract surface that the later correction track can build on:

- v0.9.5.4 Universal Semantic Action Graph
- v0.9.5.5 Materialization Negotiation
- v0.9.5.6 No-Shell Target Projection
- v0.9.5.7 Target Registry Honesty
- v0.9.5.8 Policy-Driven Safety and Environment Classification
- v0.9.5.9 Trigger and Schedule Notes
- v0.9.5.10 Conformance Honesty Gates

## Versioning boundary

- Current package version remains `0.9.4`.
- Active public standard version remains `0.7.6`.
- This is roadmap correction work inside the v0.9.5.x correction track.
- No artifact schema version is changed.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change is introduced.
