# Flow Core Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed Core roadmap identity: `0.9.7.9 Intent Lowering and Diagnostic Honesty`
Core roadmap item status: `correction-required`
Active correction item: `0.9.7.9.10 Target Selection Provenance and CLI Status Integrity`
Next Core roadmap item: `0.9.7.10 Bounded Semantic Closure Gate` (`blocked`)

## Active direction

Core, implementation and conformance planning remain separate authorities. Existing executable reference evidence stays active as falsification feedback and does not define Core meaning. Package, public-standard, artifact-contract and bounded-work versions remain independent axes.

PR #88 completed the typed target-selection and CLI-result boundary, but a post-merge audit found a dead duplicate CLI composition facade that could relabel `CompatibilityReport.target` as `EXPLICIT_CONFIGURATION`. The same audit proved that target-selection origins were not mechanically closed and that a `BLOCKED` CLI result could return process success. Bounded correction `0.9.7.9.10` is therefore active; closure remains blocked until the repaired public head and its synthetic merge candidate pass independent validation.

## Correction ledger

1. `0.9.7.9.1 Canonical Lowering Regression Repair` reopened the original lowering claim after proving preserved-input evidence was false.
2. `0.9.7.9.2 Typed Literal and Reference Integrity` removed lossy value coercion and repaired diagnostic boundaries.
3. `0.9.7.9.3 Environment Safety Production Integration` connected typed environment evidence to production validation.
4. `0.9.7.9.4 Scenario Negation and Token Boundary Honesty` replaced substring and polarity heuristics with bounded lexical authority.
5. `0.9.7.9.5 Provider-Backed Approval and Topology Identity` required provider evidence and collision-safe semantic identity.
6. `0.9.7.9.6 Derived Model and Governance Integrity` made report summaries and governance verdicts reproducible from detailed evidence.
7. `0.9.7.9.7 CLI Diagnostic and Release Honesty` separated manifest evidence, render authorization and staged release publication.
8. `0.9.7.9.8 Closure-Blocking Safety, Governance and Diagnostic Integrity` repaired the previously known closure blockers and completed after dual validation.
9. `0.9.7.9.9 Parser, Conformance and Architecture Evidence Integrity` repaired parser, conformance, serialization, module governance, target-selection, CLI-result and application-composition integrity and completed after public dual validation.
10. `0.9.7.9.10 Target Selection Provenance and CLI Status Integrity` removes the dead composition facade, closes configuration-source provenance and separates review from blocked process status.

## v0.9.7.9.8 retained implementation boundary

### Authored safety evidence

- Arbitrary non-empty text no longer satisfies backup, rollback, ticket, retention or generic safety controls.
- Explicit positive confirmation, explicit denial and unknown or placeholder evidence are distinct states.
- Placeholder and denial markers are recognized inside longer text, so values such as `backup later`, `approval pending` and `backup unavailable` cannot evade classification.
- Generic words such as `available`, `complete`, `approved`, `required` and `policy` are not control evidence by themselves.
- Concrete locators, structured backup identifiers, actionable rollback plans, change-ticket identifiers and bounded durations such as `14d` may satisfy their matching control.

### Governance signal integrity

- CI/CD bias inventory scans Kotlin lexically rather than using raw substring matching.
- Comments, ordinary diagnostic prose and the analyzer's own catalog declarations are inventory evidence but are not actionable semantic coupling.
- Active semantic identifiers and control/default literals remain actionable.
- CLI, release, scenario, conformance and adapter-owned vocabulary are classified separately from Core semantic source.
- Retained public compatibility symbols remain visible inventory and are not treated as hidden implementation defaults.
- Health status is derived only from actionable production evidence and passed in the independent validation suite.

### Diagnostic CLI

- The public CLI has one entrypoint and no legacy fallback main.
- Absence of `--target` produces target-neutral negotiation and selection evidence; it never selects Jenkins implicitly.
- Rendering requires both explicit `--target` and explicit `--render`.
- Expected target incompatibility produces typed executable, review-only or blocked evidence rather than a stack trace.
- Review evidence can be exported even when requested rendering is not authorized; no target syntax is emitted in that state.

### Canonical topology and derived projections

- Materialization re-derives canonical workflow and capability topology only from retained source metadata.
- Canonical requirements stored on an execution plan are never reused as the expected authority for their own validation.
- Intent-derived source signals without `sourceIntent` produce `planning.topology.canonical.provenance.missing` before provider invocation.
- Missing, forged, orphaned or provenance-free canonical topology is rejected.
- Legacy `dependencies` and `effects` fields remain mechanically validated compatibility projections.

### GitHub Actions cancellation and validation identity

