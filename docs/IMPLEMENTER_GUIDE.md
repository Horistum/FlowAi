# Implementer Guide

Introduced in v0.5.0. Updated in v0.5.1 with the public-candidate acceptance
gate, in v0.5.2 with standard export self-verification, in v0.5.3 with the
Standard Bundle Verifier and in v0.5.4 with the Conformance Vector Index.

This guide defines what an independent Flow implementation should read, produce
and verify without depending on Kotlin internals.

## Required implementation path

An implementation should preserve this public path:

```text
Human or AI intent
-> normalized intent
-> decision and safety validation
-> execution plan
-> target semantics and readiness
-> target manifest
-> conformance evidence
```

The implementation may use any programming language. The public contract is the
exported standard surface, schemas, conformance vectors and reference corpus.

## Required public artifacts

At the public standard candidate level, an implementation must understand:

- `public-standard-surface.json`
- `compatibility-migration-policy.json`
- `reference-intent-corpus.json`
- `target-semantics-matrix.json`
- `standard-export-bundle.json`
- `conformance-levels.json`
- `standard-export-manifest.json`

Implementations should use `standard-export-manifest.json` `acceptanceCriteria`,
`releaseGateChecks`, `evidenceArtifacts`, `selfVerificationCommands`,
`bundleVerificationChecks` and `verificationInputs` as the public acceptance and
self-verification checklist.

For v0.5.3, the same level also requires
`v0.5.3.standard-bundle-verifier`. Implementations should expose an equivalent
bundle verifier that checks public documents, JSON artifacts, schemas,
directories, evidence artifacts, release gates and the active standard version.

For v0.5.4, the same level also requires
`v0.5.4.data-driven-conformance-index`. Implementations should index public
conformance vectors and prove that required checks are not hidden only in
implementation code.

For v0.7.4, the standard-candidate level requires
`v0.7.3.standard-model-projection-coherence` and
`v0.7.4.architecture-delta-analyzer`. Implementations should treat
`StandardModel` as the single source for release profile, export manifest,
conformance levels, export bundle and public surface projections.

## Boundary

An implementation must not:

- execute workflows as part of the standard,
- define an SDK or plugin lifecycle,
- reinterpret intent inside target adapters,
- silently drop unsupported target semantics,
- depend on Kotlin implementation classes as public contracts.

## v0.7.5 purpose coverage ratio

For v0.7.5, implementations targeting the public standard candidate should treat `v0.7.5.purpose-coverage-ratio` as a behavioral quality gate. It does not require a runtime executor. It requires that public conformance evidence remains tied to reference automation scenarios, safety blocking cases and target portability boundaries.
