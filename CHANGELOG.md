# Changelog

All project source text is written in English. The changelog records architectural and behavioral changes while preserving the project boundary: Flow AI is an AI-first standardization layer for IT and DevOps automation intent, not a runtime executor, SDK platform, plugin lifecycle framework or target-specific public DSL.

## Unreleased - v0.8.x B2 conformance quality-gate wiring

### Added

- Added `ConformanceQualityGates` with stable quality-gate check names:
  - `v0.8.x.core-contract-check`
  - `v0.8.x.scenario-pack-quality`
- Added regression tests proving both B2 quality gates expose stable names and pass on the current repository state.
- Rewrote `README.md` as the primary project documentation document instead of a version-history dump.
- Added Apache License 2.0 licensing metadata through `LICENSE`.

### Notes

- Direct `ConformanceRunner.run()` wiring is intentionally kept as a narrow follow-up risk area unless the large runner file can be modified safely without broad rewrite.
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

## 0.7.6 - Semantic correctness hardening

### Added and changed

- Bumped active standard version to `0.7.6`.
- Fixed Kubernetes deploy lowering so generated target manifests carry application identity instead of falling back to a literal `app`.
- Fixed runtime input reference rendering so bare references such as `environment` and `version` become target-native runtime parameters.
- Centralized target parameter interpolation across generated command types.
- Moved mandatory capability safety enforcement into the core intent validator.
- Fixed validator block scoping for sibling statements in conditional, parallel, match, try/retry and error-handler blocks.
- Preserved mixed safe-navigation semantics per path segment.
- Added single-quoted string support to the lexer and removed global quote rewriting in condition parsing.
- Fixed exact `${name}` intent YAML references.
- Removed Jenkins regex `/.*/` fallback for named patterns.
- Removed implicit DAG parallelization from intent `requires`.
- Deduplicated cycle diagnostics in intent step dependency validation.
- Regenerated rendered end-to-end snapshots and made rendered snapshot conformance exact.

## 0.7.5 - Purpose coverage ratio

### Added

- Bumped active standard version to `0.7.5`.
- Added `v0.7.5.purpose-coverage-ratio` as a behavior-quality gate tied to the public reference intent corpus and `StandardModel`.
- Added `PurposeCoverageAnalyzer`.
- Added tests for missing purpose capabilities, missing blocked risk scenarios, governance-heavy release profiles and weak purpose evidence.
- Added a frozen `standard-model-baseline-v0.7.4.yaml`.
- Relaxed the v0.7.4 architecture-delta gate for future versions so it verifies invariant behavior instead of pinning future release structure.

## 0.7.4 - Architecture delta analyzer

### Added

- Added architecture delta analysis for standard-model changes.
- Added conformance coverage for architecture delta expectations.
- Preserved the purpose boundary by measuring actual architectural delta rather than adding ceremonial governance metadata.

## 0.7.3 - Standard model projection coherence

### Added and changed

- Bumped active standard version to `0.7.3`.
- Collapsed legacy registry-consistency gates into `v0.7.3.standard-model-projection-coherence`.
- Removed redundant public conformance vectors that only compared projections of the same `StandardModel`.
- Kept `StandardModel` as the single source of truth for public surface, export bundle, export manifest, conformance levels and release profile.
- Enforced falsifiability and evidence anchors in `StandardModel.wellFormednessIssues(rootDir)`.

## 0.7.1 - Standard model governance integration

### Added and changed

- Added `StandardModel` as the single source of truth for public standard artifacts and release checks.
- Reworked `StandardReleaseProfile`, `StandardSurface`, conformance levels and export manifest release-gate projections to derive from `StandardModel`.
- Converted `StandardArtifactRegistry` into a compatibility facade over `StandardModel`.
- Replaced release-gate category string heuristics with explicit `GateKind` data.
- Strengthened architecture report-budget validation with standard-model well-formedness checks.

## 0.7.1 - Architecture debt cleanup and drift enforcement

### Added and changed

- Bumped active standard version to `0.7.1`.
- Added `v0.7.1.architecture-debt-cleanup-and-drift-enforcement`.
- Architecture governance now enforces `drift-score.yaml` instead of checking only file existence.
- Added report-budget validation for public standard-surface artifacts.
- Added release-gate classifications so registry-consistency gates are visible as registry evidence, not behavior coverage.
- Added public compatibility alias invariants for duplicated public fields.
- Removed unused private validator helper code.
- Added an ADR documenting the AST data/orchestration boundary.

## 0.7.0 fixed

### Fixed

- Fixed the reference-corpus execution harness conformance gate so its vector-index closure uses canonical runner check ids.
- Corrected stale runner-check aliases for:
  - `v0.3.2.execution-plan.canonical`
  - `v0.3.2.safety-policy-validation`
  - `v0.3.4.capability-module-contracts`

## 0.7.0 - Reference corpus execution harness

### Added

- Bumped active standard version to `0.7.0`.
- Added `v0.7.0.reference-corpus-execution-harness`.
- Added `ReferenceCorpusExecutionHarness` to replay every public reference intent scenario through the real scenario-pack normalizer, proposal review, intent validation, AST validation and execution-plan planning.
- Added regression checks for production deployments without explicit approval, rollback-only review lowering, secret rollout verification semantics and build/test/deploy routing.

