# Changelog

All project source text is written in English. The changelog records architectural and behavioral changes while preserving the project boundary: Flow AI is an AI-first standardization layer for IT and DevOps automation intent, not a runtime executor, SDK platform, plugin lifecycle framework or target-specific public DSL.

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

## 0.8.0 - Core contract check

### Added

- Added `CoreContractCheck` for validating the public core standard contract.
- Added `CoreContractCheckTests`.
- Added `docs/V0_8_0_CORE_CONTRACT_CHECK.md`.
- Bumped Gradle package version to `0.8.0` while leaving the active public standard version controlled by `FlowStandardVersions.FLOW_STANDARD_VERSION`.

### Notes

- The active public standard version was not bumped because that is tied to rendered snapshots and conformance gates.
- No Flow syntax, target rendering semantics, execution behavior, SDK API, runtime executor or plugin lifecycle was added.

## 0.7.7 - Scenario pack quality analyzer

### Added

- Added `ScenarioPackQualityAnalyzer` for scenario pack metadata, useful examples, duplicate identifiers and blocked-coverage checks.
- Added a v0.7.7 work package for scenario pack quality gates.

### Notes

- The analyzer was later covered by direct tests in the 0.8.0 review-fix line.
- The active public standard version remained unchanged.

## 0.7.6 semantic correctness hardening fix 2

### Fixed

- Fixed full offline Gradle test failure caused by stale rendered snapshots for GitHub Actions and Tekton.
- Regenerated rendered snapshots from the reviewed v0.7.6 rendering path.
- Preserved exact snapshot conformance instead of weakening or bypassing the gate.

### Verified

- `./gradlew --offline --no-daemon clean test --stacktrace --console=plain`: 151 tests, 0 failures, 0 errors, 0 skipped.
- `./gradlew --offline --no-daemon run --args="conformance" --console=plain`: 76 passed, 0 failed.

## 0.7.6 semantic correctness hardening fix 1

### Fixed

- Fixed Kotlin compilation in `TargetExpressionTranslator` by avoiding an invalid smart cast on `BinaryExpressionNode.right`.
- Corrected mandatory safety enforcement so high-risk capability obligations are reported by `IntentCapabilityValidator` instead of being silently synthesized before validation.
- Added semantic smoke validation for Kubernetes deploy identity, Jenkins runtime input rendering, mandatory database migration safety, safe-navigation preservation and Jenkins named-pattern rendering.
