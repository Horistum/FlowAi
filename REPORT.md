# Flow Core Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed Core roadmap item: `0.9.7.9 Intent Lowering and Diagnostic Honesty`
Completed correction item: `0.9.7.9.7 CLI Diagnostic and Release Honesty`
Next Core roadmap item: `0.9.7.10 Bounded Semantic Closure Gate`
Bounded closure item: `0.9.7.10 Bounded Semantic Closure Gate`

## Active direction

Core, implementation and conformance planning remain separate authorities. Existing executable reference evidence stays active as falsification feedback and does not define Core meaning. Package, public-standard, artifact-contract and bounded-work versions remain independent axes.

## v0.9.7.9 outcome

- The public intent loader is strict, path-aware and source-aware.
- Unknown fields, malformed shapes, invalid booleans, empty references and unsupported values fail closed with stable diagnostic codes.
- Equivalent source forms produce equivalent intent meaning and diagnostics.
- Canonical AST and ExecutionPlan artifacts preserve source identity, typed lowering evidence, outputs, structured defaults and explicit bindings.
- Unsupported lowering shapes remain blocked instead of being flattened into weaker contracts.
- Mandatory materialization validates lowering evidence before target provider invocation.
- Package, public standard and artifact contract versions remain unchanged.

## v0.9.7.9 correction closure

1. `0.9.7.9.1 Canonical Lowering Regression Repair` completed the original lowering repair.
2. `0.9.7.9.2 Typed Literal and Reference Integrity` removed lossy value coercion and repaired diagnostic boundaries.
3. `0.9.7.9.3 Environment Safety Production Integration` connected typed environment evidence to production validation.
4. `0.9.7.9.4 Scenario Negation and Token Boundary Honesty` replaced substring and polarity heuristics with bounded lexical authority.
5. `0.9.7.9.5 Provider-Backed Approval and Topology Identity` required provider evidence and collision-safe semantic identity.
6. `0.9.7.9.6 Derived Model and Governance Integrity` made report summaries and governance verdicts reproducible from evidence.
7. `0.9.7.9.7 CLI Diagnostic and Release Honesty` closes the public command and release-publication boundary.

## v0.9.7.9.7 outcome

- The Gradle application entrypoint routes release-facing commands through `org.flowlang.cli.honest.HonestFlowCliKt`.
- CLI negotiation, readiness, selection, decision trace and adapter diagnostics are reconciled against the exact emitted target manifest.
- Manifest existence is reported as evidence, not as executable readiness.
- Target rendering is not invoked without explicit `--render`.
- Explicit rendering requires `TargetRenderMode.EXECUTABLE`; review-only and blocked evidence fail before output publication.
- Per-flow artifact bundles describe files actually emitted by the command rather than inheriting unrelated release artifacts.
- Release compliance requires an explicit `PASS` conformance manifest; missing evidence is a failure.
- Standard draft and export artifacts are assembled from real conformance, integrity and metadata evidence.
- Standard export writes to a sibling staging directory, verifies the staged bundle and only then replaces the destination.
- Failed validation leaves the previous destination unchanged.
- Release metadata is checked mechanically across Gradle, release state, roadmap, REPORT and CHANGELOG.
- Runtime diagnostics now describe external target-side requirements and explicitly state that Flow Core provides no runtime.

## Roadmap order

1. `0.9.7.1 Governance Signal Integrity` completed
2. `0.9.7.2 Canonical Module and Notes Authority` completed
3. `0.9.7.3 Mandatory Materialization Authority` completed
4. `0.9.7.4 Universal Dependency and Continuity Contract` completed
5. `0.9.7.5 Canonical Intent Meaning` completed
6. `0.9.7.6 Universal Effect and State Transition Model` completed
7. `0.9.7.7 Universal Control and Policy Requirements` completed
8. `0.9.7.8 Abstract Execution Topology Model` completed
9. `0.9.7.9 Intent Lowering and Diagnostic Honesty` completed through correction item `0.9.7.9.7`
10. `0.9.7.10 Bounded Semantic Closure Gate` next

## Validation

Last merged validation: Flow CI #2001, run `30075088660`, passed exact head `002483835844c207df6375d4159e331bfcaf6415` for `v0.9.7.9.6`, including Flow Agent checks, clean compilation, the complete test suite, RC and snapshot checks, and standalone conformance.

The `v0.9.7.9.7` candidate does not predeclare its own success in repository metadata. Its exact-head Flow CI result belongs in the pull request evidence after the candidate commit actually passes.

## Architecture boundary

Flow remains a notes-driven universal automation language and standardization model. Core does not provide task execution, an SDK lifecycle, a plugin framework, dynamic discovery, command transport, shell projection, hidden continuity transfer, implicit implementation binding, target-owned control meaning or permissive release claims. CLI rendering and release publication are evidence-gated edges, not new Core execution mechanisms.
