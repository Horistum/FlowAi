# 0.7.7-scenario-pack-quality-gates

- Bumped the active standard version to `0.7.7`.
- Added `ScenarioPackQualityAnalyzer` for scenario pack metadata, example relevance, duplicate id and negative coverage checks.
- Added `FlowScenarioPackQualityGateTests` covering success and failure paths.
- Added `conformance/standard/scenario-pack-quality-gates.conformance.yaml`.
- Added CI artifact upload for Gradle test reports and conformance logs.

# 0.7.6-semantic-correctness-hardening-fix2

- Fixed full offline Gradle test failure caused by stale exact rendered snapshots for GitHub Actions and Tekton.
- Regenerated `conformance/snapshots/build-test-deploy/github-actions.yml` and `conformance/snapshots/build-test-deploy/tekton-pipeline.yaml` from the reviewed v0.7.6 rendering path.
- Preserved exact snapshot conformance instead of weakening or bypassing the gate.
- Verified `./gradlew --offline --no-daemon clean test --stacktrace --console=plain`: 151 tests, 0 failures, 0 errors, 0 skipped.
- Verified `./gradlew --offline --no-daemon run --args="conformance" --console=plain`: 76 passed, 0 failed.
- Kept the active standard version at `0.7.6`; this is a corrective package fix, not a new standard feature layer.

# 0.7.6-semantic-correctness-hardening-fix1

- Fixed Kotlin compilation in `TargetExpressionTranslator` by avoiding an invalid smart-cast on `BinaryExpressionNode.right`.
- Corrected mandatory safety enforcement so high-risk capability obligations are reported by `IntentCapabilityValidator` instead of being silently synthesized before validation.
- Verified the main source surface through dependency-ordered `kotlinc` compilation in the offline sandbox.
- Added semantic smoke validation for Kubernetes deploy identity, Jenkins runtime input rendering, mandatory database migration safety, safe-navigation preservation and Jenkins named-pattern rendering.
- Kept the active standard version at `0.7.6`; this is a corrective fix for the semantic-correctness-hardening release, not a new standard feature layer.

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

# 0.7.3-standard-model-projection-coherence

- Bumped the active standard version to `0.7.3`.
- Collapsed the legacy registry-consistency gate set into one `v0.7.3.standard-model-projection-coherence` gate.
- Removed redundant public conformance vectors for self-registration/parity/closure/freeze gates that only compared projections of the same `StandardModel`.
- Kept `StandardModel` as the single source of truth for public surface, export bundle, export manifest, conformance levels and release profile.
- Enforced falsifiability/evidence anchors in `StandardModel.wellFormednessIssues(rootDir)`.
- Renamed architecture scoring mode from `negative-delta-only` to `negative-signal-only` until a true two-version delta analyzer exists.

# 0.7.1-standard-model-governance-integration

- Added `StandardModel` as the single source of truth for public standard artifacts and release checks.
- Reworked `StandardReleaseProfile`, `StandardSurface`, conformance levels and export manifest release-gate projections to derive from `StandardModel`.
- Converted `StandardArtifactRegistry` into a compatibility facade over `StandardModel` instead of a second canonical list.
- Replaced release-gate category string heuristics with explicit `GateKind` data.
- Strengthened architecture report-budget validation with `StandardModel.wellFormednessIssues()`, validation roles for stable artifacts and one-third registry-consistency budget.
- Added regression coverage proving release profile, candidate level, export manifest and public surface are derived from the model.
