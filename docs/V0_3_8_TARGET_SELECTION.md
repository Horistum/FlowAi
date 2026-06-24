# Flow v0.3.8 - Target Selection

v0.3.8 keeps Flow on the main standardization path:

```text
Human / AI intent
  -> Standard Intent Model
  -> decision, safety and capability validation
  -> ExecutionPlan
  -> target capability negotiation
  -> execution readiness decision
  -> target selection report
  -> target manifest / renderer
```

This release does not add syntax, runtime execution, SDK hooks or target templates. It adds a public report that ranks registered targets from readiness and portability facts.

## Public artifact

`target-selection-report.json` includes:

- `recommendedTarget`,
- `readyTargets`,
- `degradedTargets`,
- `blockedTargets`,
- ranked `candidates`,
- per-candidate readiness, compatibility status, portability score, blocker count and warning count.

## Selection rule

Targets are ranked by:

1. readiness: `READY` before `DEGRADED` before `BLOCKED`,
2. production readiness,
3. target portability score,
4. target name for stable ordering.

## Why this belongs in Flow

Users should not need to know platform-specific gaps up front. Flow should evaluate a target registry, explain which targets can represent the plan safely, and make the recommended target explicit before any manifest is generated.
