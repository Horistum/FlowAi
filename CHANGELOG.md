# Changelog

All project source text is written in English. The changelog records architectural and behavioral changes while preserving the project boundary: Flow AI is an AI-first standardization layer for IT and DevOps automation intent, not a runtime executor, SDK platform, plugin lifecycle framework or target-specific public DSL.

## Unreleased - v0.9.5.x architecture correction track

### Added

- Added the notes-driven architecture recenter and explicit shell/command projection prohibition.
- Added declarative notes package contracts for domain, capability, safety, runtime, target, projection and conformance ownership.
- Added a universal semantic action graph and explicit materialization negotiation with auditable evidence.
- Added no-shell target projection records and target registry lifecycle honesty.
- Added structural governance checks, unified renderer failure semantics and compatibility/readiness reconciliation.
- Added release metadata boundaries that distinguish the published package, unreleased correction scope, public standard and artifact contract versions.

### Changed

- Connected real execution-plan tasks to semantic graph, materialization and projection evidence.
- Removed legacy Jenkins and GitHub Actions shell generator fixtures and their shell-output assertions.
- Reclassified unresolved target work as review-only or fail-fast instead of executable success.
- Required target recommendations to use concrete materialization and renderer evidence rather than capability declarations alone.
- Replaced the production CI/CD bias analyzer's hardcoded future roadmap version list with evidence-driven architectural follow-up areas.
- Reconciled `REPORT.md`, `.flow-agent/release-state.yaml`, both roadmap files, versioning policy and correction reports around one explicit version boundary.

### Correction items recorded

- `0.9.5.0` Architecture Recenter - Notes-Driven Flow
- `0.9.5.1` Shell Usage Inventory and Prohibition
- `0.9.5.2` CI/CD Bias Inventory
- `0.9.5.3` Notes Package Contract Model
- `0.9.5.4` Universal Semantic Action Graph
- `0.9.5.5` Materialization Negotiation
- `0.9.5.6` No-Shell Target Projection
- `0.9.5.7` Target Registry Honesty
- `0.9.5.7.1` Connect Materialization Pipeline
- `0.9.5.7.2` Governance Scanner Honesty
- `0.9.5.7.3` Renderer Failure Semantics Unification
- `0.9.5.7.4` Remove Legacy Shell Generator Fixtures
- `0.9.5.7.5` Compatibility and Readiness Honesty
- `0.9.5.7.6` Release Metadata Reconciliation

### Version boundary

- The published Gradle package version remains `0.9.4`.
- The v0.9.5.x identifiers describe an unreleased architecture correction track, not published package versions.
- The next expected package line remains `0.9.5` and requires an explicit package-promotion decision.
- The active public Flow standard version remains `0.7.6`.
- Intent, AST, ExecutionPlan, TargetManifest and TargetRegistry contract versions are unchanged.
- Existing conformance gate identifiers remain stable.

### Architecture boundary

- No runtime executor, SDK API, framework or plugin lifecycle is introduced.
- No shell generator, command projection or target-specific public Flow DSL is introduced.
- Current renderer payload gaps remain visible as review-only or blocked evidence rather than being hidden by release metadata.

## 0.9.4 - Reference scenario matrix

### Added

- Added `ReferenceScenarioMatrix` as a target-neutral matrix for realistic reference automation scenarios.
- Added `ReferenceScenario`, `ReferenceSemanticExpectation`, `ReferencePortabilityClass`, `ReferenceScenarioKind` and `ReferenceScenarioRisk`.
- Added `ReferenceAdapterProjectionMatrix`, `ReferenceAdapterProjectionExpectation` and `ReferenceAdapterProjectionOutcome` to keep concrete target expectations outside the core scenario model.
- Added reference scenarios for build/test/deploy, API sync, database migration, rollback, cleanup, secret rotation and notification workflows.
- Added explicit negative coverage for destructive cleanup without approval.
- Added tests proving positive scenarios pass the target-neutral core pipeline through parser, validator, safety validator and planner.
- Added separate adapter projection tests for compatibility analysis, manifest generation and degradation analysis.
- Added tests proving negative coverage is rejected by core validation gates instead of being skipped.
- Added `docs/V0_9_4_REFERENCE_SCENARIO_MATRIX.md`.
- Added `.flow-agent/reports/v0.9.4-reference-scenario-matrix.md`.

