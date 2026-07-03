# v0.9.3 Capability Degradation Semantics

## Purpose

v0.9.3 makes target degradation explicit after capability negotiation and manifest generation.

The analyzer works over `TargetManifest` outputs. It explains whether target behavior is supported, degraded or blocked, and records what is preserved, what is approximated, and what must be blocked or adapted before production use.

## Added model

This release adds:

- `TargetCapabilityDegradationAnalyzer`
- `TargetCapabilityDegradationReport`
- `TargetCapabilityDegradationEntry`
- `TargetCapabilityDegradationStatus`

## Status semantics

### Supported

A supported report means no degradation or blocking entries were found.

### Degraded

A degraded report means the manifest remains reviewable and may remain renderable, but at least one feature is approximate, partial, or requires target-specific review.

In standard mode, degraded reports are valid.

In strict mode, degraded reports are rejected before rendering.

### Blocked

A blocked report means at least one feature has no safe target-side preservation guarantee. Blocked reports are invalid in standard and strict modes.

## Explanation fields

Every degradation entry explains:

- `preserved`: what remains represented in the target artifact
- `approximated`: what is partial, approximate or review-required
- `blocked`: what is blocked in standard or strict mode
- `message`: the source diagnostic message

## Inputs

The analyzer uses information already present in `TargetManifest`:

- manifest mapping notes
- job mapping notes
- step mapping notes
- `supportLevel=partial` metadata on manifests, jobs and steps

It does not re-plan Flow and does not inspect or execute target output.

## Strict mode

`TargetCapabilityDegradationAnalyzer.requireAcceptable(manifest, strictMode = true)` rejects degraded and blocked semantics before rendering.

This is intentionally stricter than normal review mode. It is useful for production gates where approximate or partial target behavior is not acceptable.

## Boundary

This release does not add:

- runtime execution
- SDK API
- plugin lifecycle
- target-specific public DSL
- renderer expansion
- Flow syntax expansion
- public standard version bump
- artifact schema version bump

## Versioning

Package version: `0.9.3`

Active public standard version: `0.7.6`

Artifact versions remain unchanged:

- Intent: `1.0`
- AST: `1.0`
- ExecutionPlan: `1.1`
- TargetManifest: `1.0`
- TargetRegistry: `1.0`
