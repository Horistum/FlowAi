# Flow Adapter Portfolio Changelog

## Unreleased

### A0.4 Control Requirement Materialization

Started after A0.3 merged through PR #98 as `9c09a03b3fa6a5b7b2114ff1d7aa7f533bacf930`.

Implementation scope under validation:

- add a strict adapter-owned control materialization manifest for every built-in target;
- declare exactly one approval, retry, timeout, compensation and scheduling claim per target;
- partition each closed family contract into supported, unsupported and unknown semantics;
- separate repository implementation evidence from official platform capability context;
- require independent `src/main` or `src/test` evidence for every supported semantic;
- permit target registry data only as supplemental negative corroboration and reject registry-only evidence;
- derive exact requirements from `ApprovalNode`, `RetryGroupNode`, `TryPlanNode`, failure rollback and schedule triggers;
- retain preserved RETRY/TIMEOUT metadata as `PRESERVED_UNSPECIFIED` UNKNOWN blockers until exact scope and value survive lowering;
- certify only Jenkins inline manual approval, Jenkins/GitHub CRON and Jenkins error-handler subsets currently implemented by composed providers;
- explicitly demote retry flattening, timeout absence, unguarded compensation, uncomposed environment approval and Tekton scheduling;
- evaluate adapter controls before executable target materialization;
- preserve blocked facts through review evidence while preventing target syntax emission;
- replace dynamic readiness diagnostic code concatenation with one closed mapping to existing stable catalog codes;
- add positive, negative, CLI integration, lifecycle and stable diagnostic mapping tests;
- extend the post-Core adapter inventory to version `1.3` with five A0.4 checks;
- preserve package `0.9.5`, public standard `0.8.0`, artifact contract `2.0` and the frozen 91-check Core pre-closure inventory.

Architecture assessment:

- Jenkins, GitHub Actions, Tekton, Argo Workflows, GitLab CI/CD and Azure Pipelines expose materially different control ownership and scope;
- workflow primitives, run-object configuration, target-resource checks and external controllers cannot be represented by one target feature boolean;
- Flow's meaning → exact requirement → provider evidence direction is retained;
- general platform support and popular repository patterns remain context, not implementation authority.

Validation history:

- Flow CI #2315 rejected the first implementation because one negative test used an incomplete `RetryGroupNode` fixture; production sources compiled and the fixture was corrected.
- Flow CI #2316 compiled the implementation and rejected release metadata that lacked exact external-candidate wording plus an evidence rule that did not distinguish positive proof from negative registry corroboration.
- Release honesty remained strict; contracts/loader and runtime assessment were separated, supported claims now require independent implementation evidence, and registry-only evidence fails.
- Flow CI #2324 rejected persisted CLI review bundles because the existing readiness reconciler invented uncatalogued diagnostic code `TARGET_COMPATIBILITY_UNSUPPORTED` through string concatenation.
- `TargetReadinessDiagnosticCodeAuthority` now maps known internal statuses to existing stable public codes and rejects unknown statuses instead of inventing public identifiers.
- A0.4 remains `next` and its work package remains `active`. No implementation evidence or A0.5 transition is authored until exact-head and synthetic merge-candidate Flow CI pass independently.

### A0.3 Capability Binding Migration

Started after A0.2 merged through PR #97 as `964a9c4f8bf9ce9dc8771c99a68393c9edc35807`.

Completed scope:

- add one target-neutral `IntentBindingContractAuthority` for explicit module-action bindings;
- keep implementation selection explicit through `uses` and `params.system`;
- partition canonical parameters into mapped and unsupported semantics plus binding-only action inputs;
- resolve authored values and module descriptor defaults exactly once before AST lowering;
- retain parameter source evidence as semantic, binding or default;
- make AST lowering consume resolved binding evidence rather than re-reading module descriptors;
- preserve canonical capability, semantic effects and source selection provenance through ExecutionPlan;
- add a strict adapter-owned binding manifest for every built-in `implements` claim;
- distinguish concrete adapter implementations from the `standard.rollback` semantic fallback;
- remove the unsupported `argocd.sync → SYNC` claim because source and destination cannot be represented;
- add positive and negative binding authority, lifecycle and behavior tests;
- extend the post-Core adapter inventory to version `1.2` with five A0.3 checks;
- retain one canonical `AdapterStreamConformanceRunner` and remove a duplicate complete-stream composition found during final review;
- preserve package `0.9.5`, public standard `0.8.0`, artifact contract `2.0` and the frozen 91-check Core pre-closure inventory.

Validation history:

- Flow CI #2293 rejected the first implementation because binding-key sorting was not type-safe and nullable resolved inputs did not smart-cast across the validity boundary.
- Flow CI #2298 passed compilation and most binding tests, then rejected a provenance assertion against a quoted legacy presentation string and release metadata that did not satisfy the exact external-candidate wording policy.
- Provenance validation was corrected to compare ExecutionPlan fields with typed `IntentBindingEvidence`; the release evidence policy was preserved rather than weakened.
- Flow CI #2300 passed exact-head and merge-candidate validation before final review found two competing complete adapter-stream composition classes.
- The duplicate runner was removed and the existing canonical `AdapterStreamConformanceRunner` was extended to own inventory `1.2`.
- Flow CI #2303, run `30354834937`, passed the final implementation on exact head `f475ae8317122b986dec23a3d36bb8df05a31db3` and synthetic merge candidate `afdfa505e96ba00eecf42a94d9b2e1ebe7059ec8`.
- Flow CI #2310 rejected the first completion head because the repository lifecycle integration test was hardcoded to `IMPLEMENTING` even though exact phase tests already covered IMPLEMENTING and COMPLETED independently.
- The repository test now accepts exactly one honest supported lifecycle phase while retaining explicit failure tests for missing evidence, premature evidence, skipped items and non-adjacent progress.
- Flow CI #2313, run `30356352115`, passed the corrected completion state on exact head `53d9794e7d4165942339b8ae37db6c911f01f83a` and synthetic merge candidate `3a052e10fcbfca4e3fe237b5864f1efab0912758` before PR #98 merged.

