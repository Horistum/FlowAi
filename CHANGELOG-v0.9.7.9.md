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
- Validation: Flow CI `#1907`, run `29988554228`, implementation head `740056b2cfc3823a510e35bd935bace1967ada2c`.

### v0.9.7.9.3 Environment Safety Production Integration

- Connected typed environment evidence to production Flow validation and CLI lowering.
- Replaced environment-name guessing with policy-declared evidence.
- Validation: Flow CI `#1950`, run `29996882135`, exact final head `cf6f30f838126ad066bb823538d68285c42b1d2d`.

### v0.9.7.9.4 Scenario Negation and Token Boundary Honesty

- Replaced substring and ordered-word heuristics with exact token boundaries, clause-local polarity and bounded grammar forms.
- Prevented denied, unavailable or conflicting source text from creating positive behavior.
- Validation: Flow CI `#1989`, run `30070000839`, exact head `33c9481be344781a943d6d313e7a37fe442ba81e`.

### v0.9.7.9.5 Provider-Backed Approval and Topology Identity

- Required provider-owned payload evidence before native approval materialization.
- Preserved stricter compatibility blockers through readiness and rendering.
- Validation: Flow CI `#1997`, run `30073104137`, exact head `d2b02ede8caca9a1cad1f76072e9f4d4d8141b1e`.

### v0.9.7.9.6 Derived Model and Governance Integrity

- Made negotiation, readiness, selection and decision-trace summaries reproducible from detailed evidence.
- Rejected duplicate manifest evidence, repeated reconciliation and mixed artifact lineage.
- Validation: Flow CI `#2001`, run `30075088660`, exact head `002483835844c207df6375d4159e331bfcaf6415`.

### v0.9.7.9.7 CLI Diagnostic and Release Honesty

- Routed the public application through the honest CLI entrypoint.
- Required explicit rendering and explicit passing conformance evidence.
- Published standard bundles only after staged verification.
- Validation: Flow CI `#2011`, run `30083768491`, exact head `f970561c568f3fea0dcf8f858acfff15b4a3eaaa`.

### v0.9.7.9.8 Closure-Blocking Safety, Governance and Diagnostic Integrity

- Replaced fail-open arbitrary-text control evidence with typed confirmed, denied and unknown classification.
- Replaced raw CI/CD bias substring matching with lexical evidence.
- Removed legacy CLI defaults and validated exact PR heads independently from merge candidates.
- Historical validation: Flow CI `#2025`, run `30144935197`, exact head `af2062a687e4fbf0a5ec4d3e50d44b8ba07e9319`, merge candidate `2e30830d55fe73ec0c46c75dfd425c1047dc9853`.
- Status: historically complete, superseded by later reopenings.

### v0.9.7.9.9 Parser, Conformance and Architecture Evidence Integrity

- Rejected malformed, fractional, unknown and duplicate `.flow` retry declarations.
- Proved both PASS and FAIL compliance polarity.
- Added target-neutral universal evidence and typed target-selection/CLI outcomes.
- Validation: Flow CI `#2087`, run `30167146445`, exact implementation head `54889f7907f1d95b900466fd438f9965ca24dbbe`, merge candidate `6dcd266e20638fcbb717e6582da711331251bd37`.
- Completion metadata: Flow CI `#2094`, run `30183785028`; PR #88 merged.
- Status: historically complete, superseded by later reopenings.

### v0.9.7.9.10 Target Selection Provenance and CLI Status Integrity

- Deleted the dead duplicate CLI manifest pipeline.
- Prevented analytical compatibility output from becoming target-selection provenance.
- Closed selection origin and configuration-source categories.
- Derived process status from typed CLI outcomes.
- Validation: Flow CI `#2100`, run `30187033448`, exact implementation head `155a61b5fc5a1e54da5677eab23eff516e215078`, merge candidate `ce75e032d8c6a807e1aa368b3fdeab19c9d154bb`.
- Completion metadata: Flow CI `#2108`, run `30187368343`; PR #90 merged as `91de098f8f51176a76e34ef53bab1b424edaa8d0`.
- Status: complete, superseded only as the active parent state by the later correction below.

### v0.9.7.9.11 Public Artifact Evidence and Verification Integrity

- Replaces literal public `PASS` fields with computed content validation and negative counterparts.
- Derives target semantics from the target registry, `TargetExpressionSupport` and provider-owned native projection contracts.
- Removes unsupported handwritten approval claims for GitHub Actions and Tekton; both remain adapter-required review-only.
- Introduces one exact artifact contract authority for stable producer identity and introduced-version metadata.
- Rejects unknown public artifacts instead of assigning a generic producer or fictional historical version.
- Validates required artifact derivation against declared artifacts and registered external evidence sources.
- Makes dangling `derivedFrom` references block `evidence.complete` and public compliance.
- Parses conformance and export bundle JSON structurally with strict unknown-field and duplicate-key rejection.
- Requires exact passing conformance evidence and exact stable-artifact membership in `requiredArtifacts`.
- Adds negative tests for failed gates, malformed JSON and artifact ids mentioned outside the authoritative field.
- Status: active; no passing validation is claimed.

## Closure boundary

`0.9.7.9 Intent Lowering and Diagnostic Honesty` is `correction-required`. `0.9.7.10 Bounded Semantic Closure Gate` is `blocked` while `0.9.7.9.11` is active. Package, public-standard and artifact-contract versions remain unchanged.
