# Changelog

This changelog tracks FlowAi as an AI-first, platform-neutral automation standard. It intentionally separates release history from the main README so the README can stay focused on the project idea, architecture and usage.

The project boundary remains unchanged across releases unless explicitly stated: no runtime executor, no SDK-first architecture, no plugin lifecycle framework, no target-specific public DSL and no Jenkins-specific language.

## 0.8.0-b2 - Conformance quality-gate wiring and documentation cleanup

- Added executable conformance quality gates for:
  - `v0.8.0.core-contract-check`,
  - `v0.8.0.scenario-pack-quality-gate`.
- Wired the quality gates into full conformance summaries so the CLI conformance result includes them without rewriting the large `ConformanceRunner.kt` implementation.
- Added public conformance vectors for the new quality-gate check ids.
- Updated the conformance vector index so the new quality-gate check ids are recognized as runner-backed checks.
- Reworked `README.md` into the primary English project documentation with project purpose, architecture, safety model, usage and development rules.
- Moved version-history responsibility into this changelog.
- Added an Apache-2.0 license marker in `LICENSE`.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.

## 0.8.0 - Core contract check

- Kept package version at `0.8.0` while leaving the active public standard version controlled by `FlowStandardVersions.FLOW_STANDARD_VERSION`.
- Added `CoreContractCheck` for verifying that required stable artifacts exist, stable artifact identifiers are unique, candidate checks are unique and `StandardModel` remains well formed.
- Added unit coverage for the core contract check.
- Documented the change in `docs/V0_8_0_CORE_CONTRACT_CHECK.md`.
- Did not change Flow syntax, renderer behavior, target semantics or runtime behavior.

## 0.7.7 - Scenario pack quality analyzer

- Added `ScenarioPackQualityAnalyzer` for scenario-pack metadata quality, useful examples, duplicate pack ids and blocked-scenario coverage.
- Added v0.7.7 work-package metadata for scenario-pack quality gates.
- Kept the active public standard version unchanged until public runner and snapshot wiring can be handled safely.
- Later B2 work wires scenario-pack quality into conformance.

## 0.7.6 - Semantic correctness hardening

- Bumped the active standard version to `0.7.6`.
- Fixed Kubernetes deploy lowering so generated target manifests carry the application identity instead of falling back to a literal `app`.
- Fixed runtime input reference rendering so references such as `environment` and `version` become target-native runtime parameters.
- Centralized target parameter interpolation across generated command types.
- Moved mandatory capability safety enforcement into the core intent validator, covering manual intent files and AI-normalized intents consistently.
- Fixed validator block scoping for sibling statements in conditional, parallel, match, try/retry and error-handler blocks.
- Preserved mixed safe-navigation semantics per path segment.
- Added single-quoted string support to the lexer and removed global quote rewriting in condition parsing.
- Fixed exact `${name}` intent YAML references.
- Removed Jenkins regex `/.*/` fallback for named patterns.
- Removed implicit DAG parallelization from intent `requires`; authored order remains deterministic unless parallelism is explicit.
- Deduplicated cycle diagnostics in intent step dependency validation.
- Regenerated rendered end-to-end snapshots and made rendered snapshot conformance exact.

### 0.7.6 fix1

- Fixed Kotlin compilation in `TargetExpressionTranslator` by avoiding an invalid smart-cast on `BinaryExpressionNode.right`.
- Corrected mandatory safety enforcement so high-risk capability obligations are reported by `IntentCapabilityValidator` instead of being silently synthesized before validation.
- Added semantic smoke validation for Kubernetes deploy identity, Jenkins runtime input rendering, mandatory database migration safety, safe-navigation preservation and Jenkins named-pattern rendering.

### 0.7.6 fix2

- Fixed stale exact rendered snapshots for GitHub Actions and Tekton.
- Preserved exact snapshot conformance instead of weakening or bypassing the gate.
- Kept the active standard version at `0.7.6`; this was a corrective package fix, not a new standard feature layer.

## 0.7.5 - Purpose coverage ratio

- Bumped the active standard version to `0.7.5`.
- Added `v0.7.5.purpose-coverage-ratio` as a behavior-quality gate tied to the public reference intent corpus and `StandardModel`.
- Added `PurposeCoverageAnalyzer` and tests for missing purpose capabilities, missing blocked risk scenarios, governance-heavy release profiles and weak purpose evidence.
- Added a frozen `standard-model-baseline-v0.7.4.yaml` so the delta checks compare against the immediate previous release.
- Relaxed the v0.7.4 architecture-delta gate for future versions so it verifies the invariant instead of hard-coding that no later release may add a check.
- Fixed a duplicate YAML key in the v0.7.3 baseline without changing its semantic value.

