# Standard Export Manifest

Introduced in v0.5.0. Extended in v0.5.1 with explicit public-candidate
acceptance criteria, in v0.5.2 with export self-verification metadata, in
v0.5.3 with the Standard Bundle Verifier command, in v0.5.4 with the
Conformance Vector Index and in v0.5.5 with standard-version fixture hardening.

`standard-export-manifest.json` is the implementer-facing index for the public
standard export. It describes the documents, JSON artifacts, schemas and
directories that must be present in a portable standard bundle.

The manifest intentionally describes contracts, not implementation hooks.

Required public documents include:

- Architecture Constitution
- Public Standard Surface
- Compatibility Policy
- Reference Intent Corpus
- Target Semantics Matrix
- Standard Export Bundle
- Implementer Guide

The manifest also repeats non-goals so consumers do not mistake Flow for a
runtime executor, SDK, plugin framework or target-specific DSL.

## v0.5.1 acceptance gate

`manifestVersion` 1.1 adds implementer-facing acceptance metadata:

- `acceptanceCriteria` defines the public checks that must remain true before
  the candidate is treated as acceptable.
- `releaseGateChecks` lists the conformance checks that enforce the candidate
  boundary.
- `evidenceArtifacts` names the artifacts an implementation should publish as
  proof.

This extends the existing manifest instead of introducing another report.

## v0.5.2 self-verification

`manifestVersion` 1.2 adds portable verification metadata:

- `selfVerificationCommands` lists the authoritative commands an implementation
  should run before publishing a standard bundle.
- `bundleVerificationChecks` lists the bundle consistency checks that must hold.
- `verificationInputs` lists the public artifacts needed to perform those
  checks.

This keeps verification in the standard/export layer. It does not add runtime
execution or implementation hooks.

## v0.5.3 verifier command

`manifestVersion` 1.3 adds the deterministic verifier command to
`selfVerificationCommands`:

```bash
./gradlew run --args="standard-verify --bundle dist/flow-standard-0.7.5"
```

The verifier emits `standard-bundle-verification.json`. This report is produced
after export and is not a required input inside the bundle being verified.

## v0.5.4 conformance vector index

`conformance-vector-index.json` is emitted by standard draft/export and by
`conformance --out`. It indexes public `.conformance.yaml` files and reconciles
their required checks with the reference runner and release profile.

## v0.5.5-v0.6.0 legacy closure gates

v0.5.5 through v0.6.0 previously carried several release-verification gates for
version-fixture hardening, release-gate parity, export-surface closure, vector
metadata hygiene and public-candidate closure. In v0.7.3 those registry-style
self-consistency gates are collapsed into the single `StandardModel` projection
coherence invariant. The public behavior is preserved by construction instead
of by parallel hand-maintained check lists.

## v0.6.1-v0.6.8 release candidate strengthening

v0.6.1 through v0.6.9 strengthen existing standard contracts rather than adding
new public reports:

- `v0.6.1.intent-corpus-expansion`
- `v0.6.2.required-clarification-contract`
- `v0.6.3.safety-policy-matrix`
- `v0.6.4.target-semantics-negative-corpus`
- `v0.6.5.execution-plan-semantic-invariants`
- `v0.6.6.ai-input-trust-boundary`
- `v0.6.7.standard-example-bundle`
- `v0.6.8.compatibility-promise`

Together they move the standard back toward the core Flow purpose: deterministic
conversion of human/AI intent into a safe, auditable, portable execution plan.


## v0.7.0 reference corpus execution harness

v0.7.0 adds `v0.7.0.reference-corpus-execution-harness`. The gate replays every public reference intent scenario through the real scenario-pack normalizer, proposal review, intent validation, AST validation and execution-plan planning. It is a standard conformance harness, not a runtime executor, SDK API or plugin lifecycle.

## v0.7.1 architecture debt cleanup and drift enforcement

v0.7.1 adds `v0.7.1.architecture-debt-cleanup-and-drift-enforcement`. The gate enforces drift-score evaluation, report-budget validation, release-gate classification and public compatibility alias invariants. This is a governance hardening step, not a new runtime executor, SDK API, plugin lifecycle, target-specific public DSL or language syntax expansion.

## v0.7.3 standard model projection coherence

v0.7.3 adds `v0.7.3.standard-model-projection-coherence` and removes the redundant registry-consistency gates from the active release profile. The release profile, export manifest, standard-candidate conformance level, export bundle and public surface are now projections of `StandardModel`. If they diverge, the model coherence gate fails directly; no separate parity/closure/freeze gates are needed.


## v0.7.4 architecture delta analyzer

v0.7.4 adds `v0.7.4.architecture-delta-analyzer`. The export manifest now carries the delta gate and the required document `docs/V0_7_4_ARCHITECTURE_DELTA_ANALYZER.md`. This keeps future changes tied to a previous release snapshot instead of relying only on current-state coherence.

## v0.7.5 purpose coverage ratio

v0.7.5 adds `v0.7.5.purpose-coverage-ratio` and the required document `docs/V0_7_5_PURPOSE_COVERAGE_RATIO.md`. The manifest now includes a purpose-quality gate that ties release growth to executable reference-intent coverage instead of registry bookkeeping.
