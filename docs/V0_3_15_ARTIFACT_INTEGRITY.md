# Flow v0.3.15 - Artifact Integrity Report

v0.3.15 adds a public integrity report for the exported Flow artifact set.

The goal is to make the standard output bundle safer for downstream tools. A consumer should not have to guess whether required artifacts are present, whether diagnostic coverage passed, or whether exported reports agree on the Flow standard version.

## Public artifact

The new artifact is:

```text
artifact-integrity-report.json
```

It is validated by:

```text
schemas/artifact-integrity-report.schema.json
```

The report contains:

- `requiredArtifactsExpected`,
- `requiredArtifactsPresent`,
- `missingRequiredArtifacts`,
- `artifactsWithoutSchema`,
- `standardVersionObservations`,
- `standardVersionMismatches`,
- `diagnosticCoverageStatus`,
- `issues`,
- `status`.

`status` is:

- `PASS` when there are no blocking integrity errors,
- `FAIL` when a required artifact is missing, a standard version mismatch exists, or diagnostic coverage failed.

Schema gaps are reported as warnings so the project can improve the public contract incrementally without breaking existing artifact families immediately.

## Why this belongs in core

Flow's main boundary is:

```text
Human / AI intent
-> standard intent model
-> validation and safety reports
-> execution plan
-> target adapter contract
-> public artifact set
```

The artifact set is the interface consumed by CI, adapters, reviewers and AI agents. Its integrity is therefore part of the standard surface, not an adapter feature.

## Boundary

This version does not add:

- new Flow syntax,
- new scenario packs,
- target renderer behavior,
- runtime execution,
- SDK/plugin behavior.

It only verifies that the public standard outputs are internally consistent.
