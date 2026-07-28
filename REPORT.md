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

## Core boundary

PR #95 merged the final bounded Core closure correction as `e25a81b9c7e7802556a0d5b34cf34185b19ed498`. Core v0.9.7 remains CLOSED. Its exact 91-check pre-closure inventory remains frozen and adapter work runs after the semantic closure check rather than rewriting that historical evidence set.

The implementation package remains `0.9.5`, the public standard remains `0.8.0`, and the artifact contract remains `2.0`.

## A0.1 portfolio reassessment

The built-in target registry declares six targets. A0.1 classifies them from actual registry, provider and committed scenario evidence:

| Target | Role | Support class | Honest boundary |
| --- | --- | --- | --- |
| `local` | semantic reference | `PROFILE_ONLY` | Target-neutral planning reference; no shipped generator or renderer. |
| `jenkins` | target adapter | `EXECUTABLE_REFERENCE` | Native checkout and image-build leaves plus the committed `checkout-build-image` executable scenario. |
| `github-actions` | target adapter | `NATIVE_LEAF_ONLY` | Reviewed checkout and image-build actions; workspace continuity does not support an end-to-end executable claim. |
| `tekton` | target adapter | `NATIVE_LEAF_ONLY` | Reviewed git-clone and buildah tasks; Pipeline-level workspace and production readiness remain incomplete. |
| `argo-workflows` | target adapter | `PROFILE_ONLY` | Capability and topology profile only; no composed projection provider or native rules. |
| `azure-devops` | target adapter | `PROFILE_ONLY` | Capability and topology profile only; no composed projection provider or native rules. |

These are not marketing tiers. `PROFILE_ONLY`, `NATIVE_LEAF_ONLY` and `EXECUTABLE_REFERENCE` are mechanically constrained evidence classes. Capability support cannot impersonate a renderer, and native leaf coverage cannot impersonate multi-step continuity.

## Authority and conformance

`adapters/portfolio/builtin-adapter-portfolio.yaml` is the distribution-owned support, limitation and evidence record. `AdapterPortfolioAuthority` reconciles it against:

- every target registry identity;
- actual built-in provider composition;
- actual native projection rules;
- evidence reference existence;
- role and support-class invariants;
- explicit limitations for every record.

`AdapterRoadmapLifecycleAuthority` separately proves that roadmap and work-package metadata move atomically:

- implementation: A0.1 `next`, A0.2 `planned`, work package `active`, no authored implementation evidence;
- completion: A0.1 `completed`, A0.2 `next`, work package `complete`, one structurally passing exact-head and merge-candidate Flow CI boundary.

Adapter conformance has its own committed inventory under `adapters/conformance/check-inventory.yaml`. It certifies:

- A0.1 lifecycle integrity;
- exact portfolio reassessment coverage;
- committed executable-reference evidence;
- absence of Adapter Portfolio dependencies from semantic Core packages.

The adapter checks execute only after `v0.9.7.10.bounded-semantic-closure`. Therefore A0.x can evolve without silently changing what closed Core v0.9.7.

## Validation history

Flow CI #2263 passed the initial portfolio and roadmap-transition implementation before lifecycle certification was added. It remains historical evidence and is not the A0.1 completion authority.

Flow CI #2270, run `30342473373`, passed the complete A0.1 implementation with lifecycle certification on:

- exact head `20191d2d7b9460894c81f9bfee73b3c11b8f78f4`;
- synthetic merge candidate `d3238468ba01fe8b97bc6f500c9f96f2beeb4760`;
- Flow Agent tooling, structure validation and context generation;
- complete compilation and tests;
- standalone Core closure plus the separate adapter conformance inventory.

The current completion-metadata head and its synthetic merge candidate must independently pass before PR #96 may become ready for review. A0.2 is selected as next, but no A0.2 implementation belongs to this PR.

## Architecture boundary

A0.1 did not add a renderer, target-specific public syntax, runtime executor, SDK lifecycle or new Core semantic requirement. It reassessed existing distribution claims before further adapter work is allowed.
