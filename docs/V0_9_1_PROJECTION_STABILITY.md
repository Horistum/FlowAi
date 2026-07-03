# v0.9.1 Jenkins/GitHub/Tekton Projection Stability

## Purpose

v0.9.1 adds smoke-level stability guards for the main supported target renderers: Jenkins, GitHub Actions and Tekton.

The previous v0.9.0 release defined a structural projection contract around `TargetManifest`. This release checks the next boundary: whether real generated manifests remain renderable into stable target artifacts without silently losing runtime inputs, runtime secrets or honest failure diagnostics.

## Scope

This release adds `ProjectionStabilitySmokeTests`.

The tests run a real Flow example through:

```text
Flow source -> parser -> planner -> target manifest -> renderer output
```

They verify for each supported target:

- deterministic renderer output
- expected target artifact structure
- runtime secret binding
- absence of green placebo commands such as `Flow executes ...`

## Target coverage

### Jenkins

The Jenkins smoke test checks for:

- `pipeline {` structure
- Flow input parameter projection
- environment binding
- Jenkins `credentials()` secret binding
- stage rendering
- shell step rendering

### GitHub Actions

The GitHub Actions smoke test checks for:

- `workflow_dispatch`
- jobs and runner structure
- environment binding
- GitHub `secrets.*` secret binding
- multiline `run` blocks

### Tekton

The Tekton smoke test checks for:

- `apiVersion: tekton.dev/v1`
- `kind: Pipeline`
- taskSpec rendering
- Kubernetes `secretKeyRef` secret binding
- script block rendering

## Boundary

The smoke tests do not replace conformance snapshots. They are intentionally higher-level guards that fail when a projection loses basic structural or honesty properties.

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

Package version: `0.9.1`

Active public standard version: `0.7.6`

Artifact versions remain unchanged:

- Intent: `1.0`
- AST: `1.0`
- ExecutionPlan: `1.1`
- TargetManifest: `1.0`
- TargetRegistry: `1.0`
