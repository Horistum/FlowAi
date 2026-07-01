# v0.8.5 Safety Boundary Hardening

## Purpose

v0.8.5 adds a pre-projection safety boundary gate over Flow AST and module contracts.

The goal is to block high-risk semantics before target rendering. The gate does not execute work, does not rewrite plans, and does not add target-specific public syntax.

## Scope

This package adds `SafetyBoundaryValidator` and wires it into `FlowValidator`.

The gate checks:

- production-sensitive mutating actions require `safety: requiresApproval`
- contract-marked high-risk actions require `safety: requiresApproval`
- rollback-sensitive standard actions require `safety: requiresApproval`
- weaker conditional safety is not enough when the operation requires explicit approval

## Boundary

The v0.8.5 gate is intentionally conservative:

- it does not add a runtime executor
- it does not add an SDK API
- it does not add a plugin lifecycle
- it does not add a target-specific public DSL
- it does not change Flow syntax
- it does not bump the public standard version
- it does not bump artifact contract versions

## Validation

`SafetyBoundaryHardeningTests` covers:

- production namespace mutation without approval is rejected
- production namespace mutation with approval is accepted
- a high-risk action with `onlyIf` but without explicit approval is rejected
- a high-risk action with explicit approval is accepted
- rollback-sensitive actions require approval

## Version matrix

| Version axis | Value | Changed in v0.8.5 |
| --- | --- | --- |
| Package version | `0.8.5` | yes |
| Public Flow standard version | `0.7.6` | no |
| Intent artifact version | `1.0` | no |
| AST artifact version | `1.0` | no |
| ExecutionPlan artifact version | `1.1` | no |
| TargetManifest artifact version | `1.0` | no |
| TargetRegistry artifact version | `1.0` | no |
