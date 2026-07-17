# Flow Core Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Active architecture track: `v0.9.7 Universal Semantic Foundation`
Completed roadmap item: `0.9.7.2 Canonical Module and Notes Authority`
Next Core roadmap item: `0.9.7.3 Mandatory Materialization Authority`
Bounded closure item: `0.9.7.10 Bounded Semantic Closure Gate`

## Active direction

Core, implementation and conformance planning remain separate authorities. Existing executable reference evidence stays active as falsification feedback and does not define Core meaning.

## v0.9.7.2 outcome

- `modules/*.yaml` is the sole production authority for module capability and safety contracts.
- `ModuleRegistry` no longer contains hardcoded contract copies or fills missing modules from defaults.
- The legacy `includeDefaults` parameter remains source-compatible but does not change registry content.
- Strict loading rejects malformed section types, invalid booleans, duplicate identities and unknown effect fields.
- Explicit approval requirements survive into production `SafetyContract.requiresApproval` values.
- Core module descriptors reject implementation-specific implications.
- The ArgoCD module no longer carries implementation compatibility claims.
- Core notes packages load from one versioned manifest and strict package directory.
- Core notes reject implementation-owned kinds and fields, unknown dependencies and malformed declarations.
- The hardcoded notes baseline was removed; the compatibility facade delegates to the canonical loader.
- Behavioral tests cover partial descriptor sets, malformed data, approval preservation and ownership violations.
- Package, public standard and artifact contract versions remain unchanged.

## Revised v0.9.7 order

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

Flow CI #1557 passed Flow Agent checks, compilation, full tests, full conformance and executable reference evidence on the implementation head. The final metadata head must pass the same workflow before review completion.

## Architecture boundary

Flow remains a notes-driven universal automation language and standardization model. It does not execute workflows, expose an SDK lifecycle, own a plugin framework or allow concrete implementations to define universal meaning.
