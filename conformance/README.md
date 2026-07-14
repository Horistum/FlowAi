# Flow Conformance Vectors

This directory contains draft conformance vectors for the Flow standard.

The current CLI runner is:

```bash
./gradlew run --args="conformance"
```

Vector categories:

```text
intent/           Standard Intent Model normalization and validation
ast/              Flow AST compatibility vectors
execution-plan/   Execution Plan semantics and DAG vectors
capabilities/     Capability validation vectors
targets/          Target compatibility vectors
generators/       Target manifest and renderer vectors
artifacts/        Public artifact bundle and integrity vectors
diagnostics/      Standard diagnostic catalog and coverage vectors
standard/         Public standard candidate vectors
```

v0.7.3 still uses a Kotlin runner for blocking checks, but the vector files are indexed as public conformance data and reconciled with the active release profile. The legacy registry-consistency gates have been collapsed into StandardModel projection coherence.

v0.7.3 keeps the public draft vectors, semantic-correctness hardening, standard-boundary cleanup, architecture governance guardrails, the v0.5.4 data-driven Conformance Vector Index and reference-corpus execution without adding runtime execution, SDK APIs or plugin lifecycle.

v0.5.0 adds vectors for:

- Conformance Levels,
- Standard Export Manifest,
- Public Standard Candidate.

v0.5.1 adds a vector for:

- Public Candidate Acceptance Gate.

v0.5.2 adds a vector for:

- Standard Export Self-Verification.

v0.5.3 adds a vector for:

- Standard Bundle Verifier.

v0.5.4 adds a vector for:

- Data-driven Conformance Index.

v0.5.5 adds a vector for:

- Standard Bundle Version Fixture Hardening.

v0.5.6 adds a vector for:

- Release Gate Parity.

v0.5.7 adds a vector for:

- Export Surface Closure.

v0.5.8 adds a vector for:

- Standard Vector Metadata Hygiene.

v0.5.9 adds a vector for:

- Public Candidate Closure.

v0.6.0 adds a vector for:

- Standard Release Candidate Baseline.

v0.6.1 through v0.6.9 add vectors for:

- Intent Corpus Expansion,
- Required Clarification Contract,
- Safety Policy Matrix,
- Target Semantics Negative Corpus,
- Execution Plan Semantic Invariants,
- AI Input Trust Boundary,
- Standard Example Bundle,
- Compatibility Promise,
- Release Candidate Freeze.

v0.4.5 through v0.4.9 add vectors for:

- Public Standard Surface,
- Compatibility and Migration Policy,
- Reference Intent Corpus,
- Target Semantics Matrix,
- Standard Export Bundle.

v0.4.0 adds vectors for:

- Standard Freeze Report,
- Compatibility Policy,
- Reference Corpus,
- Negative Conformance Corpus,
- Target Conformance Profile,
- Flow Standard Draft 0.4.

v0.3.19 consolidates vectors for:

- Standard Contract Index,
- Standard Release Profile,
- Artifact Evidence Report,
- Standard Compliance Report.

v0.3.15 adds vectors for:

- Artifact Integrity Report,
- required public artifact presence,
- standard version consistency,
- diagnostic coverage status propagation.

v0.3.14 adds vectors for:

- Diagnostic Coverage Report,
- observed public diagnostic codes,
- unknown diagnostic code detection against the standard catalog.

v0.3.13 adds vectors for:

- Standard Diagnostic Code Catalog,
- stable public diagnostic code uniqueness,
- required diagnostic codes across intent, safety, target, adapter and conformance areas.

v0.3.12 adds vectors for:

- Target Adapter Contract,
- allowed adapter input artifacts,
- forbidden intent inputs,
- adapter output and diagnostics contract.

v0.3.11 adds vectors for:

- Conformance Manifest,
- area/check/vector/schema inventory,
- required public artifact inventory.

v0.3.10 adds vectors for:

- Public Artifact Bundle Contract,
- required vs optional exported artifacts,
- schema and pipeline order metadata.

v0.3.9 adds vectors for:

- Target Decision Trace Report,
- trace steps across the public target-decision pipeline,
- explanations for recommended, degraded and blocked target candidates.

v0.3.8 adds vectors for:

- Target Selection Report,
- ranked target candidates,
- recommended target selection from readiness and portability facts.

v0.3.7 adds vectors for:

- Execution Readiness Report,
- ready/degraded/blocked target-generation decisions,
- blocker and warning findings before manifest generation.

v0.3.6 adds vectors for:

- ExecutionPlan portability score,
- portable vs target-specific capabilities,
- blocking portability issues,
- required target workarounds.

v0.3.5 adds vectors for:

- Intent Decision Model,
- cleanup retention as blocking decision,
- backup timezone as recommended decision,
- database migration backup as blocking decision,
- production deploy approval as blocking safety gate.

v0.3.4 adds vectors for:

- Capability Module Contract report,
- destructive module actions requiring safety,
- target implications,
- modules not owning runtime hooks or renderer templates.

v0.3.2 adds vectors for:

- canonical lowercase Execution Plan export,
- cleanup blocked without retention/safety,
- cleanup allowed with explicit retention,
- Kubernetes maintenance blocked without required dry-run.

## Reference snapshots

The build/test/deploy reference snapshot set is stored under:

```text
conformance/snapshots/build-test-deploy/
```

It records semantic artifacts and current target projection evidence. The directory is not an end-to-end execution proof. `snapshot-index.json` declares each artifact as semantic-only, review-only, fail-fast or executable, and the current target outputs are committed under `.review.yaml` names with `executable: false`.

Conformance compares normalized intent, Flow AST, canonical Execution Plan, snapshot evidence and rendered review artifacts against the real pipeline. Executable-looking vendor file names are forbidden until complete materialization and renderer payload evidence exists.


v0.7.0 adds a vector for:

- Reference Corpus Execution Harness.


v0.7.3 keeps the v0.7.1 governance vector and adds a vector for:

- `v0.7.3.standard-model-projection-coherence`

This gate enforces drift-score evaluation, report-budget checks, release-gate classification, public alias invariants and the AST data/orchestration boundary ADR without adding runtime execution, SDK APIs, plugin lifecycle or target-specific public DSL.


## v0.7.4 architecture delta analyzer

v0.7.4 adds `v0.7.4.architecture-delta-analyzer` and the vector `conformance/standard/architecture-delta-analyzer.conformance.yaml`.

The gate compares `standard/architecture/standard-model-baseline-v0.7.3.yaml` with the active `StandardModel`. It rejects new registry-consistency gates, silent gate-kind reclassification, silent removal of substance checks and stable public surface growth without evidence-backed checks.

## v0.7.5 purpose coverage ratio

v0.7.5 adds `v0.7.5.purpose-coverage-ratio` and the vector `conformance/standard/purpose-coverage-ratio.conformance.yaml`. The gate verifies reference corpus capability coverage, blocked risk scenarios, automation-purpose ratio, governance ratio and evidence-backed purpose checks.
