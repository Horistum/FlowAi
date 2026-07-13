# v0.9.5.7.5 Compatibility and Readiness Honesty

## Purpose

Capability compatibility is not executable readiness.

A target can declare that it understands a capability while a concrete Flow still lacks materialization evidence, target renderer payloads, or both. Before this repair, capability-only `SUPPORTED` could remain visible on a review-only artifact and capability negotiation could recommend a target without checking whether the generated manifest was executable.

v0.9.5.7.5 introduces a reconciliation layer over concrete target manifests.

## Readiness dimensions

The compatibility readiness report keeps four facts separate:

- capability compatibility: the platform-level support declaration
- materialization readiness: whether required semantic leaves crossed the materialization boundary
- projection readiness: whether the target renderer has complete payload evidence
- effective compatibility: the honest status for the concrete artifact

The model does not rewrite capability declarations. It preserves them as audit evidence and derives an effective status from the complete manifest state.

## Effective status rules

- blocked or unsupported materialization produces effective `UNSUPPORTED`
- fail-fast projection produces effective `UNSUPPORTED`
- incomplete materialization or review-only projection produces effective `PARTIAL`
- capability-level partial or runtime-required support remains degraded even when renderer payload evidence exists
- effective `SUPPORTED` requires supported capability declarations, complete materialization and executable projection readiness

## Target recommendation

Capability negotiation may still describe preliminary platform compatibility before manifests exist.

A reconciled negotiation report is the authoritative recommendation surface. A target is recommendation-eligible only when:

- concrete manifest evidence is available
- effective compatibility is `SUPPORTED`
- projection readiness is `EXECUTABLE`

Review-only, fail-fast and unevaluated targets cannot remain recommended.

## Manifest behavior

The safe manifest projection entry point now reconciles compatibility before returning a canonical manifest.

The manifest retains:

- `capabilityCompatibility`
- `effectiveCompatibility`
- `materializationReadiness`
- `projectionReadiness`
- `executable`

The public manifest compatibility status is the effective status, not the preliminary capability-only status.

## Review artifact behavior

`TargetProjectionReview` artifacts expose both capability and effective compatibility. They no longer emit a single `compatibility: SUPPORTED` field for unresolved work.

A review artifact can therefore state that a target is capability-compatible while also making it explicit that the concrete Flow is only review-ready and not executable.

## Architecture boundary

This repair does not add:

- runtime execution
- an SDK API
- a framework or plugin lifecycle
- a shell generator
- command projection
- renderer payload implementations
- target-specific public Flow syntax
- a package, public standard or artifact schema version bump

## Next step

v0.9.5.7.6 reconciles package, changelog, report and correction-track release metadata after the architecture repair sequence has a truthful readiness boundary.
