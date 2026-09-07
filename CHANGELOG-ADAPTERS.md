# Flow Adapter Portfolio Changelog

## Unreleased

### A0.6 Adapter Artifact Rendering

Started after PR #101 merged A0.5 as `8501927ec908ad6b4afbc7b06a95c219810426ff`.

Completed scope:

- add one strict adapter-owned rendering evidence document covering every built-in target;
- separate executable target syntax, non-executable review evidence and fail-fast outcomes;
- reserve provider-owned filenames such as `Jenkinsfile`, `github-actions.yml` and `tekton-pipeline.yaml` for executable target syntax only;
- add distinct Flow-owned review identities for every target and require review documents to declare `kind: TargetProjectionReview` and `executable: false`;
- make `TargetProjectionProvider.render()` reject every non-executable manifest before a concrete renderer is invoked;
- add one `AdapterArtifactRenderingAuthority` as the only application-edge decision path for executable and review artifacts;
- classify Jenkins and GitHub Actions renderers as supported, Tekton aggregate rendering as review-only, and uncomposed targets as unknown;
- preserve earlier capability, topology, control and continuity blockers instead of promoting scenarios from renderer availability;
- add `target-artifact-evidence.json` and bind every produced artifact to exact artifact and manifest SHA-256 digests;
- inventory compatibility, mapping, inputs, triggers, jobs, materialization, renderer payloads, typed bindings, adapter control and continuity metadata, render readiness, renderer evidence and limitations;
- reject tampered content, manifest drift, missing evidence, duplicate evidence identities, unresolved evidence paths and review artifacts impersonating executable files;
- emit no primary adapter artifact and no receipt for fail-fast rendering;
- distinguish `RENDERED_TARGET`, `REVIEW_DOCUMENT` and `DIAGNOSTIC_EVIDENCE` in typed CLI outcomes;
- make explicit review rendering produce a safe review artifact and review-required process status while retaining `CLI_RENDER_NOT_AUTHORIZED` for executable syntax;
- add the receipt to the public artifact bundle, producer contract, conformance manifest and public schema surface;
- route committed reference snapshots and shared conformance helpers through the canonical rendering authority rather than invoking providers directly;
- replay the Jenkins checkout-build-image executable reference through the production planner, all prior adapter authorities, final renderer and receipt validator;
- add positive executable, review-only, fail-fast and tamper-rejection behavior tests plus real CLI disk-export tests;
- extend the canonical post-Core adapter inventory to version `1.5` with six A0.6 checks;
- keep trigger expansion outside scope for A0.7;
- preserve package `0.9.5`, public standard `0.8.0`, artifact contract `2.0` and the frozen 91-check Core pre-closure inventory.

Validation history:

- Flow CI #2477 rejected the first implementation with twelve failures. The findings identified a missing producer contract for the receipt, direct provider calls in snapshot and conformance generation, obsolete review-only tests, and one message-coupled assertion.
- The provider executable-only guard was retained. Snapshot and conformance generation were migrated to the A0.6 authority, receipt provenance was registered explicitly, and review-only tests were updated to require a safe review document rather than no artifact.
- Flow CI #2483 reduced the result to three failures. Those were governance checks that still equated any rendered content with executable target syntax and an architecture test that did not yet recognize the new canonical rendering authority.
- The governance checks now inspect artifact kind, file identity, explicit non-executable markers and receipt mode instead of treating content existence as execution authority.
- Flow CI #2486, run `30790917001`, passed the implementation boundary on exact head `8d45a90120c3daeea0a3079b2d5ba8d0f9fce34d` and synthetic merge candidate `87782cecadebc33c195447983ccff84095276080`.
- Both jobs passed compile, 770 tests with zero failures, and 132 conformance checks with zero failures.
- The completion metadata head must pass a distinct exact-head and synthetic merge-candidate boundary before PR readiness.

### A0.5 Continuity Satisfaction Proof

Started after PR #100 merged the real-world pipeline corpus baseline as `52b15c1822d38397e13f772859d8f6b9a0606972`.

Completed scope:

- add one strict adapter-owned continuity evidence document covering DATA, ARTIFACT, MUTABLE_STATE and DURABLE_STATE for every target;
- consume the frozen Core `VALUE`, `WORKSPACE` and `STATE` relations without adding adapter meaning to Core;
- keep `ORDERING` outside continuity and prove that ordering-only plans create no transfer requirement;
- require a STATE relation to satisfy both mutable transfer and durable lifetime evidence;
- require every supported claim to cite a composed provider, production implementation and independent behavioral test;
- reject self-referential, registry-only, external-documentation and incomplete evidence;
- keep local, Argo Workflows and Azure DevOps continuity UNKNOWN without a composed provider;
- retain Jenkins DATA and STATE continuity as unsupported rather than inferring support from Groovy variables or job durability;
- retain GitHub Actions and Tekton data, workspace and state continuity as unsupported without relation-specific bindings;
- certify only Jenkins `artifact.shared-workspace` for the admitted checkout-build-image reference scenario;
- replay that scenario through the production intent loader, validator, planner, materialization pipeline and Jenkins renderer;
- require every executable reference to exercise at least one real continuity relation, avoiding vacuous empty-set proof;
- match adapter continuity before provider rendering and preserve unsupported or unknown blockers through diagnostic manifest evidence;
- prevent review-only outcomes from emitting target syntax;
- validate complete distribution evidence while allowing runtime tests to certify an exact active target subset;
- extend the canonical post-Core adapter inventory to version `1.4` with six A0.5 checks;
- keep package `0.9.5`, public standard `0.8.0`, artifact contract `2.0` and the frozen 91-check Core pre-closure inventory unchanged.

