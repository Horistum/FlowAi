# Flow 0.9.5 Contract Migration

## Version map

| Axis | Previous | Current |
|---|---:|---:|
| Package | 0.9.4 | 0.9.5 |
| Public Flow standard | 0.7.6 | 0.8.0 |
| Intent | 1.0 | 2.0 |
| AST | 1.0 | 2.0 |
| ExecutionPlan | 1.1 | 2.0 |
| TargetManifest | 1.0 | 2.0 |
| TargetRegistry | 1.0 | 2.0 |

## Intent 1.x to 2.0

Schedules are removed from workflow-step capability space. Replace a `SCHEDULE` step with a top-level trigger:

```yaml
intentVersion: "2.0"
triggers:
  - id: renew-every-30-days
    type: SCHEDULE
    workflows: [main]
    schedule:
      kind: INTERVAL
      expression: P30D
```

The loader rejects the removed `SCHEDULE` step with an explicit migration diagnostic. It also rejects unsupported intent contract versions instead of silently interpreting them as 2.0.

## AST and ExecutionPlan 2.0

`FlowNode` and `ExecutionPlan` now contain top-level trigger collections. Trigger identity, type, workflow bindings, schedule kind/expression/timezone, event and parameters are preserved across lowering.

## TargetManifest 2.0

The legacy command-oriented `run` field is removed. Native actions use `rendererPayload` with a target-specific payload kind, native reference, structured parameters and evidence reference. The manifest also carries triggers and reconciled compatibility/readiness evidence.

## TargetRegistry 2.0

Targets may declare `projectionRules`. Rules are validated for unique module/action keys, evidence, supported payload kind and mode/payload consistency. A new native path therefore changes data, not a core target switch.

## Target semantics matrix 2.0

Vendor fields are replaced by a target-id map. Consumers must read `semanticsByTarget[targetId]`.
