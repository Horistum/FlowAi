# Changelog

All project source text is written in English. The changelog records architectural and behavioral changes while preserving the project boundary: Flow AI is an AI-first standardization layer for IT and DevOps automation intent, not a runtime executor, SDK platform, plugin lifecycle framework or target-specific public DSL.

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
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.

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
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.

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

### Notes

- No renderer, CLI lowering, Flow syntax, runtime executor, SDK API, plugin lifecycle or target-specific public DSL behavior was added.

## 0.8.0 review fixes

### Fixed

- Added explicit `app` identity to `examples/deploy-with-approval.flow` so the manifest generator path can render Kubernetes deploy without requiring a manifest file.
- Escaped Jenkins builtin regex patterns before placing them inside slash-delimited Groovy regex literals.
- Prevented target interpolation from rewriting member paths such as `${deploy.status}` into fake runtime parameters such as `${params.deploy.status}`.
- Added manifest-path and rendering regression tests.
- Added direct tests for `ScenarioPackQualityAnalyzer`.

### Notes

- No SDK API, runtime executor, plugin lifecycle, target-specific public DSL or Flow syntax change was added.
