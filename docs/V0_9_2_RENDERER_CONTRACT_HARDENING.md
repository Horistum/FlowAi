# v0.9.2 Renderer Contract Hardening

## Purpose

v0.9.2 hardens the renderer boundary between `TargetManifest` and concrete target artifacts.

The v0.9.0 package line introduced the `TargetManifest` projection contract. The v0.9.1 package line added smoke-level renderer stability tests. v0.9.2 makes the renderer boundary stricter: Jenkins, GitHub Actions and Tekton renderers validate renderer input before serialization.

## Contract boundary

Target renderers are serialization boundaries. They translate a valid manifest into target syntax. They do not create new Flow semantics or reinterpret the original source.

## Added guard

`TargetRendererContractValidator` validates renderer inputs before rendering.

It checks:

- the manifest satisfies `TargetManifestContractValidator`
- the renderer target matches `TargetManifest.target`
- job dependencies reference known projected jobs
- step dependencies reference known projected jobs or steps
- structural container steps are not empty
- `try` steps carry a valid body and handler shape
- a step does not mix direct command text with child steps

## Renderer integration

The following renderers call the guard before producing output:

- `JenkinsManifestRenderer`
- `GitHubActionsManifestRenderer`
- `TektonManifestRenderer`

Invalid manifests fail with an explicit renderer-contract diagnostic before target output is produced.

## Validation

The release includes tests that prove:

- generated Jenkins, GitHub Actions and Tekton manifests remain renderable
- target mismatch is rejected before rendering
- unknown job dependencies are rejected before rendering
- manifest-contract violations are included in renderer diagnostics
- ambiguous run-plus-children step shapes are rejected

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

Package version: `0.9.2`

Active public standard version: `0.7.6`

Artifact versions remain unchanged:

- Intent: `1.0`
- AST: `1.0`
- ExecutionPlan: `1.1`
- TargetManifest: `1.0`
- TargetRegistry: `1.0`
