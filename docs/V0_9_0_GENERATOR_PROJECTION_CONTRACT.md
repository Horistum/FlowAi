# v0.9.0 Generator Projection Contract

## Purpose

v0.9.0 defines the stable internal contract between the platform-neutral `ExecutionPlan` and target renderers.

The contract is intentionally centred on `TargetManifest`. A generator projects an `ExecutionPlan` into a `TargetManifest`; target renderers translate that manifest into Jenkins, GitHub Actions, Tekton or another supported platform. Renderers must not re-plan Flow, invent hidden semantics, hide unsupported work, or silently claim that a side effect happened when no target mapping exists.

## Boundary

This release adds a structural validation layer only.

It does not add:

- runtime execution
- SDK API
- plugin lifecycle
- target-specific public DSL
- renderer expansion
- Flow syntax expansion
- public standard version bump
- artifact schema version bump

## Contract rules

`TargetManifestContractValidator` checks these invariants:

- manifest, standard, target and flow identity fields are present
- required manifest metadata is present and internally consistent
- at least one projected job exists
- job ids and step ids are renderer-safe and unique within the manifest
- action steps carry module, action, target and a command or explicit failing diagnostic
- green placeholder action commands such as `Flow executes ...` are rejected
- mapping notes have a constrained shape with non-blank target, node id, feature and message

## Why this matters

Flow's architecture depends on a clear separation:

1. user/AI intent is lowered into Flow AST
2. Flow AST is validated and planned into `ExecutionPlan`
3. `ExecutionPlan` is projected into `TargetManifest`
4. target renderers serialize `TargetManifest` into platform syntax

If renderers can accept malformed manifests or silently substitute placeholder success commands, the project becomes another target-specific script generator with a polite hat. This contract keeps the target boundary auditable before v0.9.1 projection stability work.

## Validation

The release includes tests that:

- validate generated Jenkins, GitHub Actions and Tekton manifests against the projection contract
- reject malformed manifests before rendering
- reject invalid mapping notes
- reject duplicate projected step ids
- reject green placebo commands that claim Flow executed unsupported work

## Versioning

Package version: `0.9.0`

Active public standard version: `0.7.6`

Artifact versions remain unchanged:

- Intent: `1.0`
- AST: `1.0`
- ExecutionPlan: `1.1`
- TargetManifest: `1.0`
- TargetRegistry: `1.0`
