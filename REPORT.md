# Flow Core Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed roadmap item: `0.9.7.2 Canonical Module and Notes Authority`
Next Core roadmap item: `0.9.7.3 Mandatory Materialization Authority`
Bounded closure item: `0.9.7.10 Bounded Semantic Closure Gate`

## Active direction

Core, implementation and conformance planning remain separate authorities. Existing executable reference evidence stays active as falsification feedback and does not define Core meaning.

## v0.9.7.2 outcome

- `modules/*.yaml` is the sole production authority for module capability and safety contracts.
- `ModuleRegistry` contains no hardcoded contract copies and never fills descriptor gaps from defaults.
- Every public module YAML entrypoint delegates to canonical structural validation.
- Strict loading rejects malformed sections, invalid booleans, duplicate identities, unknown fields and undeclared system types.
- Explicit approval and destructive safety requirements survive production loading.
- Core module descriptors reject implementation-specific implications.
- Unsupported retry and timeout details cannot remain as declarations that the model silently discards.
- Core notes packages load from one versioned manifest and strict package directory.
- Core notes reject implementation-owned fields, unknown dependencies, dependency cycles and malformed declarations.
- The canonical notes authority preserves the declared dependency chain through conformance evidence.
- The hardcoded notes baseline is removed; its compatibility facade delegates to the canonical loader.
- Legacy Flow specification tests were split into focused suites without removing assertions.
- Package, public standard and artifact contract versions remain unchanged.

## Roadmap order

1. `0.9.7.1 Governance Signal Integrity` — completed
2. `0.9.7.2 Canonical Module and Notes Authority` — completed
3. `0.9.7.3 Mandatory Materialization Authority` — next
4. `0.9.7.4 Universal Dependency and Continuity Contract`
5. `0.9.7.5 Canonical Intent Meaning`
6. `0.9.7.6 Universal Effect and State Transition Model`
7. `0.9.7.7 Universal Control and Policy Requirements`
8. `0.9.7.8 Abstract Execution Topology Model`
9. `0.9.7.9 Intent Lowering and Diagnostic Honesty`
10. `0.9.7.10 Bounded Semantic Closure Gate`

## Validation

Flow CI #1586 passed the implementation head, including Flow Agent checks, clean compilation, full tests, full conformance and executable reference evidence. The final metadata head must pass the same unmodified workflow before review completion.

## Architecture boundary

Flow remains a notes-driven universal automation language and standardization model. Core does not provide task execution, an SDK lifecycle, a plugin framework or concrete implementation authority.