Validation history:

- Flow CI #2466 rejected the first implementation because runtime tests composed a target subset while the continuity authority validated the complete distribution document against that subset.
- The public integrity report still requires all distribution targets, while runtime certification now validates exactly the active target subset and still rejects missing active evidence.
- Flow CI #2468, run `30741864674`, passed the implementation boundary on exact head `b1b996508aa9bfb1f645cfd69f7ddb777e22c1d5` and synthetic merge candidate `3538862c7ad1878d8d6d3ae00e8f5447949511aa`.
- Both jobs passed compile, 753 tests with zero failures, and 126 conformance checks with zero failures.
- The completion metadata head must pass a distinct exact-head and synthetic merge-candidate boundary before PR readiness.

### A0.4 Control Requirement Materialization

Started after A0.3 merged through PR #98 as `9c09a03b3fa6a5b7b2114ff1d7aa7f533bacf930`.

Completed scope:

- add a strict adapter-owned control materialization document for every built-in target;
- declare exactly one approval, retry, timeout, compensation and scheduling claim per target;
- partition each closed family contract into supported, unsupported and unknown semantics;
- retain exact requirement semantic, subject, completeness and scope;
- preserve manual, environment, external and unknown approval modes without reinterpretation;
- separate repository implementation evidence from repository behavioral evidence and official platform capability context;
- require independent `src/main` implementation and `src/test` behavior evidence for every supported semantic;
- validate source anchors and reject repository-path escape, missing, duplicate, blank, self-referential and registry-only evidence;
- apply identical integrity rules to YAML-loaded and typed evidence documents;
- require supported claim scopes to equal the union required by supported target-neutral semantics;
- return fail-closed reports for unknown future semantics instead of throwing lookup exceptions;
- derive exact requirements from `ApprovalNode`, `RetryGroupNode`, protected `TryPlanNode`, failure rollback and schedule triggers;
- retain preserved RETRY/TIMEOUT metadata as `PRESERVED_UNSPECIFIED` UNKNOWN blockers until exact scope and value survive lowering;
- historical A0.4 flow-level handler recognition used planner provenance; AR-02D supersedes it with authorization-owned typed `WorkflowFailurePolicy`, while detached compatibility-shaped handlers remain blocking;
- reject detached error handlers that do not protect work;
- certify only Jenkins inline manual approval, protected Jenkins error handlers and Jenkins/GitHub CRON subsets currently implemented by composed providers;
- prove CRON behavior through canonical AST, parser, planner, explicit selection, control assessment, manifest generation, readiness reconciliation and concrete rendering;
- explicitly demote retry flattening, timeout absence, unguarded compensation, uncomposed environment approval and Tekton scheduling;
- evaluate adapter controls before executable target materialization;
- use one reconciliation authority for successful and diagnostic manifest metadata;
- expose blocked execution readiness while retaining review-only evidence and preventing target syntax emission;
- replace dynamic readiness diagnostic code concatenation with one closed mapping to stable catalog codes;
- split evidence integrity, requirement derivation and materialization orchestration without creating a second production decision path;
- require the complete production, test, conformance and documentation boundary before lifecycle completion;
- extend the post-Core adapter inventory to version `1.3` with five A0.4 checks;
- preserve package `0.9.5`, public standard `0.8.0`, artifact contract `2.0` and the frozen 91-check Core pre-closure inventory.

Architecture assessment:

- Jenkins, GitHub Actions, Tekton, Argo Workflows, GitLab CI/CD and Azure Pipelines expose materially different control ownership and scope;
- workflow primitives, run-object configuration, target-resource checks and external controllers cannot be represented by one target feature boolean;
- Flow's meaning → exact requirement → provider evidence direction is retained;
- general platform support and popular repository patterns remain context, not implementation authority.

Validation history:

- Earlier Flow CI runs rejected incomplete fixtures, release-honesty wording, imprecise evidence polarity, dynamic public diagnostic codes, optimistic readiness assumptions, invalid runtime subset certification and schedule tests that bypassed production generation.
- Flow CI #2371 rejected a scope test that expected malformed evidence to pass and CRON fixtures without executable provider work.
- Flow CI #2373 rejected manually authored tasks without canonical effect evidence.
- Flow CI #2374 proved Jenkins rendering and exposed missing GitHub schedule capability provenance.
- Flow CI #2375 isolated the remaining GitHub failure to a trigger attached after planning.
- The final CRON fixture injects the trigger into canonical AST and lets `FlowPlanner` derive `trigger.schedule.cron` before provider materialization.
- Flow CI #2376, run `30420527005`, passed exact implementation head `a5040767698fed38d6efd0118de40312c77c913e` and synthetic merge candidate `8c3b837cb9416acb2de3333a81b1c7eb811d492d`; both compile/test and conformance jobs passed independently.
- The A0.4 work package records that passed implementation boundary, A0.4 is completed, and A0.5 is selected as next without including its work package or implementation.
- The completion metadata head must pass a distinct exact-head and synthetic merge-candidate boundary before PR readiness.

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
