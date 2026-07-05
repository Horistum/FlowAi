# v0.9.5.1 Shell Usage Inventory and Prohibition

## Purpose

v0.9.5.1 records the first correction inventory after the architecture recenter and applies the first concrete removal gates for shell and command-oriented projection.

Flow Core must not treat shell as the universal action model. It must preserve semantic intent first and require notes-driven materialization before any target-specific executable work is claimed.

## Rule

Shell is not a universal Flow action model.

A raw command string is not the default representation of executable automation.

Semantic-only output must not be rendered as successful target work.

## Completed corrections

### 1. TargetManifest action projection

Generated action steps now carry structured `TargetMaterialization` metadata.

`TaskNode.toTargetStep(...)` no longer calls a command generator and no longer treats command text as execution truth.

The legacy `TargetStep.run` field is still present as a compatibility field, but the manifest contract rejects it when populated. This keeps old accidental usage visible instead of silently accepting it.

### 2. Portable shell fallback removal

The old `portable-shell` default was replaced by the notes-driven projection model.

Flow Core no longer presents shell syntax as target-neutral portability.

### 3. Command generator removal

The manifest generator path no longer contains:

- `runCommandFor(...)`
- `PORTABLE_ACTIONS`
- shell quoting helpers
- standard semantic echo generation
- target-neutral command fallback text

Unsupported or unmaterialized work is represented as `ADAPTER_REQUIRED`, `SEMANTIC_ONLY` or `BLOCKED` materialization rather than fake executable work.

### 4. Renderer prohibition

Renderers no longer invent executable shell surfaces for generated Flow actions.

- Jenkins does not emit generated `sh(script: ...)` action steps.
- GitHub Actions does not emit generated `run: |` action blocks.
- Tekton does not emit generated `script: |`, `#!/bin/sh` or shell bodies.

Renderers surface materialization status and fail or delegate to a declared materialization boundary instead of pretending that unmapped work succeeded.

### 5. Intent lowering correction

Standard intent lowering no longer lowers BUILD, TEST, PACKAGE or low-level runtime requests to `shell.run`.

Those capabilities now lower to semantic `standard.execute` operations and remain semantic-only until a notes package declares how they are materialized for a concrete target.

VERIFY lowering no longer switches to shell execution when a runtime-text hint is present.

### 6. Standard catalog and contracts

Standard capability contracts and catalog entries no longer list shell as the default module for build, test, package, runbook or low-level runtime requests.

The catalog now describes those requests as semantic capabilities that require later materialization.

## Remaining follow-up areas

The following areas are intentionally left for later v0.9.5.x correction items:

- broader CI/CD bias around Docker, Kubernetes, Argo CD and registry assumptions
- scenario-pack deployment defaults such as Kubernetes pod verification
- notes package contracts for real target materialization
- eventual removal or migration of legacy compatibility fields once the manifest schema is deliberately advanced

These are not hidden as successful target work. They remain explicit architecture debt.

## Prohibition

New Flow Core changes must not add:

- shell or command-string projection as universal behavior
- echo-success placeholders reported as materialized work
- renderer-generated shell/script/run blocks for unmapped actions
- hardcoded concrete tools as universal default lowering

Any future compatibility path must be explicitly marked as legacy, degraded, semantic-only, adapter-required or blocked until notes-driven materialization exists.

## Versioning boundary

- Current package version remains `0.9.4`.
- Active public standard version remains `0.7.6`.
- This is roadmap correction work inside the v0.9.5.x correction track.
- No artifact schema version is changed.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change is introduced.
