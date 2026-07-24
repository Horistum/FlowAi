# v0.9.7.9.5 Provider-Backed Approval and Topology Identity

## Purpose

Approval is semantic meaning until a target provider proves how that meaning is implemented. A capability declaration, a target support flag or a convenient renderer branch is not implementation evidence by itself.

Control and topology identities have the same honesty requirement. A readable slug may be used for presentation, but two distinct meanings must never disappear merely because punctuation or whitespace normalize to the same text.

## Approval evidence boundary

Intent, AST and ExecutionPlan continue to represent approval without target vocabulary. `ApprovalNode` expresses the required control and suspend/resume topology.

Native materialization is decided only at the target edge. A provider-backed approval definition must declare:

- the semantic capability, such as `approval.manual`
- an opaque renderer payload kind
- a renderer-owned reference
- an explicit provider evidence reference
- the semantic fields accepted as typed bindings

The provider catalog resolves these fields and validates the resulting manifest payload. Without that contract, approval is `ADAPTER_REQUIRED`; lowering does not label it native and wait for a later validator to expose the lie.

## Jenkins implementation

The built-in Jenkins provider owns one approval implementation:

- capability: `approval.manual`
- payload kind: `JENKINS_STEP`
- payload reference: `input`
- typed fields: `mode` and `message`

The renderer accepts only this exact provider-owned payload. It verifies that mode is `manual` and emits the Jenkins `input` step from the resolved message binding.

## GitHub Actions boundary

GitHub approval is normally expressed through protected environments attached to a job. That is a placement and topology concern, not a standalone step equivalent to Jenkins `input`.

This release deliberately does not declare a GitHub native approval-step payload. Job metadata and capability support therefore cannot manufacture native step evidence. GitHub Actions remains review-only for this control until a provider contract represents protected-environment placement, configuration evidence and dependency semantics explicitly.

## Monotonic compatibility and readiness

Capability support, planning compatibility, materialization readiness and renderer readiness are distinct evidence layers. Later evidence may confirm an earlier claim or make it stricter. It cannot remove an existing blocker.

A diagnostic authorization may therefore preserve:

```text
status = UNSUPPORTED
capabilityStatus = SUPPORTED
```

This means the target supports the individual capability, but the concrete plan is still blocked by planning, control, continuity or topology evidence. A complete provider payload does not change that verdict.

`TargetCompatibilityReadinessAnalyzer` now derives its result as the stricter of:

- the existing compatibility status
- capability support
- materialization readiness
- projection readiness

Executable readiness additionally requires no compatibility error. `TargetRenderPolicy` uses effective compatibility and explicit issues, not only `capabilityStatus`. The same blocker therefore survives diagnostic generation, reconciliation and later rendering.

## Collision-safe identity

Readable ids are retained when they are unique. For example, `release` remains:

```text
topology.workflowScope.release
```

When distinct original subjects collapse to one slug, such as `release api` and `release-api`, every colliding id receives a deterministic digest suffix:

```text
topology.workflowScope.release-api--<digest>
```

The digest uses SHA-256 over length-prefixed semantic components. It does not depend on list order and does not use lossy serialized concatenation.

### Control requirements

Control semantic identity includes:

- requirement kind
- original subject
- source authority
- condition
- message

Exact duplicates are rejected. Distinct obligations are retained even when their readable slugs collide.

### Topology requirements

Topology semantic identity includes kind and original subject. Canonical and planning observations of the same meaning coalesce deliberately, with canonical provenance retained because canonical requirements are supplied first. Distinct subjects never coalesce merely because their slugs match.

## Compatibility

Existing non-colliding ids remain unchanged. Digests appear only in actual collision groups. This keeps committed artifacts readable and stable while removing silent data loss.

## Non-goals

This work does not:

- add target vocabulary to canonical intent
- claim GitHub environment protection without provider evidence
- add runtime approval services
- add dynamic plugin discovery
- encode target commands or shell fragments
- weaken compatibility blockers after materialization
- change public standard or artifact contract versions

## Conformance

The conformance gate `planning.provider-backed-approval-topology-identity` verifies provider payload evidence and collision-safe control/topology identities through the standard test and conformance pipeline. Dedicated monotonicity tests verify that complete native payload evidence cannot erase an existing unsupported compatibility verdict.
