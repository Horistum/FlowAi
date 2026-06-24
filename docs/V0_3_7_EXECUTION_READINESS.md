# Flow v0.3.7 - Execution Readiness

v0.3.7 keeps Flow on the main standardization path:

```text
Human / AI intent
  -> Standard Intent Model
  -> decision, safety and capability validation
  -> ExecutionPlan
  -> target capability negotiation
  -> execution readiness decision
  -> target manifest / renderer
```

This release does not add syntax, runtime execution, SDK hooks or target templates. It adds a public readiness decision between the ExecutionPlan and target manifest generation.

## Readiness states

- `READY`: the target can represent the plan without known semantic degradation.
- `DEGRADED`: the target can generate an artifact, but only with documented limitations or workarounds.
- `BLOCKED`: the target must not generate an artifact until blocking compatibility issues are resolved.

## Public artifact

`execution-readiness-report.json` includes:

- `generationAllowed`,
- `productionReady`,
- `compatibilityStatus`,
- `targetPortabilityScore`,
- `planPortabilityScore`,
- `blockers`,
- `warnings`,
- `requiredActions`.

## Why this belongs in Flow

Target renderers should not decide silently whether partial support is acceptable. Flow should make that decision visible before a Jenkinsfile, GitHub Actions workflow or Tekton Pipeline is generated.
