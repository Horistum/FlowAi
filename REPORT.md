# Flow Core Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed Core roadmap identity: `0.9.7.9 Intent Lowering and Diagnostic Honesty`
Core roadmap item status: `correction-required`
Active correction item: `0.9.7.9.8 Closure-Blocking Safety, Governance and Diagnostic Integrity`
Next Core roadmap item: `0.9.7.10 Bounded Semantic Closure Gate` (`blocked`)

## Active direction

Core, implementation and conformance planning remain separate authorities. Existing executable reference evidence stays active as falsification feedback and does not define Core meaning. Package, public-standard, artifact-contract and bounded-work versions remain independent axes.

The v0.9.7 track cannot enter its bounded closure gate while `0.9.7.9.8` is active. The correction was reopened after proving that generic safety prose, missing canonical source provenance and merge-ref CI evidence could still satisfy stronger claims than the implementation supported. `0.9.7.10` remains blocked until the corrected implementation head and its synthetic merge candidate pass separately.

## Correction ledger

1. `0.9.7.9.1 Canonical Lowering Regression Repair` reopened the original lowering claim after proving preserved-input evidence was false.
2. `0.9.7.9.2 Typed Literal and Reference Integrity` removed lossy value coercion and repaired diagnostic boundaries.
3. `0.9.7.9.3 Environment Safety Production Integration` connected typed environment evidence to production validation.
4. `0.9.7.9.4 Scenario Negation and Token Boundary Honesty` replaced substring and polarity heuristics with bounded lexical authority.
5. `0.9.7.9.5 Provider-Backed Approval and Topology Identity` required provider evidence and collision-safe semantic identity.
6. `0.9.7.9.6 Derived Model and Governance Integrity` made report summaries and governance verdicts reproducible from detailed evidence.
7. `0.9.7.9.7 CLI Diagnostic and Release Honesty` separated manifest evidence, render authorization and staged release publication.
8. `0.9.7.9.8 Closure-Blocking Safety, Governance and Diagnostic Integrity` remains active until its strengthened implementation and governance state pass truthful validation.

## v0.9.7.9.8 implementation boundary

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
- Evidence is localized to its actual source line rather than the first line of a multi-line lexer span.
- Health status is derived only from actionable active-semantic evidence.

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
- Legacy `dependencies` and `effects` fields are retained as compatibility projections, not independent semantic authorities.
- Materialization and conformance reject divergence from `dependsOn` and the typed semantic effect model.

### GitHub Actions cancellation and validation identity

- Ordinary dependency jobs retain GitHub's native success and cancellation semantics.
- `always()` is not added to every job with `needs`.
- Explicit failure handlers and provider-approval skip paths may evaluate after failure or skip, but remain guarded by `!cancelled()`.
- The standard Flow CI job checks out `github.event.pull_request.head.sha` for exact-head validation and verifies it with `git rev-parse HEAD`.
- A separate job validates GitHub's synthetic pull-request merge candidate; its success is not relabeled as exact-head evidence.

## Roadmap order

1. `0.9.7.1 Governance Signal Integrity` completed
2. `0.9.7.2 Canonical Module and Notes Authority` completed
3. `0.9.7.3 Mandatory Materialization Authority` completed
4. `0.9.7.4 Universal Dependency and Continuity Contract` completed with derived compatibility projections explicitly retained and enforced
5. `0.9.7.5 Canonical Intent Meaning` completed
6. `0.9.7.6 Universal Effect and State Transition Model` completed
7. `0.9.7.7 Universal Control and Policy Requirements` completed
8. `0.9.7.8 Abstract Execution Topology Model` completed with canonical provenance re-derived at materialization
9. `0.9.7.9 Intent Lowering and Diagnostic Honesty` correction-required
10. `0.9.7.9.8 Closure-Blocking Safety, Governance and Diagnostic Integrity` active
11. `0.9.7.10 Bounded Semantic Closure Gate` blocked

## Validation

Last merged validation: Flow CI #2011, run `30083768491`, passed implementation head `f970561c568f3fea0dcf8f858acfff15b4a3eaaa` for `v0.9.7.9.7`, including Flow Agent checks, clean compilation, the complete test suite and standalone conformance.

The earlier Flow CI #2021 and #2022 runs checked GitHub pull-request merge refs. They remain useful historical merge-candidate evidence but are not exact-head evidence and do not complete `0.9.7.9.8`.

The corrected implementation must pass both the exact-head `compile-test-conformance` job and the separate `merge-candidate-compile-test-conformance` job. Only then may a metadata-only head mark the correction complete, restore `0.9.7.9` to completed and advance `0.9.7.10` to next.

## Architecture boundary

Flow remains a notes-driven universal automation language and standardization model. Core does not provide task execution, an SDK lifecycle, a plugin framework, dynamic discovery, command transport, shell projection, hidden continuity transfer, implicit implementation binding, target-owned control meaning or permissive release claims. Diagnostic projection, target rendering and release publication remain evidence-gated edge operations rather than execution mechanisms.
