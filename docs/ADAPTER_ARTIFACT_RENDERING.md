# A0.6 Adapter Artifact Rendering

## Purpose

A0.6 owns the final adapter edge from an already reconciled `TargetManifest` to one concrete artifact outcome:

1. executable target syntax;
2. non-executable Flow review evidence; or
3. fail-fast with no artifact.

Rendering is not planning. It cannot add capability meaning, repair topology, invent control enforcement, substitute ordering for continuity or reinterpret an unsupported adapter decision.

## Authority chain

The production order is:

```text
Intent
  -> canonical AST
  -> ExecutionPlan
  -> explicit target selection
  -> capability and topology authorization
  -> adapter control proof
  -> adapter continuity proof
  -> TargetManifest
  -> TargetRenderPolicy
  -> AdapterArtifactRenderingAuthority
  -> artifact + target-artifact-evidence.json
```

`TargetProjectionProvider.render()` is now reserved for executable target syntax. It rejects every non-executable manifest before a target renderer can emit content under a provider-owned file name.

## Artifact classes

### Executable target

An executable artifact requires all of the following:

- `TargetRenderPolicy` returns `EXECUTABLE`;
- the adapter rendering evidence record is `SUPPORTED`;
- a composed provider owns the exact executable file name;
- the provider renderer accepts the manifest;
- the resulting content is non-empty;
- the rendering receipt validates against the exact content and manifest.

Examples of provider-owned executable identities are `Jenkinsfile` and `github-actions.yml`.

### Review evidence

A review artifact is a generic `TargetProjectionReview` document. It always declares:

```yaml
kind: TargetProjectionReview
renderMode: REVIEW_ONLY
executable: false
```

It uses a dedicated Flow-owned file identity such as `flow-jenkins-review.yaml`. A review document never reuses `Jenkinsfile`, `github-actions.yml` or `tekton-pipeline.yaml`.

Review evidence can be produced when:

- the manifest is review-only; or
- a composed provider is certified only for review rendering.

It preserves findings and semantic inventory without pretending that target syntax is deployable.

### Fail-fast

`FAIL_FAST` produces no primary artifact and no receipt. Diagnostic manifest evidence remains available through the earlier CLI evidence path.

## Rendering evidence receipt

Every produced executable or review artifact is paired with `target-artifact-evidence.json`.

The receipt binds:

- target and flow identity;
- artifact kind and render mode;
- exact artifact file name and media type;
- SHA-256 of the exact artifact bytes;
- SHA-256 of the exact source manifest;
- standard and manifest versions;
- compatibility status and issues;
- mapping notes;
- inputs and triggers;
- jobs, dependencies and nested materialization decisions;
- renderer payloads and every typed binding;
- adapter control and continuity metadata preserved on the manifest;
- render-readiness findings;
- renderer implementation evidence and declared limitations.

Evidence identities are deterministic, collision-resistant and unique. Reordered or duplicated claims cannot silently replace another claim.

## Current built-in boundary

| Target | Rendering evidence | Executable file | Review file |
|---|---|---|---|
| Jenkins | supported | `Jenkinsfile` | `flow-jenkins-review.yaml` |
| GitHub Actions | supported | `github-actions.yml` | `flow-github-actions-review.yaml` |
| Tekton | review-only | `tekton-pipeline.yaml` reserved | `flow-tekton-review.yaml` |
| Local | unknown | none | `flow-local-review.yaml` |
| Argo Workflows | unknown | none | `flow-argo-workflows-review.yaml` |
| Azure DevOps | unknown | none | `flow-azure-devops-review.yaml` |

Renderer support is not scenario executability. A GitHub Actions leaf renderer may be supported while a multi-task scenario remains blocked by topology or continuity. A0.6 consumes those earlier decisions rather than bypassing them.

## CLI behavior

`--render` now means “produce the authorized adapter artifact class.”

- executable evidence produces target syntax and exit status `0`;
- review-only evidence produces a dedicated review artifact, retains `CLI_RENDER_NOT_AUTHORIZED` for executable syntax and returns review-required status;
- fail-fast evidence produces no adapter artifact and returns blocked status.

The CLI artifact model distinguishes `RENDERED_TARGET`, `REVIEW_DOCUMENT` and `DIAGNOSTIC_EVIDENCE`. The receipt is always diagnostic evidence and is written beside the primary artifact.

## Non-goals

A0.6 does not:

- add trigger materialization coverage;
- add a runtime executor;
- create a plugin lifecycle;
- promote profile-only targets;
- accept platform documentation as implementation proof;
- change package, public standard or artifact contract versions;
- modify the frozen Core pre-closure inventory.

Trigger materialization remains A0.7.
