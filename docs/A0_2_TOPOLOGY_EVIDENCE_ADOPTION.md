# A0.2 Topology Evidence Adoption

## Purpose

A0.2 replaces self-referential target topology declarations with distribution-owned adapter evidence. It consumes the frozen Core topology vocabulary and matching semantics established by Core v0.9.7; it does not add a new universal topology kind or change what any kind means.

A target name, platform feature list, renderer class or capability profile is not topology evidence. The previous built-in registry cited its own topology block as evidence, so an authored `supported` value certified itself. A0.2 removes that field from the runtime authority path.

## Evidence authority

`adapters/topology/builtin-adapter-topology.yaml` contains exactly one record for every target in the adapter portfolio and target registry.

Every record declares all eleven frozen Core topology kinds:

- workflow scope;
- branch isolation;
- attempt isolation;
- workflow lifetime;
- suspend and resume;
- ephemeral workspace;
- durable state;
- value propagation;
- workspace propagation;
- state propagation;
- failure propagation.

It also declares the adapter-owned interaction and concurrency facets required by the A0.2 roadmap item.

Each claim contains:

- one exact facet;
- one closed status: `SUPPORTED`, `PARTIAL`, `UNSUPPORTED` or `UNKNOWN`;
- a concrete mechanism statement;
- repository evidence references;
- explicit limitations for every non-supported status.

Unknown claims, missing claims, duplicate claims, facet mismatches, unresolved references and self-references to the target registry or topology manifest fail closed.

## Runtime ownership

`TargetRegistryYamlLoader` loads the adapter topology manifest beside the distribution target registry and derives each `ExecutionTopologyProfile` through `AdapterTopologyProfileFactory`.

The target-neutral runtime profile contains:

- one declaration for every frozen Core topology kind;
- status copied from the adapter evidence claim;
- an evidence reference pointing back to the exact adapter claim;
- no duplicated adapter-specific mechanism or limitation prose.

Mechanisms and limitations remain in the adapter-owned manifest as the single detailed evidence authority. Core matching consumes their closed status and exact evidence identity without importing target-specific prose into canonical blocker messages or reference snapshots.

Legacy inline `topology.capabilities` blocks remain parseable for isolated fixtures and backward-compatible registry documents. They are not runtime authority when the adapter evidence manifest is present and cannot override it.

## Honest reassessment

### Jenkins

The executable `checkout-build-image` scenario requires workflow scope, workflow lifetime, ephemeral workspace and workspace propagation. Those four claims remain `SUPPORTED` and are tied to the committed plan, Jenkins projection and executable snapshot.

Provider-owned manual approval also keeps suspend/resume `SUPPORTED` because the renderer emits Jenkins `input` and behavior tests prove pause and continuation within one Pipeline run.

Earlier broad claims were narrowed where evidence is incomplete:

- attempt isolation becomes `UNKNOWN`;
- branch isolation, durable state, value propagation, state propagation, interaction and concurrency become `PARTIAL`;
- failure propagation remains `SUPPORTED`.

The Jenkins executable reference therefore remains valid without pretending that every Jenkins topology property is fully proven.

### GitHub Actions

Workflow scope, workflow lifetime and per-job ephemeral workspace are `SUPPORTED`. Workspace propagation and state propagation are `UNSUPPORTED` because separate jobs receive separate runner state and the provider emits no transfer bridge. Other control and transfer properties remain `PARTIAL` or `UNKNOWN` according to actual renderer evidence.

### Tekton

Workflow scope, branch isolation and workflow lifetime are `SUPPORTED`. Named Pipeline workspaces provide `PARTIAL` workspace scope and propagation evidence, but PipelineRun provisioning and end-to-end execution remain unproven. Suspend/resume, durable state and state propagation remain `UNSUPPORTED`.

### Argo Workflows and Azure DevOps

A0.1 classified both as `PROFILE_ONLY`: no provider, renderer or native projection catalog is composed. A0.2 therefore demotes every topology claim to `UNKNOWN`. Platform documentation may describe capable products, but it does not prove a shipped Flow adapter. Any topology-dependent plan remains blocked for these targets.

### Local semantic reference

`local` remains a semantic reference rather than a target adapter. Its profile describes what the abstract planning fixture can represent and explicitly marks runtime-dependent properties as partial. It does not become production execution evidence.

## Executable topology proof

Adapter conformance loads every `EXECUTABLE_REFERENCE` record from the A0.1 portfolio, validates its committed snapshot and confirms that the committed semantic-plan artifact exists. It then regenerates the execution plan from the reference intent through the production intent → AST → validation → planning pipeline and evaluates that plan through `ExecutionTopologyMatchingAuthority` using the runtime profile derived from adapter evidence.

The proof passes only when:

- the committed snapshot remains honest and executable for the target;
- the committed semantic-plan artifact remains present;
- the plan can be regenerated through the canonical semantic pipeline;
- all plan topology requirements produce a `MATCHED` decision;
- every consumed adapter claim is `SUPPORTED`;
- every assessment evidence reference points to the adapter topology manifest.

This prevents an executable label from surviving on capability coverage, self-citing profiles or unsupported deserialization of an export artifact back into the internal plan model.

## Conformance and lifecycle

A0.2 adds five checks to the separate adapter inventory:

- lifecycle integrity;
- topology evidence integrity;
- runtime topology authority;
- profile-only demotion;
- executable topology proof.

These checks run after the frozen Core semantic closure check. A0.2 completion requires a passed exact-head and synthetic merge-candidate Flow CI boundary, followed by a separately validated completion-metadata boundary before A0.3 becomes active work.

## Non-goals

A0.2 does not:

- add or improve a renderer;
- add a runtime executor or storage implementation;
- change canonical topology semantics;
- create target-specific public Flow syntax;
- promote GitHub Actions, Tekton, Argo Workflows or Azure DevOps to executable-reference status;
- modify the frozen Core pre-closure inventory;
- change package, public standard or artifact contract versions.