## 0.7.4 - Architecture delta analyzer

- Added `v0.7.4.architecture-delta-analyzer`.
- Added architecture delta measurement against the previous standard-model baseline.
- Verified that the release adds only the intended governance measurement and does not grow the stable public artifact surface.
- Kept the change scoped to architecture-delta validation.

## 0.7.3 - Standard model projection coherence

- Bumped the active standard version to `0.7.3`.
- Collapsed legacy registry-consistency gates into `v0.7.3.standard-model-projection-coherence`.
- Removed redundant public conformance vectors that only compared projections of the same `StandardModel`.
- Kept `StandardModel` as the single source of truth for public surface, export bundle, export manifest, conformance levels and release profile.
- Enforced falsifiability and evidence anchors in `StandardModel.wellFormednessIssues(rootDir)`.
- Renamed architecture scoring mode from `negative-delta-only` to `negative-signal-only` until a true two-version delta analyzer exists.

## 0.7.1 - Architecture debt cleanup and governance integration

- Bumped the active standard version to `0.7.1`.
- Added `v0.7.1.architecture-debt-cleanup-and-drift-enforcement`.
- Architecture governance now enforces `drift-score.yaml` instead of merely checking that the file exists.
- Added report-budget validation for public standard-surface artifacts.
- Added release-gate classifications so registry-consistency gates are visible as registry evidence, not behavior coverage.
- Added public compatibility alias invariants for duplicated public fields that must remain synchronized until the cleanup window.
- Removed the unused private `FlowValidator.isPlainString` helper.
- Added an ADR documenting the AST data/orchestration boundary so Flow does not quietly expand into a general-purpose language.
- Added `StandardModel` as the single source of truth for public standard artifacts and release checks.
- Reworked `StandardReleaseProfile`, `StandardSurface`, conformance levels and export manifest release-gate projections to derive from `StandardModel`.
- Converted `StandardArtifactRegistry` into a compatibility facade over `StandardModel`.
- Replaced release-gate category string heuristics with explicit `GateKind` data.

## 0.7.0 - Reference corpus execution harness

- Bumped the active standard version to `0.7.0`.
- Added `v0.7.0.reference-corpus-execution-harness` conformance coverage and vector.
- Added `ReferenceCorpusExecutionHarness` to replay every public reference intent scenario through the real scenario-pack normalizer, proposal review, intent validation, AST validation and execution-plan planning.
- Added regression checks for production deployments without explicit approval, rollback-only review lowering, secret rollout verification semantics and build/test/deploy routing.
- Fixed stale runner-check aliases for canonical execution plans, safety policy validation and capability module contracts.

## 0.6.9 - Release-candidate freeze and approval fixes

- Bumped the active standard version to `0.6.9`.
- Added `v0.6.9.release-candidate-freeze` conformance coverage and vector.
- Required all 0.6.x release-candidate gates, bundle verification, vector closure and architecture non-goals to hold.
- Fixed deterministic normalization so production deployment no longer synthesizes approval steps or approval policies merely because the target environment is production.
- Production deployment without explicit approval remains approval-free and is blocked by `SAFETY_REQUIRES_APPROVAL`.
- Explicit approval requests still emit the approval step and approval policy.
- Kubernetes production or disruptive maintenance no longer auto-approves itself.
- Fixed rollback-only review normalization so `Rollback the last release.` remains lowerable.
- Missing rollback application context is a recommended review question, not a blocking required clarification.

## 0.6.8 - Compatibility promise

- Added `v0.6.8.compatibility-promise` conformance coverage and vector.
- Added compatibility-promise rules for patch, minor, major and all-release expectations without expanding the public artifact surface.

## 0.6.7 - Standard example bundle

- Added `v0.6.7.standard-example-bundle` conformance coverage and vector.
- Added golden accepted and blocked examples tied to existing public artifacts.

## 0.6.6 - AI input trust boundary

- Added `v0.6.6.ai-input-trust-boundary` conformance coverage and vector.
- Added trust-boundary rules that keep AI/user text as proposal input until deterministic normalization, clarification and validation gates pass.

## 0.6.5 - Execution plan semantic invariants

- Added `v0.6.5.execution-plan-semantic-invariants` conformance coverage and vector.
- Added invariant checks for unique node ids, known dependencies, DAG semantics and target-neutral canonical plans.

