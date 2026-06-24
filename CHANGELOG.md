# 0.7.6-semantic-correctness-hardening-fix2

- Fixed full offline Gradle test failure caused by stale exact rendered snapshots for GitHub Actions and Tekton.
- Regenerated `conformance/snapshots/build-test-deploy/github-actions.yml` and `conformance/snapshots/build-test-deploy/tekton-pipeline.yaml` from the reviewed v0.7.6 rendering path.
- Preserved exact snapshot conformance instead of weakening or bypassing the gate.
- Verified `./gradlew --offline --no-daemon clean test --stacktrace --console=plain`: 151 tests, 0 failures, 0 errors, 0 skipped.
- Verified `./gradlew --offline --no-daemon run --args="conformance" --console=plain`: 76 passed, 0 failed.
- Kept the active standard version at `0.7.6`; this is a corrective package fix, not a new standard feature layer.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.

# 0.7.6-semantic-correctness-hardening-fix1

- Fixed Kotlin compilation in `TargetExpressionTranslator` by avoiding an invalid smart-cast on `BinaryExpressionNode.right`.
- Corrected mandatory safety enforcement so high-risk capability obligations are reported by `IntentCapabilityValidator` instead of being silently synthesized before validation.
- Verified the main source surface through dependency-ordered `kotlinc` compilation in the offline sandbox.
- Added semantic smoke validation for Kubernetes deploy identity, Jenkins runtime input rendering, mandatory database migration safety, safe-navigation preservation and Jenkins named-pattern rendering.
- Kept the active standard version at `0.7.6`; this is a corrective fix for the semantic-correctness-hardening release, not a new standard feature layer.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.

# 0.7.6-semantic-correctness-hardening

- Bumped the active standard version to `0.7.6`.
- Fixed Kubernetes deploy lowering so generated target manifests carry the application identity instead of falling back to a literal `app`.
- Fixed runtime input reference rendering so bare references such as `environment` and `version` become target-native runtime parameters.
- Centralized target parameter interpolation across generated command types.
- Moved mandatory capability safety enforcement into the core intent validator, covering manual intent files and AI-normalized intents equally.
- Fixed validator block scoping for sibling statements in conditional, parallel, match, try/retry and error-handler blocks.
- Preserved mixed safe-navigation semantics per path segment.
- Added single-quoted string support to the lexer and removed global quote rewriting in condition parsing.
- Fixed exact `${name}` intent YAML references.
- Removed Jenkins regex `/.*/` fallback for named patterns.
- Removed implicit DAG parallelization from intent `requires`; authored order remains deterministic unless parallelism is explicit.
- Deduplicated cycle diagnostics in intent step dependency validation.
- Regenerated rendered end-to-end snapshots and made rendered snapshot conformance exact.

# 0.7.5-purpose-coverage-ratio

- Bumped the active standard version to `0.7.5`.
- Added `v0.7.5.purpose-coverage-ratio` as a behavior-quality gate tied to the public reference intent corpus and `StandardModel`.
- Added `PurposeCoverageAnalyzer` and tests for missing purpose capabilities, missing blocked risk scenarios, governance-heavy release profiles and weak purpose evidence.
- Added a frozen `standard-model-baseline-v0.7.4.yaml` so v0.7.5 delta checks compare against the immediate previous release.
- Relaxed the v0.7.4 architecture-delta gate for future versions so it verifies the invariant instead of hard-coding that no later release may add a check.
- Fixed a duplicate YAML key in the v0.7.3 baseline without changing its semantic value.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.

# 0.7.3-standard-model-projection-coherence

- Bumped the active standard version to `0.7.3`.
- Collapsed the legacy registry-consistency gate set into one `v0.7.3.standard-model-projection-coherence` gate.
- Removed redundant public conformance vectors for self-registration/parity/closure/freeze gates that only compared projections of the same `StandardModel`.
- Kept `StandardModel` as the single source of truth for public surface, export bundle, export manifest, conformance levels and release profile.
- Enforced falsifiability/evidence anchors in `StandardModel.wellFormednessIssues(rootDir)`.
- Renamed architecture scoring mode from `negative-delta-only` to `negative-signal-only` until a true two-version delta analyzer exists.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change was added.

# 0.7.1-standard-model-governance-integration

- Added `StandardModel` as the single source of truth for public standard artifacts and release checks.
- Reworked `StandardReleaseProfile`, `StandardSurface`, conformance levels and export manifest release-gate projections to derive from `StandardModel`.
- Converted `StandardArtifactRegistry` into a compatibility facade over `StandardModel` instead of a second canonical list.
- Replaced release-gate category string heuristics with explicit `GateKind` data.
- Strengthened architecture report-budget validation with `StandardModel.wellFormednessIssues()`, validation roles for stable artifacts and a one-third registry-consistency budget.
- Added regression coverage proving release profile, candidate level, export manifest and public surface are derived from the model.
- Kept the change scoped to public standard governance and projections; no execution layer, target-specific public DSL or Flow syntax expansion was added.

# 0.7.1-governance-fix

- Corrected architecture drift scoring so existing baseline artifacts are reported as context but do not create a positive score floor.
- A single forbidden-direction regression such as `WorkflowExecutor` now fails drift scoring instead of being hidden by accumulated positive repository state.
- Tightened the report-budget rule: registry-consistency bookkeeping must stay below one third of the active release profile.
- Added a canonical `StandardArtifactRegistry` so public surface, release profile, conformance levels and export manifest projections derive from one source instead of parallel hand-maintained lists.
- Added a falsifying fixture for the smallest credible runtime-direction drift regression.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL or syntax expansion was added.

# 0.7.1

