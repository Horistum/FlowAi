# v0.9.5.0 Recenter Report

## Summary

This document records the roadmap decision to re-center Flow before continuing toward end-to-end readiness.

The next implementation work is the `0.9.5.x` architecture correction track, starting with `0.9.5.0 Architecture Recenter - Notes-Driven Flow`.

## Recenter decision

Flow is a language and universal automation standardization model. Flow Core uses a universal decision, validation and generation engine over declarative notes packages.

Flow Core is not an SDK, framework, plugin lifecycle platform or CI/CD transpiler.

## Why the correction track exists

The current implementation contains architecture drift around:

- command-oriented projection
- hardcoded CI/CD target assumptions
- hardcoded concrete tool defaults
- semantic-only placeholder output
- registry entries that are not equivalent to implemented projection support
- missing notes package contracts for domains, runtime, targets, projections and conformance

These issues must be corrected before end-to-end readiness work.

## Versioning boundary

- Current package version remains `0.9.4`.
- The next umbrella package line remains `0.9.5`.
- The first scoped correction item is `0.9.5.0`.
- Active public standard version remains `0.7.6`.
- Artifact schema versions remain unchanged.

No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change is introduced by this roadmap recenter.