## 0.6.4 - Target semantics negative corpus

- Added `v0.6.4.target-semantics-negative-corpus` conformance coverage and vector.
- Strengthened target semantics negatives for strict manual approval, unsupported condition fallback and rollback portability.

## 0.6.3 - Safety policy matrix

- Added `v0.6.3.safety-policy-matrix` conformance coverage and vector.
- Added a safety matrix for database migration, cleanup, Kubernetes maintenance, secret rotation, deploy and certificate renewal.

## 0.6.2 - Required clarification contract

- Added `v0.6.2.required-clarification-contract` conformance coverage and vector.
- Added blocking clarification rules for application, environment, backup, retention, approval owner, maintenance window, secret and certificate identity.

## 0.6.1 - Intent corpus expansion

- Added `v0.6.1.intent-corpus-expansion` conformance coverage and vector.
- Expanded the reference intent corpus with more accepted and blocked real-world automation scenarios.

## 0.6.0 - Standard release candidate baseline

- Bumped the active standard version to `0.6.0`.
- Added `v0.6.0.standard-release-candidate-baseline` conformance coverage and vector.
- Preserved the closed v0.5.9 public candidate and verified export surface closure, bundle verification, vector closure and architecture non-goals.

## 0.5.9 - Public candidate closure

- Bumped the active standard version to `0.5.9`.
- Added `v0.5.9.public-candidate-closure` conformance coverage and vector.
- Required release profile, standard-candidate conformance level, export manifest, self-verification commands, standard-bundle verifier and conformance vector index to agree.

## 0.5.8 - Standard vector metadata hygiene

- Added `v0.5.8.standard-vector-metadata-hygiene` conformance coverage and vector.
- Required standard-area public conformance vectors to carry `introducedIn` and `expected.requiredCheck` metadata.

## 0.5.7 - Export surface closure

- Added `v0.5.7.export-surface-closure` conformance coverage and vector.
- Required the export bundle, standard export manifest and public standard surface to agree on stable artifacts, required JSON artifacts, schemas and evidence artifacts.

## 0.5.6 - Release-gate parity

- Added `v0.5.6.release-gate-parity` conformance coverage and vector.
- Added explicit parity checks across standard release profile, standard-candidate conformance level and standard export manifest.

## 0.5.5 - Bundle version fixture hardening

- Bumped the active standard version to `0.5.5`.
- Included the `standard-version.txt` fixture overwrite fix from the corrected 0.5.4 package.
- Added `v0.5.5.standard-bundle-version-fixture-hardening` conformance coverage and vector.
- Hardened the v0.5.4 data-driven conformance index gate so it accepts 0.5.4 or later.

## 0.5.4 - Data-driven conformance index

- Added `conformance-vector-index.json` and `schemas/conformance-vector-index.schema.json`.
- Added `ConformanceVectorIndexBuilder`, which reads public `.conformance.yaml` vector metadata.
- Added `v0.5.4.data-driven-conformance-index` conformance coverage and vector.
- Wired the artifact into the public standard surface, standard export bundle, standard export manifest, conformance levels, standard release profile, conformance manifest and standard draft/export outputs.
- Added required-check metadata to older public conformance vectors.

## 0.5.3 - Standard bundle verifier

- Added deterministic Standard Bundle Verifier for exported standard bundles.
- Added `standard-verify --bundle <dir>`.
- Added `standard-bundle-verification.json` and schema coverage.
- Updated `standard-export` to emit `conformance-manifest.json` because it is a declared self-verification input.
- Extended `standard-export-manifest.json` to manifest version 1.3.
- Added `v0.5.3.standard-bundle-verifier` conformance coverage and vector.

## 0.5.2 - Standard export self-verification

- Added standard export self-verification metadata without introducing a new public report.
- Extended `standard-export-manifest.json` to manifest version 1.2 with self-verification commands, bundle verification checks and verification inputs.
- Added `v0.5.2.standard-export-self-verification` conformance coverage and vector.

## 0.5.1 - Public candidate acceptance gate

- Added a public-candidate acceptance gate without introducing a new public report.
- Extended `standard-export-manifest.json` to manifest version 1.1 with acceptance criteria, release gate checks and evidence artifacts.
- Added `v0.5.1.public-candidate-acceptance-gate` conformance coverage and vector.

## 0.5.0 - Public standard candidate

- Promoted the v0.4.9 public standard/export baseline to Public Standard Candidate.
- Added `conformance-levels.json`, `standard-export-manifest.json` and their schemas.
- Added implementer-facing documentation.
- Added conformance checks for conformance levels, export manifest and public standard candidate behavior.