- Bumped the active standard version to `0.7.1`.
- Added `v0.7.1.architecture-debt-cleanup-and-drift-enforcement`.
- Architecture governance now enforces `drift-score.yaml` instead of merely checking that the file exists.
- Added report-budget validation for public standard-surface artifacts.
- Added release-gate classifications so registry-consistency gates are visible as registry evidence, not behavior coverage.
- Added public compatibility alias invariants for duplicated public fields that must remain synchronized until the 0.8.0 cleanup window.
- Removed the unused private `FlowValidator.isPlainString` helper.
- Added an ADR documenting the AST data/orchestration boundary so Flow does not quietly expand into a general-purpose language.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL or syntax expansion was added.

# 0.7.0-fixed

- Fixed the reference-corpus execution harness conformance gate so its vector-index closure uses the canonical runner check ids.
- Corrected stale runner-check aliases for `v0.3.2.execution-plan.canonical`, `v0.3.2.safety-policy-validation` and `v0.3.4.capability-module-contracts`.
- This keeps the v0.7.0 harness strict without falsely reporting public vectors as missing from the runner.

# 0.7.0

- Bumped the active standard version to `0.7.0`.
- Added `v0.7.0.reference-corpus-execution-harness` conformance coverage and vector.
- Added `ReferenceCorpusExecutionHarness` to replay every public reference intent scenario through the real scenario-pack normalizer, proposal review, intent validation, AST validation and execution-plan planning.
- Added regression checks for production deployments without explicit approval, rollback-only review lowering, secret rollout verification semantics and build/test/deploy routing.
- Preserved the standard boundary: no runtime executor, SDK API, plugin lifecycle or target-specific public DSL.

# 0.6.9-fixed-approval-review

- Fixed rollback-only review normalization so `Rollback the last release.` remains lowerable.
- Missing rollback application context is now a recommended review question, not a blocking required clarification.
- Rollback-only intents still do not synthesize deployment steps.
- Added regression coverage for rollback review intents having no required clarification before lowering.

# 0.6.9-fixed-approval

- Fixed deterministic normalization so production deployment no longer synthesizes an `APPROVE` step or `APPROVAL` policy merely because the target environment is production.
- Production deployment without explicit approval now remains approval-free and is blocked by `SAFETY_REQUIRES_APPROVAL`.
- Explicit approval requests still emit the approval step and approval policy, preserving the positive production deployment path.
- Kubernetes production or disruptive maintenance no longer auto-approves itself; dry-run and maintenance-window obligations remain explicit safety requirements.
- Restored reference-corpus alignment for secret rollout verification, rollback-only intents, build/test/deploy routing and backup retention entities.

# 0.6.9

- Bumped the active standard version to `0.6.9`.
- Added `v0.6.9.release-candidate-freeze` conformance coverage and vector.
- The freeze gate requires every 0.6.x release-candidate gate, bundle verification, vector closure and architecture non-goals to hold.

# 0.6.8

- Added `v0.6.8.compatibility-promise` conformance coverage and vector.
- Added compatibility-promise rules for patch, minor, major and all-release expectations without expanding the public artifact surface.

# 0.6.7

- Added `v0.6.7.standard-example-bundle` conformance coverage and vector.
- Added golden accepted/blocked examples tied to existing public artifacts.

# 0.6.6

- Added `v0.6.6.ai-input-trust-boundary` conformance coverage and vector.
- Added trust-boundary rules that keep AI/user text as proposal input until deterministic normalization, clarification and validation gates pass.

# 0.6.5

- Added `v0.6.5.execution-plan-semantic-invariants` conformance coverage and vector.
- Added invariant checks for unique node ids, known dependencies, DAG semantics and target-neutral canonical plans.

# 0.6.4

- Added `v0.6.4.target-semantics-negative-corpus` conformance coverage and vector.
- Strengthened target semantics negatives for strict manual approval, unsupported condition fallback and rollback portability.

# 0.6.3

- Added `v0.6.3.safety-policy-matrix` conformance coverage and vector.
- Added a safety matrix for database migration, cleanup, Kubernetes maintenance, secret rotation, deploy and certificate renewal.

# 0.6.2

- Added `v0.6.2.required-clarification-contract` conformance coverage and vector.
- Added blocking clarification rules for application, environment, backup, retention, approval owner, maintenance window, secret and certificate identity.

# 0.6.1

- Added `v0.6.1.intent-corpus-expansion` conformance coverage and vector.
- Expanded the reference intent corpus with more accepted and blocked real-world automation scenarios.

# 0.6.0

- Bumped the active standard version to `0.6.0`.
- Added `v0.6.0.standard-release-candidate-baseline` conformance coverage and vector.
- The baseline gate preserves the closed v0.5.9 public candidate and verifies:
  - export surface closure,
  - standard-bundle verifier PASS,
  - release-profile vector coverage,
  - no runtime executor,
  - no SDK/plugin lifecycle,
  - no silent semantic fallback.
- Kept the change scoped to standard release-candidate verification; no runtime executor, SDK, plugin lifecycle, target-specific DSL or new Flow syntax was introduced.

# 0.5.9

- Bumped the active standard version to `0.5.9`.
- Added `v0.5.9.public-candidate-closure` conformance coverage and vector.
- The closure gate requires release profile, standard-candidate conformance level, export manifest, self-verification commands, standard-bundle verifier and conformance vector index to agree.
- Kept the change scoped to public-candidate verification; no runtime executor, SDK, plugin lifecycle, target-specific DSL or new Flow syntax was introduced.

# 0.5.8

- Added `v0.5.8.standard-vector-metadata-hygiene` conformance coverage and vector.
- Standard-area public conformance vectors must carry both `introducedIn` and `expected.requiredCheck` metadata.
- Kept the change scoped to vector corpus hygiene and auditability.

# 0.5.7

- Added `v0.5.7.export-surface-closure` conformance coverage and vector.
- The export bundle, standard export manifest and public standard surface must agree on stable artifacts, required JSON artifacts, schemas and evidence artifacts.
- Kept the change scoped to portable standard export consistency.