## 0.6.9 fixed approval review

### Fixed

- Fixed rollback-only review normalization so `Rollback the last release.` remains lowerable.
- Missing rollback application context became a recommended review question rather than a blocking required clarification.
- Rollback-only intents do not synthesize deployment steps.

## 0.6.9 fixed approval

### Fixed

- Fixed deterministic normalization so production deployment no longer synthesizes an approval step merely because the target environment is production.
- Production deployment without explicit approval remains approval-free and is blocked by `SAFETY_REQUIRES_APPROVAL`.
- Explicit approval requests still emit the approval step and approval policy.
- Kubernetes production or disruptive maintenance no longer auto-approves itself.

## 0.6.9 - Release candidate freeze

### Added

- Bumped active standard version to `0.6.9`.
- Added `v0.6.9.release-candidate-freeze`.
- The freeze gate requires every 0.6.x release-candidate gate, bundle verification, vector closure and architecture non-goal to hold.

## 0.6.8 - Compatibility promise

### Added

- Added `v0.6.8.compatibility-promise`.
- Added compatibility-promise rules for patch, minor, major and all-release expectations without expanding the public artifact surface.

## 0.6.7 - Standard example bundle

### Added

- Added `v0.6.7.standard-example-bundle`.
- Added golden accepted and blocked examples tied to existing public artifacts.

## 0.6.6 - AI input trust boundary

### Added

- Added `v0.6.6.ai-input-trust-boundary`.
- Added trust-boundary rules that keep AI/user text as proposal input until deterministic normalization, clarification and validation gates pass.

## 0.6.5 - Execution plan semantic invariants

### Added

- Added `v0.6.5.execution-plan-semantic-invariants`.
- Added invariant checks for unique node ids, known dependencies, DAG semantics and target-neutral canonical plans.

## 0.6.4 - Target semantics negative corpus

### Added

- Added `v0.6.4.target-semantics-negative-corpus`.
- Strengthened target semantics negatives for strict manual approval, unsupported condition fallback and rollback portability.

## 0.6.3 - Safety policy matrix

### Added

- Added `v0.6.3.safety-policy-matrix`.
- Added a safety matrix for database migration, cleanup, Kubernetes maintenance, secret rotation, deploy and certificate renewal.

## 0.6.2 - Required clarification contract

### Added

- Added `v0.6.2.required-clarification-contract`.
- Added blocking clarification rules for application, environment, backup, retention, approval owner, maintenance window, secret and certificate identity.

## 0.6.1 - Intent corpus expansion

### Added

- Added `v0.6.1.intent-corpus-expansion`.
- Expanded the reference intent corpus with accepted and blocked real-world automation scenarios.

## 0.6.0 - Standard release candidate baseline

### Added

- Bumped active standard version to `0.6.0`.
- Added `v0.6.0.standard-release-candidate-baseline`.
- The baseline gate preserves the closed v0.5.9 public candidate and verifies export surface closure, bundle verifier pass status, release-profile vector coverage and forbidden-direction boundaries.

## 0.5.9 - Public candidate closure

### Added

- Bumped active standard version to `0.5.9`.
- Added `v0.5.9.public-candidate-closure`.
- Required release profile, standard-candidate conformance level, export manifest, self-verification commands, standard-bundle verifier and conformance vector index to agree.

## 0.5.8 - Standard vector metadata hygiene

### Added

- Added `v0.5.8.standard-vector-metadata-hygiene`.
- Required standard-area public conformance vectors to carry both `introducedIn` and `expected.requiredCheck` metadata.

## 0.5.7 - Export surface closure

### Added

- Added `v0.5.7.export-surface-closure`.
- Required export bundle, standard export manifest and public standard surface to agree on stable artifacts, required JSON artifacts, schemas and evidence artifacts.

## 0.5.6 - Release gate parity

### Added

- Added `v0.5.6.release-gate-parity`.
- Release gates now have parity checks across standard release profile, standard-candidate conformance level and standard export manifest.

## 0.5.5 - Standard bundle version fixture hardening

### Added and changed

- Bumped active standard version to `0.5.5`.
- Included the `standard-version.txt` fixture overwrite fix from corrected 0.5.4 packages.
- Added `v0.5.5.standard-bundle-version-fixture-hardening`.
- Hardened the `v0.5.4.data-driven-conformance-index` gate to accept `0.5.4` or later instead of pinning exactly to `0.5.4`.

## 0.5.4 - Data-driven conformance index

### Added

- Added `conformance-vector-index.json`.
- Added `schemas/conformance-vector-index.schema.json`.
- Added `ConformanceVectorIndexBuilder`.
- Added `v0.5.4.data-driven-conformance-index`.
- Wired vector metadata into public surface, standard export bundle, standard export manifest, conformance levels, standard release profile, conformance manifest and standard draft/export outputs.

## 0.5.3 - Standard bundle verifier

### Added

