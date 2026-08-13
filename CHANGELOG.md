# Changelog

All project source text is written in English. The changelog records architectural and behavioral changes while preserving the project boundary: Flow AI is an AI-first standardization layer for IT and DevOps automation intent, not a runtime executor, SDK platform, plugin lifecycle framework or target-specific public DSL.

## Unreleased - v0.9.7 correction track

### SI-05 Operational Effect Model Re-evaluation

#### Corrected

- Replaced generic data-transformation BACKUP/RESTORE effects with target-neutral `STATE_RECOVERY` semantics that preserve the authored protected-state relationship.
- Added a typed recovery facet for recovery-point capture and state restore, including authored backup destination, recovery-point identity and capture retention without encoding those relationships in free-form resource strings.
- Kept restore state transition as `UPSERT UNKNOWN -> PRESENT` because the current authored contract does not distinguish state creation from replacement; consistency boundaries and recoverability are likewise not invented without authored evidence.
- Extended canonical observation identity and materialization re-validation so recovery-relevant authored differences remain distinguishable and forged recovery relationships fail closed.
- Added DP01/DP02, negative-pair, implementation-label invariance, strict schema, JSON round-trip and materialization-forgery regression coverage.

#### Contract migration

- Advanced AST from `2.1` to `2.2` and ExecutionPlan from `2.2` to `2.3` because `SemanticEffect` is serialized through both public artifact boundaries.
- Kept Intent `2.0`, ExecutionPlan lowering evidence `2.1`, TargetManifest `3.0`, TargetRegistry `3.1`, implementation package `0.9.5` and public standard `0.8.0` unchanged.
- Regenerated committed AST and ExecutionPlan reference snapshots for the new artifact versions; the existing non-recovery reference scenarios retain all other semantic content.
- Documented the representability and ownership decisions in `docs/SI_05_OPERATIONAL_EFFECT_MODEL_RE_EVALUATION_MIGRATION.md`.

#### Validation

- SI-05 remains active until its implementation receives independent exact-head and synthetic merge-candidate Flow CI evidence and is merged; this implementation PR does not self-complete its lifecycle.

### SI-04 Explicit Canonical Execution-Plan Semantics

#### Corrected

- Replaced `ExecutionPlanCanonicalizer` module/action/resource-string task classification with `CanonicalExecutionPlanSemanticsAuthority`, which derives public task-node kind only from canonical `StandardCapability`.
- Added the closed `CanonicalPlanNodeKind` wire vocabulary and fail-fast mappings for legacy data/control planner kinds instead of publishing arbitrary lowercased internal strings.
- Added invariance regressions proving module name, action text, target identity, adapter-required capabilities and effect-resource vocabulary cannot promote or change an unrelated canonical task kind.
- Added conformance verification that independently re-derives each task kind from canonical capability and rejects a mismatched public plan.
- Corrected public-schema validation so the ExecutionPlan schema is applied to the canonical public plan rather than the internal planner representation.
- Regenerated committed reference snapshots through the production generator; canonical `NOTIFY` now publishes `notification` independently of its implementation binding label.

#### Contract migration

- Advanced ExecutionPlan from `2.1` to `2.2` because SI-04 changes the public interpretation of `CanonicalPlanNode.kind` even though the JSON object shape is unchanged.
- Kept Intent `2.0`, AST `2.1`, ExecutionPlan lowering evidence `2.1`, package `0.9.5` and public standard `0.8.0` unchanged because their contracts are not modified by SI-04.
- Tightened the ExecutionPlan 2.2 schema `kind` enum to exactly the closed lowercase `CanonicalPlanNodeKind` vocabulary and documented consumer migration in `docs/SI_04_EXPLICIT_CANONICAL_EXECUTION_PLAN_SEMANTICS_MIGRATION.md`.

#### Validation

- Local candidate validation covers Flow Agent tooling, schema/type parity, negative implementation-label invariance, authority-catalog integrity and the complete 1013-test suite.
- SI-04 remains active until the published implementation obtains its own external exact-head and synthetic merge-candidate Flow CI boundary; committed `lastKnownValidation` continues to describe completed SI-03.

### SI-03 Canonical Technology Neutrality

#### Corrected

