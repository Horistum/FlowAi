# Flow Core Report v0.7.6

v0.7.6 is a semantic correctness hardening release. It fixes defects where valid intent could be lowered to wrong target semantics, where manual intent files could bypass mandatory safety, and where rendered snapshots could drift silently. The release adds no runtime executor, SDK API, plugin lifecycle, target-specific DSL or syntax expansion.

Implemented:

- active standard version `0.7.6`,
- retained `v0.7.5.purpose-coverage-ratio`,
- added semantic correctness hardening fixes documented in `FIX_SUMMARY.md`,
- `PurposeCoverageAnalyzer`,
- frozen `standard/architecture/standard-model-baseline-v0.7.4.yaml`,
- tests for missing capability coverage, missing blocked risk scenarios, governance-heavy profiles and weak evidence,
- a future-proofed v0.7.4 architecture-delta invariant.

# Flow Core Report v0.7.3

v0.7.3 collapses the legacy registry-consistency gate layer into one StandardModel projection-coherence gate. Public surface, export bundle, export manifest, conformance levels and release profile are projected from `StandardModel`, so they cannot silently drift as parallel hand-maintained lists. The release keeps Flow on its original axis: AI-first standardization of IT/DevOps automation intent into validated contracts and evidence, without runtime execution, SDK APIs, plugin lifecycle or target-specific public DSL.

Implemented:

- active standard version `0.7.3`,
- `v0.7.3.standard-model-projection-coherence`,
- zero active `REGISTRY_CONSISTENCY` release gates,
- removed redundant self-registration/parity/closure/freeze conformance vectors,
- `StandardModel.wellFormednessIssues(rootDir)` evidence-reference validation,
- `negative-signal-only` architecture scoring mode.

# Flow Core Report v0.4.4

v0.4.4 hardens the AI-to-standard boundary on top of the v0.4.3 architecture-governance work, without changing the product direction. It adds an AI proposal-review trust contract, a target expression-support model that refuses untranslatable guards rather than dropping them, behavioral generator conformance, and a typed safety-policy condition model. It focuses on preventing future drift from Flow's original purpose: converting Human/AI intent into validated execution plans and portable conformance artifacts through a verifiable public standard layer.

Implemented:

- Architecture Constitution,
- ADR template for larger changes,
- forbidden direction catalog,
- feature classification catalog,
- release checklist,
- Drift Score model,
- `ArchitectureGovernanceAnalyzer`,
- `v0.4.3.architecture-governance-guardrails` conformance coverage,
- architecture diagnostic codes,
- tests for governance presence and SDK/runtime/plugin drift prevention.

No runtime executor, SDK, plugin framework, target-specific DSL or renderer expansion was added.

Carried from v0.3.19:

- `StandardContractIndexAnalyzer`,
- `StandardContractIndexReport`,
- `StandardReleaseProfile`,
- `StandardReleaseProfileReport`,
- `ArtifactEvidenceAnalyzer`,
- `ArtifactEvidenceReport`,
- `StandardComplianceAnalyzer`,
- `StandardComplianceReport`,
- `standard-contract-index.schema.json`,
- `standard-release-profile.schema.json`,
- `artifact-evidence-report.schema.json`,
- `standard-compliance-report.schema.json`,
- CLI/export integration for the four consolidated public reports.

Carried from v0.3.15:

- `ArtifactIntegrityAnalyzer`,
- `ArtifactIntegrityReport`,
- `ArtifactIntegrityIssue`,
- `artifact-integrity-report.schema.json`,
- CLI/export integration for `artifact-integrity-report.json`,
- conformance checks for required artifacts, version observations and diagnostic coverage status.

Carried from v0.3.14:

- `DiagnosticCoverageAnalyzer`,
- `DiagnosticCoverageReport`,
- `ObservedDiagnosticCode`,
- `diagnostic-coverage-report.schema.json`,
- CLI/export integration for `diagnostic-coverage-report.json`,
- conformance checks for observed diagnostic codes and unknown-code blocking.

Carried from v0.3.13:

- `StandardDiagnosticCatalog`,
- `StandardDiagnosticCatalogReport`,
- `StandardDiagnosticCode`,
- `standard-diagnostic-catalog.schema.json`,
- CLI/export integration for `standard-diagnostic-catalog.json`,
- conformance checks for stable code uniqueness, required public codes and schema validity.

Carried from v0.3.12:

- `TargetAdapterContractAnalyzer`,
- `TargetAdapterContractReport`,
- `AdapterDiagnosticsReport`,
- `target-adapter-contract.schema.json`,
- `adapter-diagnostics.schema.json`,
- CLI/export integration for `target-adapter-contract.json` and `adapter-diagnostics.json`,
- conformance checks for adapter inputs, outputs, forbidden intent inputs and invariants.

Carried from v0.3.11:

- `ConformanceManifestBuilder`,
- `ConformanceManifestReport`,
- `conformance-manifest.schema.json`,
- CLI export support for `conformance-manifest.json`,
- conformance checks for areas, vectors, schemas and required artifacts.

