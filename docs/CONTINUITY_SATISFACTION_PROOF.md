# A0.5 Continuity Satisfaction Proof

## Status

A0.5 is the adapter-stream proof that a selected provider can satisfy the continuity already required by the frozen Core execution plan.

It does not add a new dependency kind, reinterpret ordering, or infer transfer from target layout.

## Boundary

Core already distinguishes:

- `ORDERING`: execution order only;
- `VALUE`: a produced value is consumed later;
- `WORKSPACE`: producer and consumer require one workspace path;
- `STATE`: mutable state must be transferred and remain durable for the required lifetime.

A0.5 maps those existing relations to adapter evidence:

| Core relation | Adapter proof family | Closed semantic |
|---|---|---|
| `ORDERING` | none | none |
| `VALUE` | `DATA` | `data.value` |
| `WORKSPACE` | `ARTIFACT` | `artifact.shared-workspace` |
| `STATE` | `MUTABLE_STATE` and `DURABLE_STATE` | `state.mutable.workflow`, `state.durable.workflow` |

A state relation is satisfied only when both transfer and lifetime evidence are satisfied.

## Evidence rules

Every target owns exactly one claim for each family. Each family semantic is classified as exactly one of:

- supported;
- unsupported;
- unknown.

Supported evidence requires:

1. a composed provider;
2. repository production implementation evidence;
3. independent behavioral test evidence;
4. no self-reference to the continuity manifest or target registry;
5. replayable producer-to-consumer behavior for every executable reference claim.

Profile-only targets and semantic references remain `UNKNOWN` until a provider exists.

## Current evidence at A0.5 closure

The only supported continuity subset at the A0.5 closure boundary was Jenkins shared-workspace artifact continuity for the admitted `checkout-build-image` reference scenario.

The proof was bounded:

- the plan contains one resolved `WORKSPACE` relation from checkout to image build;
- the Jenkins generator emits both native leaves into one target job;
- the Jenkins renderer emits sequential stages under one pipeline agent;
- the committed executable snapshot is regenerated through the canonical production planner;
- unit and conformance tests replay the complete path.

The following were unsupported at that historical boundary:

- generic Jenkins value propagation;
- generic mutable or durable state continuity;
- GitHub Actions data, workspace and state continuity in the job-per-task projection;
- Tekton data, workspace and state continuity without complete Pipeline-level bindings.

Argo Workflows, Azure DevOps and the local semantic reference remained unknown because this distribution composed no provider for them.

## Post-A0 scoped evolution

A1.0 later added one bounded GitHub Actions exception for the exact `git.checkout` to `docker.build` `source` workspace path. That exception emits an explicit `actions/upload-artifact@v7` and `actions/download-artifact@v8` pair through adapter-owned scoped support.

This later evidence does not rewrite the A0.5 closure result and does not promote generic GitHub Actions workspace continuity. The generic claim remains `UNSUPPORTED`; only the exact scoped declaration may produce executable evidence.

The scoped transfer proves regular-file bytes, relative paths and hidden-entry inclusion for the committed reference fixture. Unix mode bits are not preserved by the zipped artifact mechanism, and symbolic-link identity is not certified. Those filesystem metadata properties remain outside the scoped evidence boundary.

## CLI behavior

`CliTargetEvidenceAuthority` requires adapter continuity before executable rendering.

When continuity is unsupported or unknown:

1. executable provider invocation is rejected;
2. diagnostic target-manifest evidence is generated;
3. continuity findings are reconciled into compatibility and mapping notes;
4. readiness and selection remain blocked or review-only;
5. no target syntax is emitted.

Diagnostic fallback preserves the blocker. It does not authorize execution.

## Lifecycle

During implementation:

- the A0.5 work package is `active`;
- A0.5 remains the selected `next` roadmap item;
- A0.6 remains `planned`;
- implementation evidence must be absent.

Completion requires one passing Flow CI boundary with distinct exact-head and synthetic merge-candidate revisions. Only after that boundary may A0.5 become completed and A0.6 become next.