- Removed Docker as a required system and lowering authority from canonical `BUILD_IMAGE` and `PUSH_IMAGE` contracts while retaining the neutral capability identities and generic image semantics.
- Reclassified `dockerfile` as explicit `docker.build` binding-only configuration; unbound Dockerfile input now fails closed instead of entering canonical meaning.
- Removed Docker from the public image-capability catalog hints without deleting the concrete Docker module, bindings or target projection evidence.
- Preserved the byte-exact C0.4 binding v1.0 snapshot and introduced live binding evidence v1.1 with a machine-enforced migration that permits only the reviewed Dockerfile reclassification.
- Generalized post-C1 roadmap transition handling so SI-03 and later semantic-integrity items use an explicit data-driven work-package boundary instead of adding one hard-coded lifecycle enum branch per work item.

#### Compatibility

- Intent, AST and ExecutionPlan serialized versions remain unchanged because SI-03 does not rename capability identities or change artifact shape; it enforces the existing canonical-versus-binding boundary.
- Docker-specific implementation behavior remains available only below the canonical boundary through explicit binding and adapter evidence.

### SI-02 Authored Dependency Graph Preservation

#### Corrected

- Removed intent-to-AST lowering's synthetic previous-step dependency, so lexical or topological traversal order no longer becomes authored semantic ordering.
- Preserved `DECLARED_ORDERING` and `DATA_REFERENCE` as independent ExecutionPlan provenance when both justify the same node pair.
- Added fail-closed artifact-derived lowering verification that requires exact set equality between authored `requires` edges and `DECLARED_ORDERING` relations: missing, extra and duplicate authored edges are rejected.
- Reclassified the C02 ordering diamond and N08 baseline as correctly representable once independent siblings are no longer silently serialized.
- Strengthened N08 into a permanent negative mutation that explicitly authors an extra B-to-C edge and still detects both parallelism serialization and the dropped fan-in edge.

#### Validation

- SI-02 was authorized only after SI-01.1 independently passed Flow CI #2892 and merged as `44ac499a466378604ec3823719d15953505fd4f8`.
- Local final-tree validation covers Flow Agent tooling, clean warning-free production compilation, the complete 999-test suite and standalone conformance. GitHub CI remains an external publication boundary and is not pre-claimed by committed metadata.

### SI-01.1 Post-C1 Integrity Reconciliation

#### Corrected

- Activated an explicit post-C1.0 semantic-integrity roadmap and bounded SI-01.1 work package instead of retroactively claiming SI-01 was authorized.
- Recorded SI-01 as technically validated by Flow CI #2888 but historically missing the roadmap activation promised by PR #121.
- Advanced `lastKnownValidation` to the actual SI-01 GitHub validation boundary while keeping the current SI-01.1 validation explicitly local.
- Replaced the stale aggregate artifact-contract version with independently checked live Intent, AST, ExecutionPlan, lowering-evidence, TargetManifest and TargetRegistry versions while preserving the historical closure boundary.
- Removed five confirmed production Kotlin compiler warnings by correcting dead nullability logic, bundle provenance selection, deprecated module loading, conformance target-selection provenance and redundant nullable access.
- Allowed completed C1.0 lifecycle evidence to remain historical while a separately authorized global successor stream owns current roadmap focus.

#### Deferred

- SI-03 remains responsible for removing Docker/Dockerfile-specific vocabulary from canonical BUILD_IMAGE meaning. This correction does not pretend a lexical governance rule is a semantic migration.


### v0.9.7.9.1 Evidence and Control Integrity Repair

#### Added

- Added task-scoped approval reachability evidence and fail-closed execution planning for unresolved dynamic controls.
- Added an independent source contradiction gate so explicit backup denial cannot become synthesized positive backup evidence.
- Added descriptor-driven propagation tests for module-required capabilities and Argo CD system configuration.
- Added focused negative coverage for free-form runtime command parameters before canonical lowering.

#### Changed

- Reopened v0.9.7.9 as correction-required after proving that its lowering report certified a discarded TEST command as preserved.
- Standard capability contracts now reject unrepresentable runtime command text instead of carrying it as an opaque escape hatch.
- FlowPlanner now consumes module-declared required capabilities and schema-declared system configuration instead of product-name branches.
- The build-test-deploy reference scenario now uses one consistent unconditional authored approval, and its complete snapshot bundle is regenerated from the canonical pipeline.