Carried from v0.3.10:

- `FlowArtifactBundleAnalyzer`,
- `FlowArtifactBundleReport`,
- `flow-artifact-bundle.schema.json`,
- CLI/export integration for `flow-artifact-bundle.json`,
- conformance checks for required/optional artifact classification and pipeline order.

Carried from v0.3.9:

- `TargetDecisionTraceAnalyzer`,
- `TargetDecisionTraceReport`,
- `target-decision-trace-report.schema.json`,
- CLI/export integration for `target-decision-trace-report.json`,
- conformance checks for decision trace steps and target explanations.

Carried from v0.3.8:

- `TargetSelectionAnalyzer`,
- `TargetSelectionReport`,
- `target-selection-report.schema.json`,
- CLI/export integration for `target-selection-report.json`,
- conformance checks for ranked target candidates and recommended target selection.

Carried from v0.3.7:

- `ExecutionReadinessAnalyzer`,
- `ExecutionReadinessReport`,
- `execution-readiness-report.schema.json`,
- CLI/export integration for `execution-readiness-report.json`,
- conformance checks for ready, degraded and blocked target-generation decisions.

Carried from v0.3.6:

- `portabilityScore` in `TargetCapabilityNegotiationReport`,
- per-target portability scores,
- `portableCapabilities`,
- `targetSpecificCapabilities`,
- `blockingPortabilityIssues`,
- `requiredWorkarounds`,
- schema coverage for capability negotiation report v1.1,
- conformance and JUnit coverage for ExecutionPlan portability.

Carried from v0.3.5:

- `IntentDecisionAnalyzer`,
- `IntentDecisionReport`,
- `intent-decision-report.schema.json`,
- CLI/export integration for `intent-decision-report.json`,
- conformance checks for cleanup retention, backup timezone, database migration backup and production deploy approval.

Carried from v0.3.4:

- module descriptor contract v1.2,
- `ModuleContractAnalyzer`,
- `CapabilityModuleContractReport`,
- CLI command `modules`,
- schema and conformance coverage for capability module contract report,
- architecture guardrail preventing module descriptors from owning runtime hooks or renderer templates.

Carried from v0.3.2:

- first-class safety policy validation for approval, dry-run, backup, rollback plan, change ticket, destructive operation and external side-effect gates,
- cleanup lowering blocked without retention/safety,
- Kubernetes maintenance lowering blocked when a dry-run policy is present but no dry-run is confirmed,
- canonical lowercase `canonical-execution-plan.json` export,
- `capability-negotiation-report.schema.json`,
- adapter-facing YAML loader namespace,
- v0.3.2 conformance checks and vectors.

## 0.3.0-rc1.8.3 - Conformance stabilization hotfix

- Keeps functional conformance checks blocking.
- Keeps snapshot, schema and rendered-version drift checks blocking through `ConformanceRunner.summary.ok`.
- Adds explicit failure details to the JUnit bridge for core specification scenarios.
- Bumps Flow standard version to 0.3.0-rc1.8.3.


## 0.3.0-rc1.8.3 - Test compile hotfix

- Fixed Kotlin string interpolation in FlowBetaConformanceTests for the literal GitHub Actions expression `${{ always() }}`.
- Replaced a redundant `mappingNotes != null` conformance assertion with an actual non-empty mapping notes check.
- No functional runtime behavior was changed.

# Flow Core Report v0.3.0-rc1.8.3

## Secret rotation and conformance hotfix

- Fixed the secret-rotation scenario pack to avoid false extraction from stop words.
- Default conformance now validates generated semantics and keeps strict snapshot mode available via `FLOW_SNAPSHOT_STRICT=true`.

v0.3.0-rc1.8.3 moves the beta from console-only rendering toward reproducible standard artifacts.

## Added

- `--out` export for the complete intent-to-target pipeline.
- TargetManifest JSON export.
- Standard version metadata in ExecutionPlan/TargetManifest/rendered output.
- Improved GitHub Actions renderer.
- Initial Tekton partial generator and renderer.
- Stricter conformance checks.
- End-to-end snapshots under `conformance/snapshots/build-test-deploy`.
- `CHANGELOG.md`.

## Standard Pipeline

```text
Intent YAML
  -> normalized intent
  -> capability validation
  -> Flow AST
  -> Flow validation
  -> Execution Plan
  -> Compatibility Report
  -> Target Manifest
  -> Rendered target output
```

## Known Limitations

- Renderers are still draft renderers, not production-grade vendor generators.
- Tekton support is explicitly partial.
- Snapshot comparison is intentionally lightweight in rc1; future releases should compare canonical generated artifacts against committed snapshots.
- Full Gradle verification must be run in a network-enabled/local environment.

## v0.3.0-rc1.8.3 update

This version adds an executable Standard Intent Catalog, intent design reporting, target capability discovery commands and export of `intent-design-report.json`.