### Changed

- Bumped the Gradle package version to `0.9.4`.
- Updated release metadata for the v0.9.4 package line.
- Advanced roadmap state from reference scenario matrix to end-to-end standard scenarios.
- Replaced implementation-specific semantic capability names with universal capability names such as `deployment.apply`, `notification.send`, `resource.delete`, `approval.require`, `secret.consume` and `rollback.perform`.

### Notes

- The scenario matrix is declarative, target-neutral and does not introduce a runtime executor.
- Jenkins, GitHub Actions and Tekton expectations are adapter projection checks, not the source of scenario meaning.
- The active public standard version remains `0.7.6`.
- Intent, AST, ExecutionPlan, TargetManifest and TargetRegistry artifact versions are unchanged.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.

## 0.9.3 - Capability degradation semantics

### Added

- Added `TargetCapabilityDegradationAnalyzer` as an explanation layer over `TargetManifest` outputs.
- Added `TargetCapabilityDegradationReport`, `TargetCapabilityDegradationEntry` and `TargetCapabilityDegradationStatus`.
- Added tests for supported, degraded and blocked target behavior.
- Added strict-mode tests proving degraded semantics are rejected before rendering.
- Added explanation coverage for preserved, approximated and blocked semantics.
- Added `docs/V0_9_3_CAPABILITY_DEGRADATION_SEMANTICS.md`.
- Added `.flow-agent/reports/v0.9.3-capability-degradation-semantics.md`.

### Changed

- Bumped the Gradle package version to `0.9.3`.
- Updated release metadata for the v0.9.3 package line.
- Advanced roadmap state from capability degradation semantics to reference scenario matrix.

### Notes

- The degradation analyzer operates at the `TargetManifest` boundary and does not re-plan Flow.
- The active public standard version remains `0.7.6`.
- Intent, AST, ExecutionPlan, TargetManifest and TargetRegistry artifact versions are unchanged.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.

## 0.9.2 - Renderer contract hardening

### Added

- Added `TargetRendererContractValidator` as a pre-render gate for target renderer inputs.
- Added `TargetRendererContractReport` and `TargetRendererContractIssue` for auditable renderer-boundary diagnostics.
- Added renderer contract tests for valid manifests, target mismatch, unknown job dependencies, manifest-contract violations and ambiguous run-plus-children steps.
- Added `docs/V0_9_2_RENDERER_CONTRACT_HARDENING.md`.
- Added `.flow-agent/reports/v0.9.2-renderer-contract-hardening.md`.

### Changed

- Bumped the Gradle package version to `0.9.2`.
- Updated release metadata for the v0.9.2 package line.
- Guarded Jenkins, GitHub Actions and Tekton renderers with renderer contract validation before serialization.
- Advanced roadmap state from renderer contract hardening to capability degradation semantics.

### Notes

- Renderers now reject malformed, mismatched or dependency-inconsistent manifests before output is produced.
- The active public standard version remains `0.7.6`.
- Intent, AST, ExecutionPlan, TargetManifest and TargetRegistry artifact versions are unchanged.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.

## 0.9.1 - Jenkins/GitHub/Tekton projection stability

### Added

- Added `ProjectionStabilitySmokeTests` for the main supported renderer targets.
- Added smoke coverage for Jenkins, GitHub Actions and Tekton rendering from a real Flow example through parser, planner, manifest generation and renderer output.
- Added stability checks for deterministic renderer output, target artifact structure, runtime secret binding and absence of green placebo action commands.
- Added `docs/V0_9_1_PROJECTION_STABILITY.md`.
- Added `.flow-agent/reports/v0.9.1-projection-stability.md`.

### Changed

- Bumped the Gradle package version to `0.9.1`.
- Updated release metadata for the v0.9.1 package line.
- Advanced roadmap state from projection contract to projection stability.

### Notes