#### Removed

- Removed the dead module `targetImplications` model and report surface while retaining descriptor-boundary rejection.
- Removed the unused legacy portable-shell error-handler lowering overload.
- Removed silent control-obligation deduplication that could hide lossy ID collisions.

#### Validation boundary

- Flow CI run `1872` passed clean compilation, the complete test suite, standalone conformance and reference snapshot honesty on implementation head `1689d9400192dd0efbfba9b4d80ee6ab5ec06cdc`.
- The remaining audit findings continue as bounded work items v0.9.7.9.2 through v0.9.7.9.7; the v0.9.7 closure gate remains blocked.

## Unreleased - v0.9.6 architecture foundation

### Added

- Added a target-neutral execution-topology model covering isolation, lifetime, persistence and propagation.
- Added mandatory Target Registry `3.1` topology profiles with explicit support status and evidence for every built-in target.
- Added fail-closed materialization checks that reject missing, partial, unsupported or contradictory topology evidence before provider invocation.
- Added the first target-scoped executable multi-step reference scenario, proving ordered native checkout and image build through the real Jenkins pipeline.
- Added canonical `checkout-build-image` semantic snapshots and `jenkins.executable.yaml` evidence derived from the real parser-to-renderer path.
- Added `reference-snapshot --targets` support so committed evidence can declare an explicit target scope instead of implying universal readiness.
- Added reviewed native `docker.build` projection coverage for Jenkins, GitHub Actions and Tekton.
- Added target-owned image-build rendering through the Jenkins Docker Pipeline object API, `docker/build-push-action@v7` and the reviewed Tekton Catalog buildah Task 0.9 contract.
- Added behavioral and negative tests covering the complete registry-to-renderer image-build path, including path safety, interpolation, push policy and target-specific structural limits.
- Added reviewed native `git.checkout` projection coverage for Jenkins, GitHub Actions and Tekton.
- Added target-owned checkout rendering for GitHub repository normalization, GitHub ref/fetch-depth inputs, Jenkins shallow checkout and Tekton git-clone workspaces.
- Added behavioral and negative tests covering the complete registry-to-renderer checkout path across built-in targets.
- Added target-neutral opaque native projection implementation catalogs and typed binding slot contracts.
- Added provider-owned implementation evidence for the existing Jenkins Git checkout payload.
- Added negative architecture coverage preventing registry-only native claims, duplicate definitions, schema mismatch and cross-target catalog use.
- Added universal typed projection bindings and the explicit Core-to-edge target projection provider boundary in the preceding `0.9.6.2` and `0.9.6.3` work items.

### Changed

- Action capability, task ordering and continuity evidence no longer prove executable readiness without a matching execution-topology profile.
- Target Registry advances from `3.0` to `3.1`; Target Manifest remains `3.0`.
- Partial execution topology is classified as unsupported rather than degraded executable readiness.
- Completed the v0.9.6 Universal Native Projection Foundation track and advanced v0.9.7 Policy-Driven Safety and Environment Classification to the next roadmap item.
- Kept `build-test-deploy` MIXED and non-executable while documenting why isolated GitHub Actions and Tekton native actions are not yet an end-to-end workspace-continuity proof.
- Built-in target registries now declare `docker.build` as supported only where registry evidence, provider contracts and structured renderer behavior all exist.
- Image-build context and Dockerfile values are validated as compile-time relative workspace paths; unsafe or dynamic paths, unknown interpolation and invalid push policy fail closed.
- Jenkins custom Dockerfile selection remains unsupported because its native plugin exposes that capability through a free-form Docker CLI argument string.
- Built-in target registries now declare `git.checkout` as supported only where registry evidence, provider contracts and renderer behavior all exist.
- Checkout depth is preserved as a typed task parameter with `0` representing full history; invalid depth values fail closed at the concrete renderer edge.
- Targets without checkout projection evidence remain adapter-required instead of inheriting built-in coverage.
- Native renderer payload compilation now requires agreement between target registry evidence, the selected provider catalog and generated typed bindings.
- Typed binding resolution is owned by the native projection compilation boundary instead of the general materialization resolver.
- Reconciled generators and projection providers validate native implementation ownership before manifests leave the generation boundary.
- Concrete generators, renderers, payload identifiers, binding syntax and expression translation remain in edge target packages.