# 0.5.6

- Added `v0.5.6.release-gate-parity` conformance coverage and vector.
- Release gates now have an explicit parity check across standard release profile, standard-candidate conformance level and standard export manifest.
- Kept the change scoped to release-gate consistency.

# 0.5.5

- Bumped the active standard version to `0.5.5`.
- Included the `standard-version.txt` fixture overwrite fix from the corrected `0.5.4` package.
- Added `v0.5.5.standard-bundle-version-fixture-hardening` conformance coverage and vector.
- Hardened the `v0.5.4.data-driven-conformance-index` gate so it accepts `0.5.4` or later instead of pinning the release line to exactly `0.5.4`.
- Wired the new hardening gate into:
  - conformance levels
  - standard export manifest
  - standard release profile
  - conformance manifest
  - conformance vector index coverage
- Kept the change scoped to release verification and conformance evidence; no runtime executor, SDK, plugin lifecycle, target-specific DSL or new Flow syntax was introduced.

# 0.5.4

- Added data-driven Conformance Vector Index.
- Added public artifact:
  - `conformance-vector-index.json`
  - `schemas/conformance-vector-index.schema.json`
- Added `ConformanceVectorIndexBuilder`, which reads public `.conformance.yaml` vector metadata.
- Added `v0.5.4.data-driven-conformance-index` conformance coverage and vector.
- Wired the new artifact into:
  - public standard surface
  - standard export bundle
  - standard export manifest
  - conformance levels
  - standard release profile
  - conformance manifest
  - standard draft/export outputs
- Added required-check metadata to older public conformance vectors so the vector corpus is auditable.
- Kept the change scoped to conformance metadata and release evidence; no runtime executor, SDK, plugin lifecycle, target-specific DSL or new Flow syntax was introduced.

# 0.5.3

- Added deterministic Standard Bundle Verifier for exported standard bundles.
- Added CLI command:
  - `standard-verify --bundle <dir>`
- Added machine-readable verifier output:
  - `standard-bundle-verification.json`
  - `schemas/standard-bundle-verification.schema.json`
- Updated `standard-export` to emit `conformance-manifest.json`, because it is a declared self-verification input.
- Extended `standard-export-manifest.json` to `manifestVersion` 1.3 by adding the `standard-verify` command to self-verification metadata.
- Added `v0.5.3.standard-bundle-verifier` conformance coverage and vector.
- Wired the new gate into:
  - `conformance-levels.json`
  - `standard-release-profile.json`
  - `conformance-manifest.json`
- Kept the change scoped to public standard-bundle verification; no runtime executor, SDK, plugin lifecycle, target-specific DSL or new Flow syntax was introduced.

# 0.5.2

- Added standard export self-verification metadata without introducing a new public report.
- Extended `standard-export-manifest.json` to `manifestVersion` 1.2 with:
  - `selfVerificationCommands`
  - `bundleVerificationChecks`
  - `verificationInputs`
- Added `v0.5.2.standard-export-self-verification` conformance coverage and vector.
- Wired the new gate into:
  - `conformance-levels.json`
  - `standard-release-profile.json`
  - `conformance-manifest.json`
- Kept the change scoped to portable standard-bundle verification; no runtime executor, SDK, plugin lifecycle, target-specific DSL or new Flow syntax was introduced.

# 0.5.1

- Added a public-candidate acceptance gate without introducing a new public report.
- Extended `standard-export-manifest.json` to `manifestVersion` 1.1 with:
  - `acceptanceCriteria`
  - `releaseGateChecks`
  - `evidenceArtifacts`
- Added `v0.5.1.public-candidate-acceptance-gate` conformance coverage and vector.
- Wired the new gate into:
  - `conformance-levels.json`
  - `standard-release-profile.json`
  - `conformance-manifest.json`
- Kept the change scoped to standard acceptance evidence; no runtime executor, SDK, plugin lifecycle, target-specific DSL or new Flow syntax was introduced.

# 0.5.0

- Promoted the v0.4.9 public standard/export baseline to Public Standard Candidate.
- Added `conformance-levels.json` and `schemas/conformance-levels.schema.json`.
- Added `standard-export-manifest.json` and `schemas/standard-export-manifest.schema.json`.
- Added implementer-facing documentation:
  - `docs/IMPLEMENTER_GUIDE.md`
  - `docs/STANDARD_EXPORT_MANIFEST.md`
- Added conformance checks:
  - `v0.5.0.conformance-levels`
  - `v0.5.0.standard-export-manifest`
  - `v0.5.0.public-standard-candidate`
- Kept the change scoped to public standard candidate evidence; no runtime executor, SDK, plugin lifecycle or target-specific public DSL was introduced.

# 0.4.9

- Added the Standard Export Bundle contract and `standard-export` CLI command.
- Added `standard-export-bundle.json` and `schemas/standard-export-bundle.schema.json`.
- Added `v0.4.9.standard-export-bundle` conformance coverage and vector.

## Conformance hardening for the public standard surface (0.4.5–0.4.9 artifacts)

- `v0.4.7.reference-intent-corpus`: the corpus is now executable. Each scenario carries the actual
  input text and a verified expectation; the check replays every scenario through the real
  `ScenarioPackIntentNormalizer` and `IntentProposalReview` gate and asserts the produced status,
  capabilities, required clarifications, rejection codes and extracted entities. Corpus version
  1.0 -> 2.0. The corpus covers 10 verified scenarios (5 accepted, 5 blocked). `database-migration-with-backup`
  is a genuine accepted scenario: a backup step paired with an explicit rollback mitigates the migration
  high risk. `certificate-renewal` is blocked on the `entities.certificate` clarification.
