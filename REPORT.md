# Flow Portfolio Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed Core roadmap identity: `0.9.7.10 Bounded Semantic Closure Gate`
Core roadmap item status: `completed`
Completed correction item: `0.9.7.10.2 Closure Evidence Boundary Integrity Correction`
Completed Core closure item: `0.9.7.10 Bounded Semantic Closure Gate` (`completed`)
Completed adapter roadmap item: `A0.1 Adapter Portfolio Reassessment` (`completed`)
Next adapter roadmap item: `A0.2 Topology Evidence Adoption` (`next`)
Active adapter work package: `A0.2 Topology Evidence Adoption` (`active`)

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
- interaction evidence;
- concurrency evidence;
- one closed support status per claim;
- a concrete mechanism;
- repository evidence references;
- explicit limitations for every non-supported status.

Self-references to the topology manifest or target registry, unresolved references, unknown claims, missing claims, duplicates and facet mismatches fail closed.

## Runtime authority

`TargetRegistryYamlLoader` now derives runtime `ExecutionTopologyProfile` instances through `AdapterTopologyProfileFactory` whenever the adapter evidence manifest is present.

Legacy inline registry topology blocks remain parseable for isolated fixtures, but they cannot override distribution evidence. Runtime declarations carry only the closed status and an exact reference to the adapter claim. Detailed mechanism and limitation prose remains exclusively in the adapter manifest rather than being duplicated into Core compatibility diagnostics and canonical snapshots.

The important reassessment outcomes are:

- Jenkins keeps `SUPPORTED` workflow scope, workflow lifetime, suspend/resume for provider-owned manual approval, ephemeral workspace, workspace propagation and failure propagation. Attempt isolation becomes `UNKNOWN`; several broader properties become `PARTIAL`.
- GitHub Actions keeps supported workflow scope, workflow lifetime and ephemeral workspace. Workspace and state propagation remain `UNSUPPORTED`.
- Tekton keeps supported workflow scope, branch isolation and workflow lifetime. Workspace evidence remains `PARTIAL`; durable state, state propagation and suspend/resume remain `UNSUPPORTED`.
- Argo Workflows and Azure DevOps are demoted to `UNKNOWN` for every topology claim because their A0.1 support class is `PROFILE_ONLY` and no provider is composed.
- Local remains a semantic reference and does not become production execution evidence.

## Executable reference preservation

The committed Jenkins `checkout-build-image` scenario requires workflow scope, workflow lifetime, ephemeral workspace and workspace propagation. All four remain `SUPPORTED` by adapter-owned evidence.

The executable topology conformance check validates the committed snapshot and semantic-plan artifact, regenerates the plan from its reference intent through the production semantic pipeline, evaluates it through `ExecutionTopologyMatchingAuthority`, and requires every consumed claim to be `SUPPORTED` with evidence originating in the adapter topology manifest.

Jenkins therefore remains an executable reference without retaining unrelated unproven topology claims or treating canonical JSON export as an internal polymorphic plan loader.

## Adapter conformance

The adapter inventory is version `1.1` and contains the four A0.1 checks plus five A0.2 checks:

- A0.2 lifecycle integrity;
- topology evidence integrity;
- runtime topology authority;
- profile-only demotion;
- executable topology proof.

Flow CI #2274 correctly rejected the first A0.2 implementation because the relative target loader bypassed the adapter manifest, Jenkins approval was under-classified, the executable proof attempted unsupported plan deserialization, and release evidence wording violated the external-candidate policy.

Flow CI #2279 confirmed that those production issues were fixed, then rejected a remaining architectural duplication: adapter mechanism prose had been copied into Core runtime profile diagnostics, changing canonical reference snapshots. The runtime profile now carries only status and evidence identity while the detailed prose remains adapter-owned.

A0.2 remains `next` and its work package remains `active`. No implementation evidence is authored until an exact-head and synthetic merge-candidate Flow CI run passes with the corrected authority and checks active.

## Architecture boundary

A0.2 does not add a renderer, provider, runtime executor, storage mechanism, target-specific public DSL or new Core topology kind. It changes adapter evidence ownership and intentionally demotes unsupported claims while preserving the frozen Core model.
