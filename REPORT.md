# Flow Portfolio Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed Core roadmap identity: `0.9.7.10 Bounded Semantic Closure Gate`
Core roadmap item status: `completed`
Completed correction item: `0.9.7.10.2 Closure Evidence Boundary Integrity Correction`
Completed Core closure item: `0.9.7.10 Bounded Semantic Closure Gate` (`completed`)
Completed adapter roadmap item: `A0.2 Topology Evidence Adoption` (`completed`)
Next adapter roadmap item: `A0.3 Capability Binding Migration` (`next`)

## Core boundary

PR #95 merged the final bounded Core closure correction as `e25a81b9c7e7802556a0d5b34cf34185b19ed498`. Core v0.9.7 remains CLOSED. Its exact 91-check pre-closure inventory remains frozen. Adapter checks continue only after `v0.9.7.10.bounded-semantic-closure`.

The implementation package remains `0.9.5`, the public standard remains `0.8.0`, and the artifact contract remains `2.0`.

## A0.1 baseline

PR #96 merged A0.1 as `dbd1529cd9da1b21d13bd45d3af1a84361b9abf1`. The resulting portfolio classes remain:

| Target | Role | Support class |
| --- | --- | --- |
| `local` | semantic reference | `PROFILE_ONLY` |
| `jenkins` | target adapter | `EXECUTABLE_REFERENCE` |
| `github-actions` | target adapter | `NATIVE_LEAF_ONLY` |
| `tekton` | target adapter | `NATIVE_LEAF_ONLY` |
| `argo-workflows` | target adapter | `PROFILE_ONLY` |
| `azure-devops` | target adapter | `PROFILE_ONLY` |

A0.2 consumes those classifications. It does not promote a target merely because a platform is generally capable of a feature.

## A0.2 defect and correction

The previous target registry topology blocks were complete in shape but self-referential in evidence: each target cited its own `targets/builtin-targets.yaml` topology block. Therefore an authored `supported` value certified itself.

This produced unsupported claims, including fully supported Jenkins attempt, durable-state and state-propagation properties and broad Argo Workflows and Azure DevOps topology claims despite neither target having a composed provider.

A0.2 introduces `adapters/topology/builtin-adapter-topology.yaml` as the distribution-owned evidence authority. Every target now declares:

- all eleven frozen Core topology kinds;
- interaction and concurrency evidence;
- one closed support status per claim;
- a concrete mechanism;
- repository evidence references;
- explicit limitations for every non-supported status.

Self-references to the topology manifest or target registry, unresolved references, unknown claims, missing claims, duplicates and facet mismatches fail closed.

## Runtime authority

`TargetRegistryYamlLoader` derives runtime `ExecutionTopologyProfile` instances through `AdapterTopologyProfileFactory` whenever the adapter evidence manifest is present.

Legacy inline registry topology blocks remain parseable for isolated fixtures, but they cannot override distribution evidence. Runtime declarations carry only the target-neutral status and exact adapter evidence identity. Detailed mechanism and limitation prose remains exclusively in the adapter manifest instead of being duplicated into Core diagnostics or canonical snapshots.

The final reassessment is:

- Jenkins keeps `SUPPORTED` workflow scope, workflow lifetime, suspend/resume for provider-owned manual approval, ephemeral workspace, workspace propagation and failure propagation. Attempt isolation becomes `UNKNOWN`; broader unproven properties become `PARTIAL`.
- GitHub Actions keeps supported workflow scope, workflow lifetime and ephemeral workspace. Workspace and state propagation remain `UNSUPPORTED`.
- Tekton keeps supported workflow scope, branch isolation and workflow lifetime. Workspace evidence remains `PARTIAL`; durable state, state propagation and suspend/resume remain `UNSUPPORTED`.
- Argo Workflows and Azure DevOps are `UNKNOWN` for every topology claim because their A0.1 support class is `PROFILE_ONLY` and no provider is composed.
- Local remains a semantic reference and does not become production execution evidence.

## Executable reference preservation

The committed Jenkins `checkout-build-image` scenario requires workflow scope, workflow lifetime, ephemeral workspace and workspace propagation. All four remain `SUPPORTED` by adapter-owned evidence.

The executable topology conformance check validates the committed snapshot and semantic-plan artifact, regenerates the plan from its reference intent through the production intent → AST → validation → planning pipeline, evaluates it through `ExecutionTopologyMatchingAuthority`, and requires every consumed claim to be `SUPPORTED` with evidence originating in the adapter topology manifest.

Jenkins therefore remains an executable reference without retaining unrelated unproven topology claims or treating canonical JSON export as an internal polymorphic plan loader.

## Validation history

Flow CI #2274 correctly rejected the first A0.2 implementation because the relative target loader bypassed adapter evidence, Jenkins approval was under-classified, the executable proof attempted unsupported plan deserialization, and release-state wording violated external-candidate policy.

Flow CI #2279 confirmed those production fixes and rejected a remaining architecture duplication when adapter mechanism prose was copied into Core runtime diagnostics, changing canonical snapshots. The runtime profile was corrected instead of regenerating snapshots around the duplication.

Flow CI #2284, run `30348796256`, passed the final implementation on:

- exact head `6d441628c4d3101bfd0c32c5d2eb370b00fbc4fe`;
- synthetic merge candidate `6168e45d292a34f3d648c0e645016f88c813e25b`;
- Flow Agent tooling, structure validation and context generation;
- complete compilation and tests;
- frozen Core closure followed by adapter inventory version `1.1`;
- all A0.1 and A0.2 conformance checks.

A0.2 is now `completed` and A0.3 is `next`. The current completion-metadata head and its synthetic merge candidate must independently pass before PR #97 may become ready for review. No A0.3 work package or implementation belongs to this PR.

## Architecture boundary

A0.2 did not add a renderer, provider, runtime executor, storage mechanism, target-specific public DSL or new Core topology kind. It changed adapter evidence ownership and intentionally demoted unsupported claims while preserving the frozen Core model.