## 0.4.9 - Standard export bundle

- Added the Standard Export Bundle contract and `standard-export` CLI command.
- Added `standard-export-bundle.json` and schema coverage.
- Added `v0.4.9.standard-export-bundle` conformance coverage and vector.
- Hardened the public standard surface from 0.4.5 to 0.4.9 by making corpus scenarios executable, fixing entity extraction, reconciling schemas against the filesystem and aligning export bundle checks with the public surface.

## 0.4.8 - Target semantics matrix

- Added Target Semantics Matrix as a public artifact for Jenkins, GitHub Actions and Tekton portability semantics.
- Added `target-semantics-matrix.json` and schema coverage.
- Added `v0.4.8.target-semantics-matrix` conformance coverage and vector.

## 0.4.7 - Reference intent corpus

- Added Reference Intent Corpus for portable positive and negative intent scenarios.
- Added `reference-intent-corpus.json` and schema coverage.
- Added `v0.4.7.reference-intent-corpus` conformance coverage and vector.

## 0.4.6 - Compatibility migration policy

- Added Compatibility and Migration Policy as a public standard contract.
- Added `compatibility-migration-policy.json` and schema coverage.
- Added `v0.4.6.compatibility-migration-policy` conformance coverage and vector.

## 0.4.5 - Public standard surface freeze

- Added Public Standard Surface freeze contract.
- Added `public-standard-surface.json` and schema coverage.
- Added `v0.4.5.standard-surface-freeze` conformance coverage and vector.

## 0.4.4 - AI proposal review and behavior gates

- Wired `IntentProposalReview` into the CLI lowering path so `normalize --lower`, `--pipeline` and `--render` refuse rejected proposals before lowering.
- Added public conformance checks for AI proposal review, condition expression readiness, no silent condition fallback and behavioral generator equivalence.
- Introduced `TargetExpressionSupport` as the single source of truth for native condition support.
- Made untranslatable target guards execution-readiness blockers instead of silently dropped conditions.
- Added typed `PolicyCondition` handling for safety validation.
- Added behavioral conformance tests comparing reference semantics across Jenkins, GitHub Actions and Tekton.
- Added `docs/EXECUTION_CONFORMANCE.md` and `docs/AI_NORMALIZATION_CONTRACT.md`.

## 0.4.3 - Architecture governance guardrails

- Added `docs/ARCHITECTURE_CONSTITUTION.md` and ADR templates.
- Added architecture governance data under `standard/architecture`.
- Added `ArchitectureGovernanceAnalyzer` and `v0.4.3.architecture-governance-guardrails` conformance coverage.
- Hardened forbidden-direction checks so source terms are loaded from governance YAML instead of being duplicated in Kotlin.
- Fixed scenario-pack entity over-extraction where command verbs were captured as entity values.
- Closed a silent-semantic-fallback gap for Tekton conditions.

## 0.4.2 - Standard boundary no SDK/runtime

- Replaced SDK-like target adapter certification language with target conformance language.
- Removed the active local runtime path and replaced it with non-executing plan preview behavior.
- Added conformance coverage for target conformance profiles and standard-boundary no-SDK/runtime checks.

## 0.4.1 - Semantic correctness hardening

- Hardened generated target semantics across Jenkins, GitHub Actions and Tekton.
- Preserved condition behavior and target manifest structure.
- Added semantic checks that focus on behavior instead of only golden text.

## 0.4.0 - Public standard draft

- Added public standard draft generation and standard draft artifacts.
- Introduced the first structured public draft contract.

## 0.3.23 - Negative conformance corpus

- Added negative conformance corpus coverage for unsafe or unsupported automation intent.

## 0.3.22 - Reference corpus

- Added reference corpus index and reference scenario documentation.

## 0.3.21 - Compatibility policy

- Added compatibility policy as a public standard artifact.

## 0.3.20 - Standard freeze report

- Added standard freeze report and related conformance coverage.

## 0.3.19 - Standard compliance and evidence reports

- Added `StandardContractIndexAnalyzer`, `standard-contract-index.json`, `StandardReleaseProfile`, `standard-release-profile.json`, `ArtifactEvidenceAnalyzer`, `artifact-evidence-report.json`, `StandardComplianceAnalyzer` and `standard-compliance-report.json`.

## 0.3.15 - Artifact integrity report