- Entity-extraction hardening of corpus scenarios: the Kubernetes scenario uses `in namespace payments`
  so the scope extracts as `payments` rather than the stop-word `as`; the secret scenario uses
  `Rotate a secret.` so there is no spurious secret-name candidate (renamed to
  `secret-rotation-unnamed-secret`); the deployment and migration scenarios use `rollback` (one word)
  so the `ROLLBACK` capability is actually produced. The check now asserts these extracted entities, so a
  scenario that passes with a mis-parsed entity is caught.
- Removed the previously inaccurate `database-migration-with-backup -> PASS` self-claim and the
  `safety.backup` required-vs-recommended mismatch from the old self-referential check.
- `v0.4.8.target-semantics-matrix`: the conditions row is now derived from and cross-checked against
  `TargetExpressionSupport` (the single source of truth for guard-expression support); GitHub Actions
  conditions corrected from `native` to `partial`. Target ids are validated against the real target
  registry. The unsupported-target-condition reference case is verified here (a guard Tekton cannot
  express natively reports `condition.expression`; Jenkins expresses it).
- `v0.4.5.standard-surface-freeze`: now reconciles every declared schema against the filesystem, so a
  removed, renamed or never-created schema is detected rather than asserting a constant; retains the
  explicit assertion that `intent.schema.json` and `execution-plan.schema.json` are in the stable surface.
- `v0.4.9.standard-export-bundle`: now reconciles every required directory against the filesystem and
  requires the exported artifact set to equal the public surface stable artifacts (single source).
- No SDK, framework, plugin or runtime added: the checks only read existing standard components.

# 0.4.8

- Added Target Semantics Matrix as a public artifact for Jenkins, GitHub Actions and Tekton portability semantics.
- Added `target-semantics-matrix.json` and `schemas/target-semantics-matrix.schema.json`.
- Added `v0.4.8.target-semantics-matrix` conformance coverage and vector.

# 0.4.7

- Added Reference Intent Corpus for portable positive and negative intent scenarios.
- Added `reference-intent-corpus.json` and `schemas/reference-intent-corpus.schema.json`.
- Added `v0.4.7.reference-intent-corpus` conformance coverage and vector.

# 0.4.6

- Added Compatibility and Migration Policy as a public standard contract.
- Added `compatibility-migration-policy.json` and `schemas/compatibility-migration-policy.schema.json`.
- Added `v0.4.6.compatibility-migration-policy` conformance coverage and vector.

# 0.4.5

- Added Public Standard Surface freeze contract.
- Added `public-standard-surface.json` and `schemas/public-standard-surface.schema.json`.
- Added `v0.4.5.standard-surface-freeze` conformance coverage and vector.

# 0.4.4