- Ordinary dependency jobs retain GitHub's native success and cancellation semantics.
- `always()` is not added to every job with `needs`.
- Explicit failure handlers and provider-approval skip paths remain guarded by `!cancelled()`.
- The standard Flow CI job checks out `github.event.pull_request.head.sha` and verifies it with `git rev-parse HEAD`.
- A separate job validates GitHub's synthetic pull-request merge candidate; its success is never relabeled as exact-head evidence.

## v0.9.7.9.9 retained implementation boundary

### Parser and conformance integrity

- Retry defaults apply only to omitted fields.
- Incorrectly typed, fractional, unknown and duplicate retry declarations fail closed with `retry.<field>` diagnostic paths.
- Compliance checks exercise both passing and failing conformance manifests.
- Universal artifact, schema, decision and draft evidence is built from a target-neutral Core fixture.

### Architecture boundaries

- Serializer annotations are removed from the target-neutral projection contract.
- Jackson and YAML imports are checked across the declared semantic Core package set.
- Module target ownership is rejected by `CanonicalModuleLoader` structure rather than source indentation.
- Explicit target selection is registry-validated before materialization and raw nullable target parameters are absent from materialization authorities.
- Selection provenance is emitted independently so deterministic target manifests are not mutated by CLI origin.
- CLI execution uses sealed typed outcomes, stable diagnostic codes and semantic artifact roles before presentation.
- CLI and release application composition are covered by production coupling governance rather than an automatic directory exemption.

## v0.9.7.9.10 active implementation boundary

### Target-selection provenance

- The duplicate `org.flowlang.cli.TargetManifestGenerationPipeline` facade is removed.
- Classpath checks require the canonical generator pipeline and reject the removed CLI duplicate.
- Reference snapshots use a typed reference-snapshot source rather than a caller-authored provenance label.
- Conformance and test sources are bounded categories; `cli:compatibility-report` and unknown labels fail closed.
- `TargetSelectionOrigin` remains exactly `CLI_OPTION`, `INTENT_DECLARATION` and `EXPLICIT_CONFIGURATION`.

### CLI process status

- Process statuses are a typed contract: success `0`, invalid input `2`, review required `3`, blocked `4` and internal error `70`.
- `REVIEW_ONLY` remains successful when evidence is merely inspected, but becomes `REVIEW_REQUIRED` when strict or render behavior is requested.
- `BLOCKED` always returns a blocking non-success status.
- `Completed` no longer accepts an independent exit-code parameter.
- Unit and standalone conformance exercise the status mapping and provenance boundary.

### Deliberate scope boundary

- The retained compatibility-symbol manifest and dual version-axis expiration remain a separately bounded governance improvement. This correction does not falsely claim that the existing `KUBERNETES_MAINTENANCE` allowlist has been replaced.

## Roadmap order

1. `0.9.7.1 Governance Signal Integrity` completed
2. `0.9.7.2 Canonical Module and Notes Authority` completed
3. `0.9.7.3 Mandatory Materialization Authority` completed
4. `0.9.7.4 Universal Dependency and Continuity Contract` completed
5. `0.9.7.5 Canonical Intent Meaning` completed
6. `0.9.7.6 Universal Effect and State Transition Model` completed
7. `0.9.7.7 Universal Control and Policy Requirements` completed
8. `0.9.7.8 Abstract Execution Topology Model` completed
9. `0.9.7.9 Intent Lowering and Diagnostic Honesty` correction-required
10. `0.9.7.9.10 Target Selection Provenance and CLI Status Integrity` active
11. `0.9.7.10 Bounded Semantic Closure Gate` blocked

## Validation

Last merged validation: Flow CI #2011, run `30083768491`, passed implementation head `f970561c568f3fea0dcf8f858acfff15b4a3eaaa` for `v0.9.7.9.7`.

Historical correction validation: Flow CI #2025, run `30144935197`, passed exact implementation head `af2062a687e4fbf0a5ec4d3e50d44b8ba07e9319` and synthetic merge candidate `2e30830d55fe73ec0c46c75dfd425c1047dc9853` for `v0.9.7.9.8`.

Historical `0.9.7.9.9` validation: Flow CI #2087, run `30167146445`, passed exact implementation head `54889f7907f1d95b900466fd438f9965ca24dbbe` and synthetic merge candidate `6dcd266e20638fcbb717e6582da711331251bd37`; Flow CI #2094 validated completion metadata before PR #88 merged.

No passing validation is yet claimed for `0.9.7.9.10`. The public implementation head and its synthetic merge candidate must pass independently before completion metadata is written.

## Architecture boundary

Flow remains a notes-driven universal automation language and standardization model. Core does not provide task execution, an SDK lifecycle, a plugin framework, dynamic discovery, command transport, shell projection, hidden continuity transfer, implicit implementation binding, target-owned control meaning or permissive release claims. Diagnostic projection, target rendering and release publication remain evidence-gated edge operations rather than execution mechanisms.
