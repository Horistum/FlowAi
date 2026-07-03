# Changelog

All project source text is written in English. The changelog records architectural and behavioral changes while preserving the project boundary: Flow AI is an AI-first standardization layer for IT and DevOps automation intent, not a runtime executor, SDK platform, plugin lifecycle framework or target-specific public DSL.

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

- Preserved `secret("NAME")` system configuration through planning and materialised it through target-native secret mechanisms.
- Replaced green placeholder commands for unmapped actions with explicit failing diagnostics and mapping notes.
