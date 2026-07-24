# v0.9.7.9 Bounded Correction Changelog

This ledger records the bounded `0.9.7.9.x` repair sequence. These identifiers are implementation work items, not package, public-standard or artifact-contract versions. The published package remains `0.9.5` and the active public standard remains `0.8.0`.

### v0.9.7.9.1 Evidence and Control Integrity Repair

- Restored task-scoped approval reachability and fail-closed unresolved controls.
- Rejected explicit backup denial before positive backup synthesis.
- Removed opaque runtime command escape hatches and dead implication surfaces.
- Preserved the reference scenario through the canonical generator.
- Validation: Flow CI `#1872`, head `1689d9400192dd0efbfba9b4d80ee6ab5ec06cdc`.

### v0.9.7.9.2 Typed Literal and Reference Integrity

- Separated typed literals, references and dynamic expressions across source and lowering boundaries.
- Repaired source-path diagnostics and rejected lossy or ambiguous coercion.
- Consolidated the correction roadmap and assigned remaining CLI/release wording to `0.9.7.9.7`.
- Validation: Flow CI `#1907`, run `29988554228`, implementation head `740056b2cfc3823a510e35bd935bace1967ada2c`; final metadata head `6231fa89e84f79f2441834d36788e973b0439a26` remained metadata-only and did not claim a separate passing run.

### v0.9.7.9.3 Environment Safety Production Integration

- Connected typed environment evidence to production Flow validation and CLI lowering.
- Replaced environment-name guessing with policy-declared literal, reference and dynamic evidence.
- Added scoped approval reachability and exhaustive finite non-sensitive proof.
- Validation: Flow CI `#1950`, run `29996882135`, exact final head `cf6f30f838126ad066bb823538d68285c42b1d2d`.

### v0.9.7.9.4 Scenario Negation and Token Boundary Honesty

- Replaced substring and ordered-word heuristics with exact token boundaries, clause-local polarity and bounded grammar forms.
- Prevented denied, unavailable or conflicting source text from creating positive backup, notification, repository or control behavior.
- Preserved the public database migration corpus without restoring sentence-wide fuzzy matching.
- Validation: Flow CI `#1989`, run `30070000839`, exact head `33c9481be344781a943d6d313e7a37fe442ba81e`.

### v0.9.7.9.5 Provider-Backed Approval and Topology Identity

- Required provider-owned payload evidence before native approval materialization.
- Preserved stricter compatibility blockers through readiness and rendering.
- Added collision-safe control and topology identities and idempotent review findings.
- Validation: Flow CI `#1997`, run `30073104137`, exact head `d2b02ede8caca9a1cad1f76072e9f4d4d8141b1e`.

### v0.9.7.9.6 Derived Model and Governance Integrity

- Made negotiation, readiness, selection and decision-trace summaries reproducible from detailed evidence.
- Rejected duplicate manifest evidence, repeated reconciliation and mixed artifact lineage.
- Treated drift-score configuration as executable governance policy and scoped ADR exceptions to named signals and real conformance guardrails.
- Validation: Flow CI `#2001`, run `30075088660`, exact head `002483835844c207df6375d4159e331bfcaf6415`.

### v0.9.7.9.7 CLI Diagnostic and Release Honesty

- Routes the public Gradle application through the honest CLI entrypoint.
- Reconciles CLI target reports against the exact emitted manifest.
- Does not invoke a renderer without explicit `--render`; explicit rendering requires executable evidence.
- Replaces `TARGET MANIFEST READY` wording with manifest evidence and render-readiness reporting.
- Requires explicit passing conformance evidence for release compliance.
- Assembles standard release artifacts from real conformance, integrity and metadata evidence.
- Publishes standard bundles only after staged verification, preserving the previous destination on failure.
- Adds machine-checked package, public-standard, roadmap, report and correction-ledger consistency.
- Clarifies that runtime-required capabilities depend on external target-side infrastructure or adapters; Flow Core provides no runtime.
- Validation: Flow CI `#2011`, run `30083768491`, exact head `f970561c568f3fea0dcf8f858acfff15b4a3eaaa`.

### v0.9.7.9.8 Closure-Blocking Safety, Governance and Diagnostic Integrity

- Replaces fail-open arbitrary-text control evidence with typed confirmed, denied and unknown classification.
- Accepts concrete backup, rollback, change-ticket, retention and safety evidence while rejecting placeholders such as `unknown`, `TODO`, `n/a` and `pending`.
- Recognizes bounded compact retention durations such as `14d` without accepting arbitrary text.
- Replaces raw CI/CD bias substring matching with lexical code, control-literal, ordinary-string, comment, catalog and retained-compatibility contexts.
- Localizes lexical findings to the actual occurrence line and separates scenario, conformance, CLI, release and adapter ownership from Core semantic health.
- Makes governance health depend only on actionable coupling in active semantic source.
- Removes the legacy CLI entrypoint and every implicit Jenkins target default.
- Produces target-neutral planning evidence when no target is selected.
- Produces typed review-only or blocked diagnostic manifest evidence for expected target incompatibility instead of a stack trace.
- Re-derives canonical workflow and capability topology from retained source provenance before materialization.
- Retains legacy `dependencies` and `effects` only as mechanically checked compatibility projections.
- Preserves GitHub Actions cancellation semantics and removes `always()` from ordinary dependency jobs.
- Status: complete.
- Implementation validation: Flow CI `#2021`, run `30090943402`, exact implementation head `64e4df9abe8a95adede53b2876c49031e0bfed8f` passed tooling, structure, clean tests, standalone conformance and reference evidence.
- Completion metadata requires its own final exact-head Flow CI before review readiness.

## Closure boundary

`0.9.7.9 Intent Lowering and Diagnostic Honesty` is restored to `completed` only after the bounded correction implementation passed. `0.9.7.10 Bounded Semantic Closure Gate` is now `next`. The closure gate remains a finite verification step and may not introduce new requirements or reinterpret implementation coverage as Core meaning.
