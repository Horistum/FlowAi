# A1.0 GitHub Actions Artifact and Workspace Continuity

## Decision

A1.0 makes the canonical `checkout-build-image` plan executable on GitHub Actions by materializing the resolved Flow `WORKSPACE` relation as an explicit workflow-artifact transfer.

The implementation does not reinterpret `needs` as data movement. `needs` expresses job ordering only. Workspace continuity is provided by a producer-owned `actions/upload-artifact@v7` step and a consumer-owned `actions/download-artifact@v8` step using one deterministic artifact identity.

## Bounded support scope

The executable claim is deliberately limited to the complete ordered plan shape:

1. `git.checkout`
2. workspace channel `source`
3. `docker.build`

The scope authority compares the complete task-action sequence in addition to producer, consumer, semantic family and channel. A larger workflow, another channel, an unresolved producer, a `VALUE` relation or a `STATE` relation therefore remains unsupported.

The general target registry continues to declare `continuity.workspace` as unsupported for GitHub Actions. A plan-specific capability resolver creates an immutable effective target view only for the exact bounded plan. The authored registry object is never mutated.

## Execution boundary

The production projection sequence is:

```text
git.checkout
  -> actions/upload-artifact@v7
  -> needs ordering
  -> actions/download-artifact@v8
  -> docker/build-push-action@v7
```

The upload step:

- uploads the complete selected path from `.`;
- includes hidden files;
- fails when the producer workspace is empty;
- uses a deterministic identity derived from producer node and channel.

The download step restores the same artifact into `.` before the consumer action. Renderer payloads are typed and validated by the GitHub Actions native projection catalog before executable rendering.

## Transfer fidelity boundary

The current transfer uses the default archived form of `actions/upload-artifact@v7`. GitHub documents that zipped artifact upload does not preserve Unix file permissions: downloaded directories receive mode `755` and files receive mode `644`. Executable bits are therefore outside the certified continuity boundary.

The repository proof certifies:

- regular-file byte content;
- relative file paths;
- hidden entries included by the generated upload binding;
- one deterministic producer and consumer artifact identity.

The repository proof does not certify:

- Unix mode bits or executable permissions;
- symbolic-link identity or link-target semantics;
- a bit-identical POSIX filesystem reconstruction;
- Docker builds whose correctness depends on metadata not represented by regular-file bytes and paths.

The behavioral reconstruction test intentionally models the generated transfer contract with local file copies. It proves byte preservation for the committed regular-file fixture, but it is not evidence that the real GitHub artifact archive preserves filesystem metadata.

Any future scope expansion to executable scripts, symlink-sensitive build contexts or other metadata-dependent consumers requires a typed metadata-preserving transfer mechanism and new independent behavioral evidence. The existing scoped declaration must not be reused for that expansion.

## Fail-closed behavior

Normal materialization requires both:

- compatibility against the plan-specific effective capability profile;
- the adapter-owned continuity execution gate.

Diagnostic materialization does not throw away unsupported evidence. It emits an `ADAPTER_REQUIRED` review step and remains non-executable. Unsupported or ambiguous relations cannot be repaired by the renderer.

## Evidence boundaries

A1.0 keeps two reference boundaries separate:

- `conformance/snapshots/checkout-build-image` remains the historical Jenkins executable reference and is regenerated only as Jenkins evidence;
- `conformance/snapshots/github-actions-checkout-build-image` is the A1.0 GitHub Actions target-scoped executable reference.

This separation prevents adapter evolution from rewriting the frozen pre-A1 Jenkins and Core evidence boundary.

The post-A0 promotion is declared in `adapters/portfolio/executable-reference-promotions.yaml`. It overlays one bounded executable-reference claim without changing the historical A0.1 base portfolio record. The A1.0 conformance inventory independently validates lifecycle state, promotion integrity, production regeneration, fail-closed polarity and Jenkins preservation.

## Behavioral proof

Tests verify that:

- upload follows the producer action;
- download precedes the consumer action;
- producer and consumer use the same artifact identity;
- hidden entries are included by the generated binding;
- regular text and binary file bytes survive the bounded local reconstruction model;
- an empty producer workspace fails;
- another channel or an additional task does not receive scoped support;
- generic `VALUE` and `STATE` continuity are not promoted;
- Unix mode bits and symbolic-link identity are explicitly outside the certified boundary;
- Jenkins remains executable and unchanged;
- Tekton and unsupported GitHub Actions plans remain blocked.

## Explicit non-goals

A1.0 does not claim:

- generic GitHub Actions workspace continuity;
- generic artifact support for arbitrary producer and consumer actions;
- bit-identical filesystem continuity;
- Unix permission or symbolic-link preservation;
- value propagation;
- mutable or durable state propagation;
- equivalence between ordering and continuity;
- target-specific semantics inside Flow Core.