- The projection stability tests are smoke-level guards, not byte-for-byte conformance snapshots.
- The active public standard version remains `0.7.6`.
- Intent, AST, ExecutionPlan, TargetManifest and TargetRegistry artifact versions are unchanged.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.

## 0.9.0 - Generator projection contract

### Added

- Added `TargetManifestContractValidator` as a structural contract gate for the boundary between `ExecutionPlan` and target renderers.
- Added `TargetManifestContractReport` and `TargetManifestContractIssue` for auditable projection-contract diagnostics.
- Added tests proving generated Jenkins, GitHub Actions and Tekton manifests satisfy the contract.
- Added negative tests for malformed manifests, missing release metadata, invalid mapping notes, duplicate step ids and green placebo action commands.
- Added `docs/V0_9_0_GENERATOR_PROJECTION_CONTRACT.md`.
- Added `.flow-agent/reports/v0.9.0-generator-projection-contract.md`.

### Changed

- Bumped the Gradle package version to `0.9.0`.
- Updated release metadata for the v0.9.0 package line.
- Advanced roadmap state from the v0.8 safety-hardening line to the v0.9 projection-contract line.

### Notes

- The projection contract validates manifest structure only. It does not execute target work and does not introduce a runtime executor.
- The active public standard version remains `0.7.6`.
- Intent, AST, ExecutionPlan, TargetManifest and TargetRegistry artifact versions are unchanged.
- No SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.

## 0.8.7 - Safety-boundary result handler coverage

### Added

- Added regression tests proving destructive actions inside action result handlers still require approval.
- Added regression coverage proving rollback inside `when error` handler branches remains allowed as an error-handler context.

### Changed

- Extended `SafetyBoundaryValidator` so it descends into action result handlers.
- Treated `when error { ... }` result-handler branches as error-handler context.

### Notes

- The safety gate remains a pre-projection validator over Flow AST and module contracts.
- No public standard version, schema artifact version, runtime executor, SDK API, plugin lifecycle, target-specific public DSL or Flow syntax change was added.

## 0.8.6 - Review hardening fixes

### Added

- Added regression tests for runtime secret materialisation, rollback negation and remaining review findings.
- Added manifest honesty regression tests for unmapped actions, non-materialised data operations, runtime result interpolation and database system URLs.
- Added `.flow-agent/reports/v0.8.6-review-hardening-fixes.md`.

### Changed

- Preserved `secret("NAME")` system configuration through planning and materialised it through target-native mechanisms.
- Replaced green placeholder commands for unmapped actions with explicit failing diagnostics and mapping notes.
- Added mapping notes for Flow-layer data operations that are not materialised by target projection.
- Made notify delivery failures non-zero.
- Preserved explicit rollback negation during scenario-pack normalisation.
- Kept HTTP method literals while treating non-HTTP all-caps tokens as references.
- Isolated mutually exclusive branch result bindings while preserving duplicate detection for sequential bindings.
- Promoted unknown result fields to errors when module output schema is concrete.
- Moved deprecated legacy draft generators out of production source.

### Notes

- The active public standard version remains `0.7.6`.
- Artifact schema versions are unchanged.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.

## 0.8.5 - Safety boundary hardening

### Added

- Added `SafetyBoundaryValidator` as a pre-projection safety gate over Flow AST and module contracts.
- Added tests for production-sensitive mutation, approval-backed high-risk actions and rollback-sensitive actions.
- Added `SafetyBoundaryHardeningTests`.
- Added `docs/V0_8_5_SAFETY_BOUNDARY_HARDENING.md`.
- Added `.flow-agent/reports/v0.8.5-safety-boundary-hardening.md`.

### Changed

- Bumped the Gradle package version to `0.8.5`.
- Updated release metadata for the v0.8.5 package line.
- Wired safety-boundary validation into `FlowValidator` so high-risk semantics are blocked before target projection.

### Notes

- The safety gate does not execute anything and does not rewrite plans or renderer output.
- The active public standard version remains `0.7.6`.
- Intent, AST, ExecutionPlan, TargetManifest and TargetRegistry artifact versions are unchanged.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.

## 0.8.4 - Planner capability constraints

### Added