- Wired `IntentProposalReview` into the CLI lowering path: `flow normalize --lower` (and `--pipeline`/`--render`) now runs the proposal-review gate on the normalized intent and refuses to lower a `Rejected` proposal, so the trust boundary is enforced on the real AI-to-plan path rather than only in tests. The deterministic default normalizer is unaffected (it already satisfies the gate); the change matters for a future model-backed provider.
- Added four conformance checks for the v0.4.4 invariants (previously only covered by JUnit) and promoted them to full public conformance vectors, honoring the rule that every extension carries a vector rather than only a runner check. Each of `v0.4.4.ai-proposal-review` (the standard rejects an irreversible migration that omits mandated backup, regardless of the provider's report), `v0.4.4.condition-expression-readiness` (an untranslatable Tekton guard blocks readiness with a `condition.expression` blocker and the generation gate throws), `v0.4.4.no-silent-condition-fallback` (the target expression-support model never disagrees with the translator, and Tekton never silently turns an unsupported guard into a passing one) and `v0.4.4.behavioral-generator-equivalence` (the golden plan preserves every task and the conditional guard across Jenkins/GitHub Actions/Tekton) now executes in `ConformanceRunner`, is required by the public release profile (`StandardReleaseProfile.requiredConformanceChecks`) and the conformance-manifest and release-profile vectors, and has a standalone `conformance/**/*.conformance.yaml` spec file discovered by the manifest builder.
- Bumped the standard version 0.4.3 -> 0.4.4: the post-governance work below is materially larger than a governance point release. Provenance check identifiers such as `v0.4.3.architecture-governance-guardrails` are intentionally left unchanged (they stamp the version that introduced the check); only `FLOW_STANDARD_VERSION`, the build version, the rendered snapshots and the documentation version stamps moved.
- Fixed a documentation drift in `README.md`: the WIP line no longer says "formal runtime execution" (which pulls toward a runtime-executor direction the governance forbids); it now reads "external execution conformance harnesses".
- Introduced `TargetExpressionSupport` in `capabilities` as the single source of truth for which condition expressions each target can express natively; both the compatibility analyzer and the target expression translator consume it (no duplicated knowledge, no dependency cycle, no plugin/SDK surface).
- A Flow guard that a target cannot express natively is now an execution-readiness BLOCKER (`condition.expression`), so generation is refused rather than the guard being silently dropped. Added `FlowExpressionSupportModelTests` including a no-drift check that the model's verdict always matches the translator's behavior.
- Added cross-target behavioral-equivalence coverage (`FlowGeneratorEquivalenceTests`): the same plan preserves every task, preserves dependency edges, and handles guards consistently across Jenkins/GitHub Actions/Tekton (semantic properties, not golden-file text equality).
- Introduced a typed `PolicyCondition` view (`Requirement`/`RetentionRule`/`Custom`) so the safety validator branches on a closed, typed model instead of inline magic strings; the serialized condition stays a string (additive, no serialization change). Added `FlowPolicyConditionTests`.
- Added `FlowBehavioralConformanceTests`: an independent reference interpreter derives which tasks execute under a given guard assignment from both the canonical plan and each generated manifest, then asserts they agree across Jenkins/GitHub Actions/Tekton. This is behavioral equivalence (reference semantics), not golden-file text equality, and includes a negative "teeth" case proving a silently dropped guard is detected.
- Added `docs/EXECUTION_CONFORMANCE.md` with the recipe for executing the generated manifests on real Jenkins/GitHub Actions/Tekton for the golden scenarios - the real-platform last mile beyond the in-repo reference-semantics conformance.
- Locked the generation-boundary enforcement for untranslatable guards: both CLI generation paths already call `compatibility.assertAllowed()` before generating, and an untranslatable guard is a compatibility ERROR, so generation is refused rather than merely reported. Added `untranslatableConditionThrowsAtTheGenerationGate` to prove the gate throws, complementing the existing readiness-report assertion.
- Added `IntentProposalReview` (Track 2, first increment): a trust boundary for any `AiIntentProvider` that re-derives validation and safety from the proposed `IntentDocument` using the standard's own validators, independently of the provider's self-reported risks/clarifications. This makes a model-backed provider safe to swap in behind the existing seam, with `ScenarioPackIntentNormalizer` remaining the deterministic default/fallback. It supersedes `AiIntentResponse.assertUsableForLowering()` (which trusts the provider's report) for trust decisions; the existing method is unchanged. Added `FlowIntentProposalReviewTests` (incl. a teeth case proving a pristine report cannot bypass a safety policy) and `docs/AI_NORMALIZATION_CONTRACT.md`.
- Extended `IntentProposalReview` with capability-mandated safety (Track 2, second increment): before validating, it injects any missing safety policy that an irreversible capability mandates (`DATABASE_MIGRATE` -> backup, `DEPROVISION` -> approval) and lets `SafetyPolicyValidator` decide satisfaction, so a proposal cannot escape the obligation by omitting the policy. Satisfaction logic is reused, not reimplemented. The mandate is conservative (`DEPLOY` is excluded) and enforced only in the AI-proposal gate, leaving hand-authored intents and the 784-scenario suite unaffected. Added two tests (omitted backup rejected; backup present accepted).

# 0.4.3


- Added `docs/ARCHITECTURE_CONSTITUTION.md` as the project-level architecture boundary.
- Added `docs/adr/ADR_TEMPLATE.md` for larger changes.
- Added `standard/architecture/forbidden-directions.yaml` to reject SDK, runtime, plugin and silent semantic fallback drift.
- Added `standard/architecture/feature-classification.yaml`, `release-checklist.yaml` and `drift-score.yaml`.
- Added `ArchitectureGovernanceAnalyzer` and `v0.4.3.architecture-governance-guardrails` conformance coverage.
- Added architecture diagnostic codes and tests for governance files and forbidden directions.
- Hardened `ArchitectureGovernanceAnalyzer` so forbidden source terms are loaded from `standard/architecture/forbidden-directions.yaml` instead of being duplicated in Kotlin.
- Added regression coverage proving YAML-only forbidden terms are detected in active source.
- Fixed scenario-pack entity over-extraction where imperative command verbs (e.g. `run`) were captured as entity values and suppressed required clarifications; `Run database migration.` now correctly asks for the target database.
- Added `FlowNormalizationRobustnessTests` with adversarial/property coverage enforcing the "AI proposes, the standard decides" invariant: filler words (time adverbs, pronouns, command verbs) are never captured as entities, and a missing critical entity always raises a REQUIRED clarification.
- Closed a silent-semantic-fallback gap: a Flow condition that cannot be expressed as a native Tekton `when` now emits an explicit `condition.unsupported` error mapping note instead of silently dropping the guard and letting the task run unconditionally.
- Added `FlowExpressionPortabilityRobustnessTests` asserting that unsupported conditions fail loud (GitHub Actions) or surface an explicit diagnostic (Tekton), and are never resolved to a hidden true/false value.
- Kept the release scoped to governance guardrails, normalization robustness and honest target-portability diagnostics; no new syntax, runtime executor, SDK, plugin framework or renderer expansion was added.

# 0.4.2

- Replaced SDK-like `target-adapter-certification-profile.json` with `target-conformance-profile.json`.
- Replaced `TargetAdapterCertificationProfileReport` with `TargetConformanceProfileReport`.
- Removed the active `org.flowlang.runtime.LocalRuntime` path and replaced it with non-executing `org.flowlang.preview.PlanPreview`.
- Added `v0.4.2.target-conformance-profile` and `v0.4.2.standard-boundary-no-sdk-runtime` conformance checks.
- Added an architecture conformance vector preventing active SDK/runtime drift.
- Updated docs and public artifact lists to clarify that Flow defines contracts and conformance artifacts, not an SDK, plugin framework or runtime executor.

# 0.4.1

- Hardened scenario-pack entity extraction so temporal words, pronouns and quantifiers such as `now`, `tonight`, `daily`, `everything`, `here` and `there` do not suppress required clarifications.
- Added token-order trigger matching so requests such as `Migrate the database now` select `database-migration` instead of falling back to `custom`.
- Changed target condition translation to fail explicitly for unsupported semantics instead of emitting silent `true`/`false` fallbacks.
- Replaced broad `Throwable` catches in condition translation with narrower `Exception` handling.
- Added `v0.4.1.semantic-correctness-hardening` conformance coverage and unit tests for adversarial normalization.
- Moved clean conformance report data classes out of the runner implementation.
- Marked `LocalRuntime` output as planning preview with `executesCommands=false`.
- Clarified release-profile check ID provenance semantics.
- Kept the change scoped to semantic correctness hardening; no new syntax, runtime executor, SDK/plugin system or target renderer expansion was added.

# 0.4.0

- Added the first public Flow standard draft.
- Added `standard-freeze-report.json`, `compatibility-policy.json`, `reference-corpus-index.json`, `negative-conformance-corpus.json`, `target-adapter-certification-profile.json`, `standard-index.json`, `conformance-suite.json` and `flow-standard-draft.json`.
- Added JSON schemas, conformance vectors and tests for the public draft surface.
- Added `standard-draft` CLI export.
- Kept the change scoped to standardization: no new syntax, runtime executor, SDK/plugin system or target-specific renderer expansion.

# 0.3.19

- Consolidated the remaining v0.3.16-v0.3.19 roadmap steps into one release.
- Added Standard Contract Index via `StandardContractIndexAnalyzer`.
- Added Standard Release Profile via `StandardReleaseProfile`.
- Added Artifact Evidence Report via `ArtifactEvidenceAnalyzer`.
- Added Standard Compliance Report via `StandardComplianceAnalyzer`.
- Added public artifacts `standard-contract-index.json`, `standard-release-profile.json`, `artifact-evidence-report.json` and `standard-compliance-report.json`.
- Added matching JSON schemas, conformance vectors and tests.
- Kept the change scoped to public standard contract closure; no SDK runtime, plugin system, new syntax or renderer expansion was added.

# 0.3.15

- Added Artifact Integrity Report via `ArtifactIntegrityAnalyzer`.
- Added public `artifact-integrity-report.json` export for lowering pipelines.
- Added `schemas/artifact-integrity-report.schema.json`.
- Added required-artifact presence, standard-version observation and diagnostic coverage status checks.
- Added artifact integrity diagnostic codes to the Standard Diagnostic Code Catalog.
- Added conformance vector and tests for artifact integrity behavior.
- Kept the change scoped to public standard artifact consistency; no SDK runtime, plugin system, new syntax or renderer expansion was added.

# 0.3.14

- Added Diagnostic Coverage Report via `DiagnosticCoverageAnalyzer`.
- Added public `diagnostic-coverage-report.json` export for lowering pipelines.
- Added `schemas/diagnostic-coverage-report.schema.json`.
- Added observed, unknown and unused diagnostic code reporting.
- Added `DIAGNOSTIC_CODE_UNKNOWN` to the Standard Diagnostic Code Catalog.
- Added conformance vector and tests for diagnostic coverage behavior.
- Kept the change scoped to public standard diagnostics; no SDK runtime, plugin system, new syntax or renderer expansion was added.

# 0.3.13

- Added Standard Diagnostic Code Catalog via `StandardDiagnosticCatalog`.
- Added public `standard-diagnostic-catalog.json` output/export.
- Added `schemas/standard-diagnostic-catalog.schema.json`.
- Added stable diagnostic code metadata: code, area, severity, stability, usedBy and description.
- Added `diagnostics` CLI command with JSON, Markdown and `--out <dir>` modes.
- Added conformance vector and tests for diagnostic catalog behavior.
- Kept the change scoped to public standard diagnostics; no SDK runtime, plugin system, new syntax or renderer expansion was added.

# 0.3.12

- Added Target Adapter Contract via `TargetAdapterContractAnalyzer`.
- Added public `target-adapter-contract.json` and `adapter-diagnostics.json` exports.
- Added `schemas/target-adapter-contract.schema.json` and `schemas/adapter-diagnostics.schema.json`.
- Added allowed input artifacts, expected output artifacts, forbidden intent inputs and adapter invariants.
- Added adapter diagnostics for ready, degraded and blocked target generation.
- Added conformance vector and tests for target adapter contract behavior.
- Kept the change scoped to standard adapter boundaries; no SDK runtime, plugin system, new syntax or renderer expansion was added.

# 0.3.11

- Added Conformance Manifest via `ConformanceManifestBuilder`.
- Added public `conformance-manifest.json` output/export.
- Added `schemas/conformance-manifest.schema.json`.
- Added conformance area summaries, required checks, failed checks, vector inventory, public schema inventory and required artifact inventory.
- Added `conformance --out <dir>` support for writing the manifest.
- Added conformance vector and tests for conformance manifest behavior.
- Kept the change scoped to standard conformance reporting; no new syntax, SDK runtime or renderer expansion was added.

# 0.3.10

- Added Public Artifact Bundle Contract via `FlowArtifactBundleAnalyzer`.
- Added public `flow-artifact-bundle.json` export.
- Added `schemas/flow-artifact-bundle.schema.json`.
- Added artifact metadata for role, schema, required/optional status, derivation and pipeline order.
- Added conformance vector and tests for artifact bundle behavior.
- Kept the change scoped to public artifact packaging; no new syntax, SDK runtime or renderer expansion was added.

# 0.3.9

- Added Target Decision Trace Report via `TargetDecisionTraceAnalyzer`.
- Added public `target-decision-trace-report.json` export.
- Added `schemas/target-decision-trace-report.schema.json`.
- Added trace steps for ExecutionPlan, capability negotiation, execution readiness and target selection.
- Added per-target explanations for recommended, degraded and blocked candidates.
- Added conformance vector and tests for target decision trace behavior.
- Kept the change scoped to audit/reporting; no new syntax, SDK runtime or renderer expansion was added.

# 0.3.8

- Added Target Selection Report via `TargetSelectionAnalyzer`.
- Added public `target-selection-report.json` export.
- Added `schemas/target-selection-report.schema.json`.
- Added ranked target candidates based on execution readiness and portability score.
- Added ready/degraded/blocked target groups to target selection output.
- Added conformance vector and tests for reference target selection.
- Kept the change scoped to validation/reporting; no new syntax, SDK runtime or renderer expansion was added.

# 0.3.7

- Added Execution Readiness Report via `ExecutionReadinessAnalyzer`.
- Added public `execution-readiness-report.json` export.
- Added `schemas/execution-readiness-report.schema.json`.
- Added target-generation decisions `READY`, `DEGRADED` and `BLOCKED`.
- Added readiness fields `generationAllowed`, `productionReady`, `blockers`, `warnings` and `requiredActions`.
- Added conformance vector and tests for Jenkins ready, GitHub Actions degraded and Tekton blocked behavior.
- Kept the change scoped to validation/reporting; no new syntax, SDK runtime or renderer expansion was added.

# 0.3.6

- Added ExecutionPlan portability scoring to target capability negotiation.
- Added report fields `portabilityScore`, `portableCapabilities`, `targetSpecificCapabilities`, `blockingPortabilityIssues` and `requiredWorkarounds`.
- Added per-target portability scores to negotiation entries.
- Bumped `capability-negotiation-report.schema.json` to report v1.1.
- Added conformance vector and JUnit coverage for portability behavior.
- Kept the change scoped to standard reporting; no new syntax, SDK runtime or target renderer expansion was added.

# 0.3.5

- Added Intent Decision Model via `IntentDecisionAnalyzer`.
- Added public `intent-decision-report.json` export.
- Added `schemas/intent-decision-report.schema.json`.
- Added blocking missing-decision checks for cleanup retention, database migration backup and production deploy approval.
- Added recommended missing-decision check for backup schedule timezone.
- Added conformance vector and tests for decision model behavior.

# 0.3.4

- Corrected the v0.3.3 Module SDK direction back to Capability Module Contract.
- Removed module-owned `runtime`, `entrypoint`, `template` and `generators` from the public module contract.
- Added `requiredCapabilities` and `targetImplications` as capability-level metadata.
- Replaced `ModuleSdkReport` with `CapabilityModuleContractReport`.
- Replaced `module-sdk-report.schema.json` with `capability-module-contract-report.schema.json`.
- Added conformance guardrail `architecture.modules-do-not-own-target-rendering`.
- Kept modules as capability/effects/safety dictionaries, not plugin/runtime frameworks.

# 0.3.3

- Added Module SDK report and `ModuleContractAnalyzer`.
- Extended module descriptor contract to v1.1 with adapter-facing `runtime`, `secrets`, `targetMappings` and `generators` metadata.
- Added CLI command `modules`.
- Added `schemas/module-sdk-report.schema.json`.
- Added conformance vector and tests for module SDK contracts.
- Kept v0.3.2 safety and canonical execution plan behavior intact.

# 0.3.2

- Added first-class `SafetyPolicyValidator` before AST lowering.
- Cleanup scenario now blocks lowering without explicit retention or safety rule.
- Kubernetes maintenance safety policy now enforces explicit dry-run when required.
- Added canonical lowercase Execution Plan export for adapter/runtime consumers.
- Added `canonical-execution-plan.json` to CLI export bundles.
- Added schema coverage for `capability-negotiation-report.json`.
- Added adapter-facing YAML loader namespace under `org.flowlang.adapters.yaml` while keeping legacy loaders compatible.
- Added v0.3.2 conformance vectors and tests for safety policy validation and canonical execution plans.

# 0.3.1

- Expanded ExecutionPlan as a public intermediate contract.
- Extended `execution-plan.schema.json` for node types, dependencies, effects, safety, required capabilities, target hints and assumptions.
- Added target capability negotiation report.
- Added database migration, certificate renewal and Kubernetes maintenance scenario packs.
- Added conformance vectors for the new scenario packs and capability negotiation.
- Added v0.3.1 documentation notes and continued the core/adapter split direction.

## 0.3.0-rc1.8.3

Corrective release after independent scenario-pack audit. See `docs/RC1_8_1_AUDIT_AND_FIXES.md`.

- Fixed cleanup normalization so the semantic cleanup resource is not interpreted as a system name.
- Changed cleanup capability contract from `target` to `resource`; `system` is the only system selector.
- Fixed backup subject extraction for phrases such as `orders database`.
- Custom/unknown requests now produce a human-review intent that can lower safely instead of crashing.
- Removed non-English natural-language aliases and non-English project source/test/example text.
- Added regression coverage for cleanup lowering, simple backup, custom review and rollback-only review.

## 0.3.0-rc1.8.3 - Conformance stabilization hotfix

- Keeps functional conformance checks blocking.
- Keeps snapshot, schema and rendered-version drift checks blocking through `ConformanceRunner.summary.ok`.
- Adds explicit failure details to the JUnit bridge for core specification scenarios.
- Bumps Flow standard version to 0.3.0-rc1.8.3.


## 0.3.0-rc1.8.3 - Test compile hotfix

- Fixed Kotlin string interpolation in FlowBetaConformanceTests for the literal GitHub Actions expression `${{ always() }}`.
- Replaced a redundant `mappingNotes != null` conformance assertion with an actual non-empty mapping notes check.
- No functional runtime behavior was changed.

# Changelog

## 0.3.0-rc1.8.3

- Relaxed legacy GitHub Actions DAG conformance to assert semantic DAG rendering instead of fixed generated task IDs.


## 0.3.0-rc1.8.3 - Secret rotation and conformance hotfix

- Fixed secret rotation entity extraction so vague requests produce required clarification instead of treating conjunctions as secret names.
- Changed default conformance snapshot validation to semantic invariants while preserving exact golden comparison behind `FLOW_SNAPSHOT_STRICT=true`.
- Updated snapshot standard version markers to the current Flow standard version.


## 0.3.0-rc1.8.3

- Fixed `ConformanceRunner` compilation by typing `PipelineArtifacts.plan` as `ExecutionPlan` instead of `Any`.
- Added explicit `String` types around serialized CLI JSON outputs to avoid Kotlin overload ambiguity in dependency-limited/offline compilation checks.

## 0.3.0-rc1.8.3

### Added
- Scenario Pack model and registry.
- Standard packs for deployment, backup/restore, data-sync, secret-rotation and incident-runbook.
- `scenarios` and `scenario <id> --examples` CLI commands.
- Scenario pack conformance checks covering catalog presence and full-pipeline normalization.
- Documentation: `docs/SCENARIO_PACKS.md`.

### Changed
- `ScenarioPackIntentNormalizer` is the primary deterministic normalizer; `RuleBasedIntentNormalizer` remains only as a deprecated compatibility wrapper.
- AI normalization remains provider-neutral and still outputs only Standard Intent Model documents.

### Principle
- Scenario Packs expand Flow horizontally across common DevOps automation families without adding target-specific syntax or forcing users to learn platform lifecycle details.

## 0.3.0-rc1.8.3

### Added
- AI Intent Normalization contract (`AiIntentProvider`, `AiIntentRequest`, `AiIntentResponse`).
- Deterministic `ScenarioPackIntentNormalizer` for offline MVP validation and conformance.
- Normalization report with classification, confidence, assumptions, open questions, risks, explanation and guardrails.
- CLI command `normalize` with optional lowering, rendering and export path.
- Schema draft: `schemas/ai-normalization-report.schema.json`.

### Fixed / Hardened
- Removed non-English keyword triggers from the scenario-pack normalizer so public commands and matching vocabulary stay English-only.
- Fixed normalized Kubernetes deployment/verification so generated intent lowers to module-contract-valid Flow AST.
- Added full AI-normalization pipeline validation: normalized intent -> capability validation -> AST lowering -> FlowValidator -> ExecutionPlan.
- Added schema smoke conformance for emitted public artifacts (`intent`, `AST`, `execution plan`, `target manifest`).

### Principle
- AI remains an intent translator, not a direct Jenkins/GitHub/Tekton generator.
- Every normalized result must pass the same Flow validation and target compatibility pipeline.

## 0.2.0-rc5

### Added / Improved
- Stricter golden snapshot comparison for JSON and rendered target outputs.
- Target mapping notes in TargetManifest and rendered outputs.
- StandardCapabilityContract executable model.
- Improved Jenkins, GitHub Actions and Tekton renderers while keeping Flow platform-neutral.
- Removed hardcoded BuiltInTargets as production source of truth; `targets/builtin-targets.yaml` is the registry.
- Added core/adapter split documentation and conformance check preventing Jackson/YAML imports in core model packages.
- Added regression tests for target expression translation and standard capability contracts.

## 0.2.0-rc4

### Fixed / Corrected
- Fixed semantic dead conditions in generated Jenkins and GitHub Actions output.
- Fixed false sequential dependencies in `FlowPlanner`.
- Restored GitHub Actions job-per-task manifest generation with `needs:`.
- Improved target renderers to use the canonical TargetManifest path from CLI.
- Improved renderer robustness by avoiding unsafe triple-quoted shell strings.
- Added runtime inputs to ExecutionPlan/TargetManifest.
- Fixed duplicate `features:` block in target registry YAML.
- Conformance runner compares generated snapshots for manifest/rendered artifacts.

## 0.2.0-rc3

### Fixed / Corrected
- Re-aligned the codebase with Flow's AI-first automation standardization purpose.
- Replaced string-only intent params/config with structured `IntentValue` model.
- Preserved YAML maps/lists as structured values.
- Added visible convention assumptions to the Intent Design Report.
- Updated manifest generators to consume `ExecutionPlan.nodes`.
- Added fine-grained target feature capability support.
- Added Standard Capability Contract fields to the catalog model.
- Added safer Tekton scripts with shebang and `set -eu`.

## 0.2.0-rc2
- Added Standard Intent Catalog as executable Kotlin model.
- Added `catalog` CLI command with JSON and Markdown output.
- Added `targets` CLI command for target capability matrix inspection.
- Added Intent Design Report before capability validation and lowering.

## 0.2.0-rc1
- Added end-to-end artifact export via `--out`.
- Added TargetManifest JSON export.
- Added Tekton target manifest draft.

## 0.1.x
- Built the first parser, AST, validator, planner, module registry, intent loader and basic target generation experiments.

## 0.3.0-rc1.8.3

Corrective release after rc1.6 audit.

- Restored full FlowSpecJUnitTest as blocking coverage.
- Fixed `${version}` template lowering and target-specific interpolation.
- Aligned version strings to `0.3.0-rc1.8.3`.
- Added build-test, provisioning and cleanup scenario pack coverage.
- Removed fabricated cron/source/destination values from normalization output.
- Required clarification questions and unmitigated high risks now block lowering by default.
- Replaced constant confidence values with dynamic confidence scoring.
- Narrowed deployment triggers to avoid over-synthesizing deployment from rollback/health/kubernetes alone.
- Added ScenarioPackIntentNormalizer and deprecated the old delegate-only RuleBasedIntentNormalizer.
- Added operation-specific semantic rendering for standard.execute.
- Enforced English-only project source text: no non-English triggers, examples, CLI strings or generated artifact content.


## 0.3.0-rc1.8.3

- Fixed public JSON serialization to omit null optional fields so schema conformance does not fail on optional step descriptions.
- Kept schema validation blocking; this is a real fix, not an advisory bypass.

## 0.7.3 build-fix

- Fixed the data-driven conformance index self-check runner list so `v0.5.4.data-driven-conformance-index` is included when the index validates runner coverage.
- Fixed the architecture drift negative fixture test so it copies all evidence anchors required by `StandardModel.wellFormednessIssues(rootDir)` and isolates the expected runtime-direction negative signal.
- Updated the offline build configuration to use JDK 21 in the Kotlin toolchain and added `gradle.properties` for offline, single-worker, high-memory sandbox builds.
- Verified `FlowRcConformanceTests`, `FlowArchitectureDebtCleanupTests`, full `test --offline`, CLI `run --args="conformance" --offline`, and clean `clean test --offline`.