### Architecture boundary

- `0.9.6.1` through `0.9.6.7` are bounded work identifiers toward the next package line; the published package remains `0.9.5`.
- Public Flow standard remains `0.8.0`; Intent, AST and ExecutionPlan remain `2.0`; Target Registry is `3.1` and Target Manifest remains `3.0`.
- No runtime executor, SDK lifecycle, plugin discovery, shell projection, credential inference or universal cross-target executable claim is introduced.

## 0.9.5 - Universal model completion

### Added

- Added first-class manual, schedule, event and webhook triggers across Intent, AST, ExecutionPlan and TargetManifest.
- Added declarative target projection rules and structured native renderer payload evidence.
- Added an extensible registry-keyed target semantics matrix.
- Added migration documentation for all public 2.0 artifact contracts.

### Changed

- Made compatibility/readiness reconciliation mandatory at the final manifest generation boundary used by the CLI and all generators.
- Removed dormant no-op and phantom-task renderer branches.
- Made generic deploy and verify lowering target-neutral instead of inventing Kubernetes defaults.
- Promoted the implementation package to `0.9.5` and the public standard to `0.8.0`.
- Advanced Intent, AST, ExecutionPlan, TargetManifest and TargetRegistry to `2.0`.

### Removed

- Removed schedule-as-workflow-step semantics.
- Removed the legacy TargetManifest `run` field.
- Removed vendor-specific fields from `TargetSemanticsEntry`.

### Architecture boundary

- No runtime executor, SDK lifecycle, plugin framework, shell projection or target-specific public Flow DSL is introduced.
- Missing projection evidence remains review-only or fail-fast.

## Historical - v0.9.5.x architecture correction track

### Added

- Added the notes-driven architecture recenter and explicit shell/command projection prohibition.
- Added declarative notes package contracts for domain, capability, safety, runtime, target, projection and conformance ownership.
- Added a universal semantic action graph and explicit materialization negotiation with auditable evidence.
- Added no-shell target projection records and target registry lifecycle honesty.
- Added structural governance checks, unified renderer failure semantics and compatibility/readiness reconciliation.
- Added release metadata boundaries that distinguish the published package, unreleased correction scope, public standard and artifact contract versions.
- Added evidence-driven target expression profiles with registry or notes provenance and fail-closed support decisions.
- Added an explicit snapshot evidence index with semantic-only, review-only, fail-fast and executable states.

### Changed

- Connected real execution-plan tasks to semantic graph, materialization and projection evidence.
- Removed legacy Jenkins and GitHub Actions shell generator fixtures and their shell-output assertions.
- Reclassified unresolved target work as review-only or fail-fast instead of executable success.
- Required target recommendations to use concrete materialization and renderer evidence rather than capability declarations alone.
- Replaced the production CI/CD bias analyzer's hardcoded future roadmap version list with evidence-driven architectural follow-up areas.
- Reconciled `REPORT.md`, `.flow-agent/release-state.yaml`, both roadmap files, versioning policy and correction reports around one explicit version boundary.
- Removed target-name expression assumptions and renderer fallbacks that weakened unsupported conditions to false or unenforced comments.
- Replaced the flagship shell-based test step with semantic `standard.execute`, renamed non-executable target snapshots and made conformance compare exact current evidence.
- Consolidated repository YAML parsing on one shared Jackson boundary and removed the bespoke `MiniYaml` subset parser.

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
- `0.9.5.7.7` Policy-Driven Safety Prelude
- `0.9.5.7.8` Target Expression and Unknown Target Safety
- `0.9.5.7.9` Reference Scenario and Snapshot Honesty Reset

### Version boundary

- The correction work was promoted in package `0.9.5`.
- The v0.9.5.x identifiers remain historical scoped-work identifiers, not package versions.
- Package promotion is recorded in the `0.9.5` release section above.
- The active public Flow standard version is promoted to `0.8.0`.
- Public artifact contracts are promoted to `2.0` with an explicit migration.
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
