# v0.8.2 Target Negotiation Report

v0.8.2 adds a target negotiation explanation report on top of the existing compatibility negotiation model.

The report explains why a target is supported, degraded or blocked for a given execution plan. It does not add runtime execution, SDK APIs, plugin lifecycle, renderer behavior, target-specific public Flow syntax or new Flow syntax.

## Purpose

Compatibility data is only useful when users can understand the result. A target can be usable, usable with degradation, or blocked. v0.8.2 makes those outcomes explicit and auditable.

## Outcomes

The report uses three outcomes:

- `SUPPORTED`: all required capabilities are supported by the target.
- `DEGRADED`: one or more required capabilities are partial or require runtime support.
- `BLOCKED`: one or more required capabilities are unsupported by the target.

## Rejection reasons

Blocked and runtime-required capabilities are reported as explicit rejection reasons. Each reason includes:

- target
- capability
- support level
- explanation message

## Workarounds

Partial and runtime-required capabilities can produce workaround recommendations from the lower-level negotiation model. These recommendations are explanatory. They do not authorize silent renderer fallback.

## Boundary

This release is reporting-only. It does not change planning, rendering, target manifests, runtime behavior or public Flow syntax.