- Added deterministic `StandardBundleVerifier` for exported standard bundles.
- Added `standard-verify --bundle <dir>` CLI command.
- Added `standard-bundle-verification.json` and schema.
- Updated `standard-export` to emit `conformance-manifest.json`.
- Added `v0.5.3.standard-bundle-verifier`.

## 0.5.2 - Standard export self-verification

### Added

- Extended `standard-export-manifest.json` to manifest version 1.2 with self-verification metadata.
- Added `v0.5.2.standard-export-self-verification`.

## 0.5.1 - Public candidate acceptance gate

### Added

- Added public-candidate acceptance gate metadata without introducing a new public report.
- Extended `standard-export-manifest.json` to manifest version 1.1 with acceptance criteria, release gate checks and evidence artifacts.
- Added `v0.5.1.public-candidate-acceptance-gate`.

## 0.5.0 - Public standard candidate

### Added

- Promoted the v0.4.9 public standard/export baseline to Public Standard Candidate.
- Added `conformance-levels.json` and schema.
- Added `standard-export-manifest.json` and schema.
- Added implementer-facing documentation.
- Added public standard candidate conformance checks.

## 0.4.9 - Standard export bundle

### Added

- Added the Standard Export Bundle contract and `standard-export` CLI command.
- Added `standard-export-bundle.json` and schema.
- Added `v0.4.9.standard-export-bundle`.

## 0.4.8 - Target semantics matrix

### Added

- Added Target Semantics Matrix for Jenkins, GitHub Actions and Tekton portability semantics.
- Added `target-semantics-matrix.json` and schema.
- Added `v0.4.8.target-semantics-matrix`.

## 0.4.7 - Reference intent corpus

### Added

- Added Reference Intent Corpus for portable positive and negative intent scenarios.
- Added `reference-intent-corpus.json` and schema.
- Added `v0.4.7.reference-intent-corpus`.

## 0.4.6 - Compatibility migration policy

### Added

- Added Compatibility and Migration Policy as a public standard contract.
- Added `compatibility-migration-policy.json` and schema.
- Added `v0.4.6.compatibility-migration-policy`.

## 0.4.5 - Public standard surface freeze

### Added

- Added Public Standard Surface freeze contract.
- Added `public-standard-surface.json` and schema.
- Added `v0.4.5.standard-surface-freeze`.

## 0.4.4 - AI proposal review and target expression readiness

### Added and changed

- Wired `IntentProposalReview` into the CLI lowering path.
- Added conformance checks for AI proposal review, condition-expression readiness, no silent condition fallback and behavioral generator equivalence.
- Introduced `TargetExpressionSupport` as the single source of truth for which condition expressions each target can express natively.
- Added reference-semantics behavioral equivalence tests across Jenkins, GitHub Actions and Tekton.
- Added documentation for execution conformance and AI normalization contracts.

## 0.4.3 - Architecture governance guardrails

### Added and fixed

- Added project-level architecture constitution and ADR template.
- Added forbidden-direction, feature-classification, release-checklist and drift-score governance data.
- Added `ArchitectureGovernanceAnalyzer` and conformance coverage.
- Hardened forbidden-term loading from repository YAML.
- Fixed scenario-pack entity over-extraction.
- Closed silent-semantic-fallback gaps for unsupported Tekton conditions.

## 0.4.2 - Target conformance profile and boundary cleanup

### Changed

- Replaced SDK-like target-adapter certification profile with target conformance profile.
- Removed active local runtime direction and replaced it with non-executing plan preview behavior.
- Added conformance checks for target conformance profile and standard boundary.

## 0.4.1 - Semantic correctness hardening

### Added and fixed

- Hardened generator and target semantics around conditions, compatibility and manifest behavior.
- Added regression coverage for behavior preservation and unsupported target conditions.

## 0.4.0 - Public standard draft baseline

### Added

- Added public-standard draft artifacts and standard closure concepts.
- Started moving from prototype behavior toward public standard evidence.

## 0.3.x - Intent, capability and artifact foundation

### Added and evolved

- Added structured `IntentValue` model.
- Added YAML intent loading that preserves maps, lists, secrets, references and expressions.
- Added deterministic scenario-pack normalization.
- Added Standard Intent Catalog.
- Added Intent Design Report and Intent Decision Report.
- Added safety policy validation for approval, dry-run, backup, rollback, change-ticket and destructive-operation gates.
- Added execution-plan canonicalization.
- Added capability negotiation, execution readiness, target selection and target decision trace reports.
- Added target adapter contract, diagnostic catalog, diagnostic coverage, artifact integrity, standard contract index, release profile, artifact evidence and standard compliance reports.
- Added conformance manifest and public artifact bundle support.

## 0.3.0-rc1.8.3 - Foundation correction and AI normalization hardening

### Added and fixed

- Re-aligned Flow with its purpose as an AI-first standardization layer instead of a parser-centered workflow DSL.
- Added scenario packs for deployment, backup/restore, data sync, secret rotation and incident runbook flows.
- Hardened AI intent normalization so normalized deployment paths pass capability validation, AST lowering, Flow validation and execution-plan creation.
- Fixed public JSON serialization to omit null optional fields so schema conformance remains strict and useful.