- Added `ArtifactIntegrityAnalyzer`, `artifact-integrity-report.json`, schema coverage, required-artifact presence checks, standard-version consistency checks and conformance/JUnit coverage.

## 0.3.14 - Diagnostic coverage report

- Added `DiagnosticCoverageAnalyzer`, `diagnostic-coverage-report.json`, schema coverage and checks for observed, unknown and unused diagnostic codes.
- Integrated diagnostic coverage into intent and normalization lowering pipelines.

## 0.3.13 - Standard diagnostic catalog

- Added `StandardDiagnosticCatalog`, `standard-diagnostic-catalog.json`, schema coverage and the `diagnostics` CLI command.

## 0.3.12 - Target adapter contract

- Added `TargetAdapterContractAnalyzer`, `target-adapter-contract.json`, `adapter-diagnostics.json` and schemas.
- Added adapter invariants such as `ADAPTER_MUST_NOT_READ_INTENT` and `ADAPTER_MUST_RESPECT_READINESS`.

## 0.3.11 - Conformance manifest

- Added `ConformanceManifestBuilder`, `conformance-manifest.json`, schema coverage and `conformance --out <dir>` export support.

## 0.3.10 - Public artifact bundle

- Added `FlowArtifactBundleAnalyzer`, `flow-artifact-bundle.json`, schema coverage and artifact metadata such as role, schema, required flag, derived flag, pipeline index and sources.

## 0.3.9 - Target decision trace

- Added `TargetDecisionTraceAnalyzer`, `target-decision-trace-report.json`, schema coverage, decision trace steps and target explanations for recommended, degraded and blocked targets.

## 0.3.8 - Target selection report

- Added `TargetSelectionAnalyzer`, `target-selection-report.json`, schema coverage and ranked target candidates with readiness, portability score, blocker count and warning count.

## 0.3.7 - Execution readiness report

- Added `ExecutionReadinessAnalyzer`, `execution-readiness-report.json`, schema coverage and readiness fields such as generation allowance, production readiness, blockers, warnings and required actions.

## 0.3.6 - Execution plan portability

- Added normalized portability scoring to the capability negotiation report, including per-target scores, portable capabilities, target-specific capabilities, blocking portability issues and required workarounds.

## 0.3.5 - Intent decision model

- Added `IntentDecisionAnalyzer`, `intent-decision-report.json`, schema coverage, blocking decisions for cleanup retention, database migration backup and production deploy approval, and recommended checks for backup schedule timezone.

## 0.3.4 - Capability module contracts

- Added capability module contract reports via `modules`.
- Added module descriptor contract v1.2 with secrets, required capabilities and target implications.
- Removed module-owned runtime, entrypoint, template and generator concepts from the public module contract.
- Added architecture guardrails ensuring modules do not own target rendering.

## 0.3.2 - Canonical execution plan and safety policies

- Added first-class `SafetyPolicyValidator` for approval, dry-run, backup, rollback, change-ticket and destructive-operation gates.
- Added canonical lowercase `canonical-execution-plan.json` export.
- Added adapter-facing YAML loader namespace with backward-compatible legacy loaders.
- Tightened cleanup and Kubernetes maintenance scenario-pack safety behavior.

## 0.3.1 - Scenario packs and capability negotiation

- Added scenario-pack vectors and capability negotiation reports.
- Added scenario packs for deployment, backup/restore, data sync, secret rotation and incident runbooks.
- Added commands for listing scenarios and scenario examples.

## 0.3.0-rc1.8.3 - Foundation correction and AI normalization hardening

- Corrected the project direction toward Flow as an AI-first standardization layer rather than a low-level parser demo.
- Added structured `IntentValue` handling so YAML maps, lists, secrets, refs and expressions remain structured after loading.
- Preserved flow-style and block-style YAML without flattening complex values into JSON strings.
- Added `IntentDesignReport` with convention assumptions so hidden defaults are visible.
- Made target manifests node-preserving and based on `ExecutionPlan.nodes`, not only flattened tasks.
- Added target capability registry support for fine-grained feature keys such as `approval.inline`, `parallel.dag`, `loops.dynamic` and `secrets.runtime`.
- Moved the Standard Intent Catalog toward executable capability contracts with required params, systems, lowering strategy and target implications.
- Hardened AI intent normalization so normalized deployment paths pass scenario-pack normalization, intent capability validation, AST lowering, Flow validation and execution-plan planning.
- Added deterministic scenario-pack based normalization as the default provider-neutral path.
- Added commands for intent generation, normalization, catalog inspection, target inspection and module inspection.