- Added `PlannerCapabilityConstraintGate` as a pre-projection gate over planner output and target capability compatibility.
- Added `PlannerCapabilityConstraintReport` and `PlannerCapabilityConstraintStatus` to make allowed, degraded and blocked projection states explicit.
- Added tests proving supported targets are allowed, unsupported target semantics are blocked before rendering, partial support is degraded outside strict mode, strict mode blocks partial support, and unknown targets are blocked.
- Added `docs/V0_8_4_PLANNER_CAPABILITY_CONSTRAINTS.md`.
- Added `.flow-agent/reports/v0.8.4-planner-capability-constraints.md`.

### Changed

- Bumped the Gradle package version to `0.8.4`.
- Updated `REPORT.md`, `.flow-agent/release-state.yaml`, `.flow-agent/roadmap.yaml`, and version consistency tests for the v0.8.4 package line.

### Notes

- The planner remains platform-neutral; the new gate does not rewrite plans or invent target workarounds.
- The active public standard version remains `0.7.6`.
- Intent, AST, ExecutionPlan, TargetManifest and TargetRegistry artifact versions are unchanged.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL or Flow syntax change was added.

## 0.8.3 - Package version and release integrity

### Added

- Added `docs/versioning-policy.md` to define the distinction between package, public standard, artifact schema and conformance gate versions.
- Added `VersionConsistencyTests` to guard package metadata, top-level report metadata, release-state metadata and artifact version boundaries.
- Added `.flow-agent/reports/v0.8.3-release-integrity.md` as the package-line release integrity report.

### Changed

- Bumped the Gradle package version to `0.8.3`.
- Updated `REPORT.md` to make `0.8.3` the current package line while keeping the active public standard version at `0.7.6`.
- Updated `.flow-agent/release-state.yaml` and `.flow-agent/roadmap.yaml` for the v0.8.3 release-integrity package line.

### Notes

- The active public standard version remains `0.7.6`.
- Intent, AST, ExecutionPlan, TargetManifest and TargetRegistry artifact versions are unchanged.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL or Flow syntax change was added.

## 0.8.2 - Target negotiation report

### Added

- Added `TargetNegotiationReportAnalyzer` as an explanation layer over the existing compatibility negotiation model.
- Added `TargetNegotiationExplanationReport`, `TargetNegotiationExplanation`, `TargetNegotiationRejectionReason` and `TargetNegotiationOutcome`.
- Added tests for supported, degraded, blocked and runtime-required target outcomes.
- Added `docs/V0_8_2_TARGET_NEGOTIATION_REPORT.md`.
- Added `.flow-agent/reports/v0.8.2-release-report.md`.
- Bumped Gradle package version to `0.8.2` while keeping the active public standard version at `0.7.6`.

### Notes

- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion, planner change or Flow syntax change was added.
- The report is explanatory. Planner enforcement is intentionally left for v0.8.3.

## 0.8.1 - Target capability matrix

### Added

- Added `TargetCapabilityMatrixAnalyzer` as a descriptive capability matrix over registered targets.
- Added `TargetCapabilityMatrixReport` and `TargetCapabilityMatrixEntry`.
- Added tests for repository target coverage, missing required targets, blank target metadata and explicit unsupported capability representation.
- Added `docs/V0_8_1_TARGET_CAPABILITY_MATRIX.md`.
- Added `.flow-agent/reports/v0.8.1-release-report.md`.
- Bumped Gradle package version to `0.8.1` while keeping the active public standard version at `0.7.6`.

### Notes

- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.
- The matrix is descriptive. It does not change target rendering behavior.

## 0.8.x B2 conformance quality-gate wiring

### Added

- Added `ConformanceQualityGates` with stable quality-gate check names:
  - `v0.8.x.core-contract-check`
  - `v0.8.x.scenario-pack-quality`
- Added regression tests proving both B2 quality gates expose stable names and pass on the current repository state.
- Rewrote `README.md` as the primary project documentation document instead of a version-history dump.
- Added Apache License 2.0 licensing metadata through `LICENSE`.
- Wired B2 quality gates into `ConformanceRunner` and added public conformance vectors for those checks.
