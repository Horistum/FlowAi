# v0.9.7.9.7 CLI Diagnostic and Release Honesty

## Purpose

This bounded correction closes the user-facing diagnostic and release-publication boundary of `v0.9.7.9`.

The work does not add a runtime, scheduler, SDK, plugin lifecycle, shell command transport or target-specific public Flow syntax. It makes existing CLI and release claims reproducible from the evidence that actually exists.

## Problem statement

The previous public CLI mixed four different facts:

1. a target manifest existed;
2. capability compatibility was preliminary;
3. concrete renderer evidence might be incomplete;
4. a renderer implementation was available in the process.

Those facts were displayed and exported as if they formed one executable decision. In particular:

- target syntax could be rendered without an explicit `--render` request;
- preliminary readiness, negotiation and selection reports were written beside a concrete manifest;
- headings described a manifest as `READY` without executable evidence;
- adapter diagnostics could remain capability-only after manifest reconciliation;
- standard draft construction fabricated an `ADAPTER_CONTRACT_READY` observation;
- standard export wrote directly to its destination before final conformance and bundle verification;
- release compliance treated missing conformance evidence as non-failing;
- release metadata cited stale candidate evidence and mixed package, public-standard and bounded-work versions;
- runtime diagnostics implied that Flow Core could supply runtime support.

## Evidence hierarchy

### Manifest evidence

`target-manifest.json` proves that Flow produced a structurally validated target projection model. It does not prove that the model is executable.

### Render readiness

`TargetRenderPolicy` classifies the exact manifest as:

- `EXECUTABLE`
- `REVIEW_ONLY`
- `FAIL_FAST`

Only `EXECUTABLE` authorizes target syntax emission.

### Render request

A renderer is never invoked merely because it is registered. The CLI invokes a target provider only when both conditions hold:

1. the user explicitly supplied `--render`;
2. `TargetRenderPolicy.requireExecutable(manifest)` succeeds.

Without `--render`, the CLI emits manifest and render-readiness evidence only.

## One coherent CLI evidence set

`CliTargetEvidenceAuthority` builds one target evidence set from one execution plan and one exact manifest.

The authority:

1. computes preliminary compatibility, negotiation, readiness and selection;
2. generates the target manifest through the supplied projection registry;
3. reconciles readiness, negotiation and selection against that manifest;
4. builds the decision trace from the reconciled reports;
5. reconciles the adapter contract and diagnostics against concrete readiness;
6. evaluates render readiness;
7. optionally renders only after executable authorization.

CLI artifacts therefore no longer combine preliminary and concrete models.

## Public CLI wording

The public application entrypoint is:

```text
org.flowlang.cli.honest.HonestFlowCliKt
```

Target output uses explicit headings:

- `RECONCILED TARGET COMPATIBILITY REPORT`
- `RECONCILED TARGET CAPABILITY NEGOTIATION REPORT`
- `RECONCILED EXECUTION READINESS REPORT`
- `RECONCILED TARGET SELECTION REPORT`
- `RECONCILED TARGET DECISION TRACE REPORT`
- `TARGET MANIFEST EVIDENCE`
- `TARGET RENDER READINESS`
- `TARGET OUTPUT NOT RENDERED`
- `RENDERED EXECUTABLE TARGET OUTPUT`

The phrase `TARGET MANIFEST READY` is not used because manifest existence and executable readiness are distinct properties.

## Artifact export honesty

Per-flow CLI export now declares the artifacts that the command actually writes.

It does not append unrelated standard-release closure reports to every intent conversion. The exported bundle and artifact-integrity report are derived from the concrete output set, including a rendered target artifact only when rendering was explicitly requested and authorized.

## Release compliance

`StandardComplianceAnalyzer` requires an explicit `PASS` conformance manifest.

These states are failures:

- conformance manifest absent;
- conformance manifest present with `FAIL`;
- artifact integrity failed;
- required evidence missing;
- release contract gates incomplete.

The absence of failure evidence is not success evidence.

## Standard release assembly

`StandardReleaseAssemblyAuthority` assembles one release artifact graph from:

- a passing `ConformanceRunner` result;
- a passing conformance manifest;
- a consistent conformance-vector index;
- release metadata honesty;
- diagnostic coverage;
- artifact integrity;
- explicit artifact provenance;
- standard contract index;
- artifact evidence;
- standard compliance;
- standard freeze;
- public index, conformance suite and draft artifacts.

No synthetic adapter-ready diagnostic is used.

Every required JSON artifact declares a schema path, and every declared schema path must resolve to an existing repository file before assembly succeeds.

## Atomic publication

`standard-export` uses a sibling staging directory.

The process is:

1. assemble validated release artifacts;
2. write them to staging;
3. copy public standard directories to staging;
4. run `StandardBundleVerifier` against staging;
5. write the verification report;
6. require verification `PASS`;
7. replace the destination directory;
8. restore the previous destination if replacement fails.

A failed candidate does not leave a partially published destination.

`standard-draft` also assembles its evidence before replacing its destination, but it does not claim full exported-bundle verification because the draft output intentionally excludes copied standard directories.

## Release metadata axes

The following version axes remain independent:

| Axis | Current value |
|---|---:|
| Published implementation package | `0.9.5` |
| Next implementation package | `0.9.6` |
| Active public Flow standard | `0.8.0` |
| Active bounded correction item | `0.9.7.9.7` |
| Next Core item | `0.9.7.10` |

`0.9.7.9.7` is not a package or public-standard version.

`ReleaseMetadataHonestyAuthority` checks Gradle, release state, roadmap state, `REPORT.md`, the bounded correction changelog and already-merged validation evidence.

Candidate validation is not written into the candidate commit. The exact candidate CI result remains external pull-request evidence until the commit actually passes.

## Runtime wording

The historical `REQUIRES_RUNTIME` enum value remains for contract stability. Diagnostics now define it precisely:

- the target requires external target-side runtime or adapter capability;
- Flow Core does not provide that runtime;
- the requirement is degraded or blocked evidence, never hidden implementation support.

## Validation coverage

Unit and conformance coverage proves:

- no renderer invocation without `--render`;
- no rendered file in a non-render export;
- review-only rendering fails before output directory publication;
- CLI reports carry concrete manifest evidence;
- adapter diagnostics match reconciled readiness;
- missing conformance evidence fails release compliance;
- package, standard, roadmap, report and correction-ledger metadata agree;
- release assembly contains no synthetic adapter-ready diagnostic;
- staged verification precedes exported-bundle publication;
- legacy Flow-runtime ownership wording is absent from release-facing diagnostics.

The standard conformance check is:

```text
cli.release.diagnostic-honesty
```

## Architecture boundary

This correction adds validation and publication discipline. It does not add:

- workflow execution;
- task execution;
- a Flow runtime;
- an SDK or base-class API;
- plugin discovery or lifecycle;
- target command transport;
- shell projection;
- automatic deployment;
- implicit rendering;
- a package, public-standard or existing artifact-contract version bump.
