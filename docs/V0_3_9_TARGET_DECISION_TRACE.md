# Flow v0.3.9 - Target Decision Trace

v0.3.9 keeps Flow on the main standardization path:

```text
Human / AI intent
  -> Standard Intent Model
  -> decision, safety and capability validation
  -> ExecutionPlan
  -> target capability negotiation
  -> execution readiness decision
  -> target selection report
  -> target decision trace
  -> target manifest / renderer
```

This release does not add syntax, runtime execution, SDK hooks or target templates. It adds an audit report over existing standard artifacts.

## Public artifact

`target-decision-trace-report.json` includes:

- `recommendedTarget`,
- `finalDecision`,
- `generationAllowed`,
- ordered `trace` steps,
- `targetExplanations`,
- cited `publicArtifacts`.

## Trace steps

The report links the target recommendation to:

1. `execution-plan.json`,
2. `capability-negotiation-report.json`,
3. `execution-readiness-report.json`,
4. `target-selection-report.json`.

## Why this belongs in Flow

Flow should not only recommend a target; it should make the recommendation auditable. A user or adapter should be able to see why a target was selected, why another target was only degraded, and why a blocked target must not be generated.
