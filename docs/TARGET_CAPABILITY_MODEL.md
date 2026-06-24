# Flow Target Capability Model v1.0

Flow must never assume that every target can represent every workflow feature.

Each target declares what it supports natively, partially, not at all, or only with a Flow runtime sidecar/wrapper.

## Support levels

- SUPPORTED
- PARTIAL
- UNSUPPORTED
- REQUIRES_RUNTIME

## Initial target features

- sequential tasks
- conditions
- dynamic loops
- parallel groups
- match/case branching
- retry
- approvals
- error handlers
- artifacts
- secrets
- native runtime behavior

## Initial targets

- local
- jenkins
- github-actions
- tekton
- argo-workflows
- azure-devops

## Example

```yaml
target: tekton
conditions: SUPPORTED
dynamicLoops: PARTIAL
parallel: SUPPORTED
approvals: UNSUPPORTED
retry: PARTIAL
artifacts: PARTIAL
secrets: SUPPORTED
```

## Why this matters

A Flow generator must not silently produce a broken or semantically weaker pipeline.

If a Flow contains approval and the target is Tekton, the compatibility report must explicitly say whether this is unsupported or requires an external gate.

## Implementation status

This version contains a first Kotlin implementation:

```text
org.flowlang.capabilities.TargetCapability
org.flowlang.capabilities.CompatibilityAnalyzer
org.flowlang.capabilities.CompatibilityReport
```

The CLI now prints compatibility reports for built-in targets after planning.

## Work in progress

- Capability checks are still feature-level, not module-action-level.
- Artifact and secret mapping are not deeply validated yet.
- Generator-specific downgrade rules are not implemented yet.
- Target-specific policy packs are not implemented yet.