### A0.2 Topology Evidence Adoption

Started after A0.1 merged through PR #96 as `dbd1529cd9da1b21d13bd45d3af1a84361b9abf1`.

Completed scope:

- add a strict adapter-owned topology evidence manifest covering every target;
- require every frozen Core topology kind plus interaction and concurrency evidence;
- require concrete mechanisms, repository references and explicit limitations;
- reject missing, duplicate, unknown, unresolved and self-referential claims;
- derive runtime `ExecutionTopologyProfile` instances from adapter evidence;
- keep runtime profiles target-neutral by carrying only status and exact evidence identity;
- retain detailed mechanisms and limitations exclusively in the adapter authority;
- retain legacy inline registry topology only as a non-authoritative fixture fallback;
- intentionally narrow unsupported Jenkins topology claims while preserving the executable reference and provider-owned manual approval requirements;
- retain GitHub Actions workspace and state propagation as unsupported;
- retain Tekton workspace evidence as partial until PipelineRun provisioning is proven;
- demote every Argo Workflows and Azure DevOps topology claim to unknown while no provider is composed;
- prove that profile-only targets block topology-dependent execution;
- validate the committed Jenkins snapshot and regenerate its semantic plan through the production planning pipeline;
- prove the Jenkins executable reference consumes only supported adapter topology evidence;
- add A0.2 lifecycle integrity and five new checks to the post-Core adapter inventory;
- make completed A0.1 and A0.2 lifecycle authorities historical and forward-stable while requiring adjacent current roadmap progress;
- preserve package `0.9.5`, public standard `0.8.0`, artifact contract `2.0` and the frozen 91-check Core pre-closure inventory.

Validation history:

- Flow CI #2274 rejected the first implementation because the relative target loader bypassed adapter evidence, Jenkins manual approval was under-classified, executable proof attempted unsupported plan deserialization and release-state wording violated external-candidate policy.
- Flow CI #2279 confirmed those production fixes and then rejected duplicated adapter prose in Core runtime diagnostics through canonical snapshot mismatches.
- The runtime profile was corrected to retain only target-neutral status and exact evidence identity instead of regenerating snapshots around an architectural duplication.
- Flow CI #2284, run `30348796256`, passed the final implementation on exact head `6d441628c4d3101bfd0c32c5d2eb370b00fbc4fe` and synthetic merge candidate `6168e45d292a34f3d648c0e645016f88c813e25b`.
- Flow CI #2285 rejected the first completion state because the completed A0.1 authority permanently required `A0.2 next`, making any later roadmap progress invalid.
- `AdapterRoadmapSequence` now requires current completed and next items to remain adjacent while completed item authorities preserve their own historical evidence instead of freezing the global pointer.
- Flow CI #2291 passed the corrected completion state before PR #97 merged.

### A0.1 Adapter Portfolio Reassessment

Started the adapter roadmap after Core `0.9.7.10` and bounded correction `0.9.7.10.2` were merged and validated.

Completed scope:

- transition the primary roadmap stream from completed Core work to adapters;
- generalize Flow Agent roadmap selection and tests for a non-Core primary stream;
- introduce a strict distribution-owned adapter portfolio manifest;
- require exactly one support, limitation and evidence record for every target registry identity;
- distinguish semantic reference, profile-only, native-leaf-only and executable-reference claims;
- reconcile portfolio claims with actual provider composition and native projection rules;
- preserve Jenkins as the only current committed end-to-end executable reference target;
- classify GitHub Actions and Tekton as native-leaf-only;
- classify Argo Workflows and Azure DevOps as profile-only;
- retain `local` as a semantic reference rather than promoting it into a production adapter;
- add committed executable snapshot validation for executable-reference claims;
- add `AdapterRoadmapLifecycleAuthority` to reject mixed, premature or evidence-free A0.1 completion metadata;
- add a separate adapter conformance inventory that runs after frozen Core closure;
- prove semantic Core packages do not depend on adapter portfolio authority;
- preserve package `0.9.5`, public standard `0.8.0` and artifact contract `2.0`.

Validation history:

- Flow CI #2256 rejected the first roadmap transition because A0.1 used unsupported item status `active`; the primary-stream tooling and metadata were corrected to the existing `next` lifecycle rather than weakening validation.
- Flow CI #2270, run `30342473373`, passed the complete implementation on exact head `20191d2d7b9460894c81f9bfee73b3c11b8f78f4` and synthetic merge candidate `d3238468ba01fe8b97bc6f500c9f96f2beeb4760`.
- Flow CI #2272, run `30343249002`, passed the completion metadata on exact head `9d8e75bfd5d38f0b4827881174ea4201c063f2f4` and synthetic merge candidate `1934370c2a26a930961ab96a67fc2280b2ce3a39`.
- PR #96 merged A0.1 as `dbd1529cd9da1b21d13bd45d3af1a84361b9abf1`.
