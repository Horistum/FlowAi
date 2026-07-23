# Flow Core Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed roadmap item: `0.9.7.9 Intent Lowering and Diagnostic Honesty`
Next Core roadmap item: `0.9.7.10 Bounded Semantic Closure Gate`
Bounded closure item: `0.9.7.10 Bounded Semantic Closure Gate`

## Active direction

Core, implementation and conformance planning remain separate authorities. Existing executable reference evidence stays active as falsification feedback and does not define Core meaning.

## v0.9.7.9 outcome

- The public intent loader is strict, path-aware and source-aware.
- Unknown fields, malformed object or list shapes, invalid booleans, empty references and unsupported values fail closed with stable diagnostic codes.
- Equivalent block YAML, flow YAML, JSON and normalized map forms produce equivalent intent meaning and diagnostics.
- Canonical AST metadata preserves the source description, workflow identities, policy source, system purpose and failure declarations.
- Typed lowering evidence maps every accepted meaningful source path to its canonical AST target path.
- Declared step outputs survive into AST and ExecutionPlan outputs and dependency resolution.
- Structured input defaults retain their canonical expression while the legacy scalar projection remains compatibility-only.
- Explicit binding metadata such as `system`, `tool` and `engine` remains separate from semantic parameters and survives lowering.
- Unknown semantic parameters are errors unless the `CUSTOM` capability explicitly owns them.
- Multiple workflow flattening, `stopOnError=false` and unsupported policy types fail closed until a canonical representation exists.
- Mandatory materialization validates source-step coverage and lowering evidence before target provider invocation.
- Both reference snapshot bundles were regenerated through the production generator.
- The Jenkins checkout-and-build executable proof remains live.
- Package, public standard and artifact contract versions remain unchanged.

## Roadmap order

1. `0.9.7.1 Governance Signal Integrity` — completed
2. `0.9.7.2 Canonical Module and Notes Authority` — completed
3. `0.9.7.3 Mandatory Materialization Authority` — completed
4. `0.9.7.4 Universal Dependency and Continuity Contract` — completed
5. `0.9.7.5 Canonical Intent Meaning` — completed
6. `0.9.7.6 Universal Effect and State Transition Model` — completed
7. `0.9.7.7 Universal Control and Policy Requirements` — completed
8. `0.9.7.8 Abstract Execution Topology Model` — completed
9. `0.9.7.9 Intent Lowering and Diagnostic Honesty` — completed
10. `0.9.7.10 Bounded Semantic Closure Gate` — next

## Validation

Flow CI #1833, run `29973213486`, passed implementation head `784573fd67abc73bf18dc98d6ae055d7914f2420`, including Flow Agent checks, clean compilation, the full test suite, full conformance and canonical reference evidence. The final metadata head must pass the same unmodified workflow before review completion.

## Architecture boundary

Flow remains a notes-driven universal automation language and standardization model. Core does not provide task execution, an SDK lifecycle, a plugin framework, concrete implementation authority, hidden continuity transfer, implicit implementation binding, target-owned control meaning, action-only topology claims or permissive lossy intent lowering.
