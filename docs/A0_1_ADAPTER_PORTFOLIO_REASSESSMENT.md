# A0.1 Adapter Portfolio Reassessment

## Purpose

A0.1 starts the adapter roadmap only after the v0.9.7 Core semantic foundation has closed. It does not expand adapter behavior. It establishes an honest baseline for the behavior and evidence already present in the distribution.

The reassessment separates three claims that must never be treated as synonyms:

1. `PROFILE_ONLY`: capability and topology declarations exist, but no composed target provider or native action rule is shipped.
2. `NATIVE_LEAF_ONLY`: a composed provider owns reviewed native contracts for individual semantic leaves, but no end-to-end executable scenario is claimed.
3. `EXECUTABLE_REFERENCE`: provider and native-leaf evidence is accompanied by committed end-to-end scenario evidence for the named target.

A target capability profile is not a renderer. A renderer is not proof of continuity. Two native actions are not automatically an executable workflow. Human beings have repeatedly attempted to turn those implications into architecture, so the distinctions are now executable governance.

## Portfolio authority

`adapters/portfolio/builtin-adapter-portfolio.yaml` contains exactly one record for every target in `targets/`.

Each record declares:

- whether the target is a semantic reference or a concrete adapter;
- the current support class;
- a concise support summary;
- repository evidence references;
- committed executable evidence when claimed;
- explicit limitations with their own evidence references.

`AdapterPortfolioAuthority` derives provider availability and native projection rules from actual application composition and target registry data. Authored portfolio prose cannot promote a target beyond those facts.

Unknown fields, duplicate target records, missing targets, unresolved evidence, missing limitations, invalid role/class combinations and unsupported provider claims fail closed.

## Current assessment

### Local

`local` is `SEMANTIC_REFERENCE / PROFILE_ONLY`. It validates target-neutral planning contracts. The built-in distribution does not compose a local generator or renderer, so it is not a production adapter claim.

### Jenkins

`jenkins` is `TARGET_ADAPTER / EXECUTABLE_REFERENCE`.

Evidence includes:

- a composed generator, renderer and native catalog;
- native `git.checkout` and `docker.build` contracts;
- the committed `checkout-build-image` snapshot with executable Jenkins target state.

This does not make every Jenkins semantic action executable. Generic standard execute and rollback work remains notes-projected, feature-level state continuity is partial, and several trigger forms remain partial.

### GitHub Actions

`github-actions` is `TARGET_ADAPTER / NATIVE_LEAF_ONLY`.

The provider owns reviewed checkout and image-build actions, but the job-per-task projection does not prove workspace propagation for the multi-step reference. Durable state and state propagation remain unsupported. No end-to-end executable claim is published.

### Tekton

`tekton` is `TARGET_ADAPTER / NATIVE_LEAF_ONLY`.

The provider owns reviewed git-clone and buildah Task contracts. Workspace support is partial and complete PipelineRun workspace binding is not committed as executable scenario evidence. Manual approval is unsupported and the renderer remains explicitly partial.

### Argo Workflows and Azure DevOps

Both targets are `TARGET_ADAPTER / PROFILE_ONLY`.

They have capability and topology profiles, but this distribution composes no generator, renderer or native projection catalog for them and their registry entries declare no native action rules. Their presence in target compatibility data is not an implementation promise.

## Roadmap lifecycle authority

Flow Agent tooling selects A0.1 as the `next` item in the primary `adapters` stream. The A0.1 work package is independently `active` while implementation is in progress.

`AdapterRoadmapLifecycleAuthority` reconciles the adapter roadmap, roadmap index, release state and A0.1 work package. It accepts only two states:

- `IMPLEMENTING`: A0.1 is `next`, A0.2 is `planned`, the work package is `active` and no authored implementation evidence exists;
- `COMPLETED`: A0.1 is `completed`, A0.2 is `next`, the work package is `complete` and a structurally passing exact-head plus synthetic merge-candidate Flow CI boundary is recorded.

Mixed statuses, premature evidence, malformed workflow fields, unknown evidence keys and a completion transition that does not select A0.2 fail closed.

## Separate conformance stream

The frozen Core inventory under `standard/conformance/pre-closure-check-inventory.yaml` remains unchanged.

Adapter checks are declared separately in `adapters/conformance/check-inventory.yaml` and run after `v0.9.7.10.bounded-semantic-closure`. This prevents later adapter work from retroactively changing the evidence set that closed Core.

A0.1 checks:

- lifecycle integrity across work package and roadmap metadata;
- portfolio coverage and classification integrity;
- executable-reference snapshot honesty;
- absence of adapter-portfolio dependencies from semantic Core packages.

## Non-goals

A0.1 does not:

- add or improve a target renderer;
- add a runtime executor or plugin lifecycle;
- introduce target-specific public Flow syntax;
- change canonical intent, effects, controls, topology or continuity requirements;
- promote GitHub Actions or Tekton to end-to-end executable status;
- turn Argo Workflows or Azure DevOps profiles into implementation claims;
- alter the public Flow standard or artifact contract version.
