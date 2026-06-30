# v0.8.4 Planner Capability Constraints

## Purpose

v0.8.4 introduces a pre-projection planner capability constraint gate.

The gate verifies that a platform-neutral `ExecutionPlan` is compatible with a selected target before the plan is projected into a `TargetManifest` or rendered into vendor syntax.

## Problem

Before this package line, Flow had target capability analysis and manifest generators, but there was no small explicit boundary object that a caller could use as the pre-projection decision point.

That creates a risk:

1. The planner produces a valid platform-neutral plan.
2. A target cannot represent part of that plan.
3. A renderer still receives a compatibility report and may appear to render something useful.
4. Unsupported semantics can become a mapping note, a partial fallback, or worse, quietly misleading output.

Flow must not allow renderers to invent semantics.

## Design

The new `PlannerCapabilityConstraintGate` wraps the existing `CompatibilityAnalyzer` and exposes two operations:

- `analyze(plan, targetName, strict)` returns a `PlannerCapabilityConstraintReport`.
- `compatibilityForProjection(plan, targetName, strict)` returns a `CompatibilityReport` only when projection is allowed and throws when the selected target is blocked.

Callers must run this gate before invoking target manifest projection. The gate is intentionally explicit instead of being hidden inside a renderer, because renderers must not decide whether unsupported semantics are acceptable.

The gate classifies planner output as:

- `ALLOWED`: no blocking issues or warnings.
- `DEGRADED`: partial support exists, but projection is still allowed outside strict mode.
- `BLOCKED`: unsupported target semantics exist, or strict mode turns partial support into blocking issues.

## Boundary

The gate does not:

- rewrite the plan
- lower target-specific workarounds
- change Flow syntax
- introduce a runtime executor
- introduce SDK APIs
- introduce plugin lifecycle behavior
- introduce target-specific public DSL
- bump the public standard version
- bump artifact contract versions

## Examples

Supported Jenkins shell tasks are allowed:

```text
ExecutionPlan(task.execute) + jenkins -> ALLOWED
```

Tekton manual approval is blocked before rendering:

```text
ExecutionPlan(approval.manual) + tekton -> BLOCKED
```

GitHub Actions dynamic loops remain degraded outside strict mode:

```text
ExecutionPlan(loop.dynamic) + github-actions -> DEGRADED
```

The same plan is blocked in strict mode:

```text
ExecutionPlan(loop.dynamic) + github-actions + strict -> BLOCKED
```

## Version impact

Package version changes to `0.8.4`.

No public or artifact contract version is bumped:

```text
Flow public standard: 0.7.6
Intent: 1.0
AST: 1.0
ExecutionPlan: 1.1
TargetManifest: 1.0
TargetRegistry: 1.0
```