The goal is to prevent the project from drifting into a low-level DSL. Low-level `.flow` remains useful, but the primary public path is:

```text
Human / AI intent -> Standard Intent Model -> Intent Design Report -> Capability Validation -> AST -> Execution Plan -> Compatibility -> Target Manifest -> Rendered Output
```

## v0.3.0-rc1.8.3 foundation correction

This release fixes the architectural drift identified after rc2:

- intent params/config are now structured `IntentValue` objects instead of `Map<String,String>`;
- convention defaults are reported as assumptions, not silently hidden in lowering;
- target manifest generation preserves control-flow nodes from the Execution Plan;
- target capabilities now support fine-grained feature keys;
- standard capability catalog definitions are extended toward executable contracts;
- Tekton renderer scripts now include shebang and `set -eu`.

Remaining limitations:

- full Gradle verification requires a network-enabled/local environment;
- snapshot comparison is still presence/version oriented, not exact canonical diffing;
- target renderers remain draft quality and must not be treated as production-grade generated pipelines.

## v0.3.0-rc1.8.3 semantic generator correction

This release addresses the rc3 regressions raised during review:

- Generated Jenkins and GitHub Actions conditions are no longer semantic comments. Conditions are translated into Jenkins Groovy `when { expression { ... } }` and GitHub Actions `if: ${{ ... }}` expressions.
- `FlowPlanner` no longer creates false sequential dependencies between data-independent steps. Dependencies now come from explicit intent `requires` and data-flow references.
- GitHub Actions generation returns to job-per-task manifests with `needs:` edges, making DAG and parallel intent visible to the target platform.
- CLI rendering now uses the canonical TargetManifest path only. Legacy draft generators remain deprecated compatibility classes and are no longer the public generation route.
- Target registry YAML is the runtime source of truth. Built-in Kotlin targets are deprecated fallback data only.
- Renderer escaping was tightened to avoid unsafe Groovy triple-quoted shell strings and fragile multiline output.
- Conformance now includes semantic regression tests for conditions, DAG dependencies and GitHub Actions jobs/needs.

Known remaining limits:

- Tekton condition translation is still represented as a Flow condition comment because portable Tekton `when`/CEL mapping requires a dedicated target feature contract.
- Native rollback implementations are still module/target-specific. The standard layer represents rollback as `standard.rollback`.
- Full Gradle verification requires a local environment with access to Gradle dependencies.

## v0.3.0-rc1.8.3 generator/conformance correction

v0.3.0-rc1.8.3 focuses on the original Flow goal: a portable AI-first automation standard that can render to Jenkins, GitHub Actions and Tekton without forcing users to learn each platform syntax.

Key corrections:

- Target manifests now carry explicit mapping notes.
- Golden conformance compares rendered outputs and JSON structures, not only marker strings.
- Jenkins/GitHub/Tekton renderers use the canonical manifest path and preserve conditions/dependencies more faithfully.
- Standard capabilities now have executable contracts derived from the catalog.
- Core model packages are guarded against Jackson/YAML imports so parser/loader adapters do not leak into the standard model.

## v0.3.0-rc1.8.3 AI Normalization Hardening

This update fixes the core invariant for the AI path: a normalized result must be valid after lowering, not just valid as an intent object.

Fixed:

- Removed non-English keyword triggers from the scenario-pack normalizer; public command vocabulary remains English.
- Removed unsupported Kubernetes `app` parameters from generated deploy/get actions.
- Kubernetes verification now maps application identity to `selector`, while `resource` defaults to `pods`.
- Added full AI-normalization validation in conformance: normalizer -> intent validator -> AST lowering -> Flow validator -> execution plan.
- Added schema smoke validation for public outputs to reduce schema/JSON drift.
- Aligned `build.gradle.kts` version and `FlowStandardVersions.FLOW_STANDARD_VERSION` to `0.3.0-rc1.8.3`.

## Compile hotfix: 0.3.0-rc1.8.3

A compile regression in `ConformanceRunner` was fixed: `PipelineArtifacts.plan` is now typed as `ExecutionPlan`, so conformance checks can use the flattened task view safely. CLI JSON output variables are explicitly typed as `String` to avoid Kotlin overload ambiguity in offline compilation diagnostics.


## v0.3.0-rc1.8.3 corrective audit response

This corrective release addresses the scenario-pack audit findings without hiding test failures:

- cleanup uses `resource` for the thing being cleaned and `system` only for actual system references;
- simple backup phrases such as `Back up the orders database every night` are normalized without required subject clarification;
- custom and rollback-only requests lower to a human-review standard intent instead of throwing `NORMALIZATION_BLOCKED`;
- project source/test/example content is English-only;
- regression tests cover the above end-to-end pipeline cases.

Detailed audit response: `docs/RC1_8_1_AUDIT_AND_FIXES.md`.


## 0.3.0-rc1.8.3

- Fixed public JSON serialization to omit null optional fields so schema conformance does not fail on optional step descriptions.
- Kept schema validation blocking; this is a real fix, not an advisory bypass.
