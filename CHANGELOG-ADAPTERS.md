# Flow Adapter Portfolio Changelog

## Unreleased

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
- A0.2 remains `completed`, A0.3 remains `next`, and the corrected completion head must independently pass exact-head and merge-candidate validation before PR #97 is ready for review.

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
