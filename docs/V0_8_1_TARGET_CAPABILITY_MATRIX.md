# v0.8.1 Target Capability Matrix

v0.8.1 introduces a stable target capability matrix report.

The matrix describes what supported targets can represent natively, partially, not at all, or only through runtime support. It is a descriptive standard artifact. It does not execute automation, add an SDK API, introduce a plugin lifecycle, or create target-specific public Flow syntax.

## Purpose

Flow must not assume that every target can represent every Flow execution-plan feature. The target capability matrix makes target support explicit before later negotiation and planner constraint work.

## Covered targets

The current required target set is:

- `jenkins`
- `github-actions`
- `tekton`

## Core capabilities

The matrix covers these core capability dimensions:

- `sequentialTasks`
- `parallel`
- `conditions`
- `dynamicLoops`
- `match`
- `retry`
- `approvals`
- `errorHandlers`
- `artifacts`
- `secrets`
- `nativeRuntime`

Target-specific feature flags remain represented separately as feature entries. That keeps the public matrix stable without pretending every platform has identical semantics.

## Support levels

The matrix uses the existing support levels:

- `SUPPORTED`
- `PARTIAL`
- `UNSUPPORTED`
- `REQUIRES_RUNTIME`

Unsupported and runtime-required capabilities are first-class matrix entries. They must not be hidden behind renderer fallback.

## Boundary

This release does not change Flow syntax, renderer behavior, runtime execution, SDK APIs, plugin lifecycle or target-specific public DSL behavior.
