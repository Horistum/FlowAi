# v0.9.5.7.6 Release Metadata Reconciliation

## Purpose

This correction item makes Flow's version boundaries explicit after the architecture repair work introduced several nested roadmap identifiers.

The repository previously showed a published Gradle package at `0.9.4`, a next package line at `0.9.5` and active correction work identified as `0.9.5.7.x`, but did not describe those axes consistently in every release metadata source. That ambiguity could make an internal correction item look like a package or public-standard release.

## Version axes

### Published package

The Gradle package version remains:

```text
0.9.4
```

No package named `0.9.5.7.6` is published or claimed by this change.

### Unreleased correction track

The active architecture correction track is:

```text
v0.9.5.x
```

The scoped item implemented here is:

```text
0.9.5.7.6 Release Metadata Reconciliation
```

Correction identifiers order and bound internal work. They are not distribution versions.

### Public standard

The active public Flow standard remains:

```text
0.7.6
```

The correction does not advance exported public-standard contracts or declare a new standard version.

### Artifact contracts

The artifact contract versions remain unchanged:

- Intent `1.0`
- AST `1.0`
- ExecutionPlan `1.1`
- TargetManifest `1.0`
- TargetRegistry `1.0`

Existing conformance gate identifiers also remain stable.

## Metadata reconciliation

The correction aligns:

- `build.gradle.kts`
- `REPORT.md`
- `CHANGELOG.md`
- `.flow-agent/release-state.yaml`
- `.flow-agent/roadmap.yaml`
- `.flow-agent/roadmap-v0.9.5.7-repair-track.yaml`
- `docs/versioning-policy.md`
- the v0.9.5.7.6 Flow Agent report
- `VersionConsistencyTests`

The changelog now records the v0.9.5.x work as an unreleased architecture correction track instead of fabricating a sequence of published package releases.

## Analyzer correction

`CiCdBiasInventoryAnalyzer` previously embedded a list of future roadmap versions. That mixed repository evidence analysis with release scheduling.

The analyzer now derives stable follow-up areas from the evidence it actually finds:

- semantic model
- adapter boundary
- scenario and conformance
- documentation
- notes and target declarations

Roadmap versions remain in roadmap metadata, where they can be reordered without changing production analysis behavior.

## Release promotion rule

Package `0.9.5` may be promoted only through an explicit release decision that aligns package metadata, release evidence and the remaining correction boundary. Completing one nested correction item does not implicitly publish the umbrella package.

## Architecture boundary

This correction does not introduce:

- runtime execution
- an SDK surface
- a framework or plugin lifecycle
- shell or command projection
- a target-specific public Flow DSL
- renderer payload implementations
- Flow syntax expansion

The work changes metadata truthfulness and analyzer ownership only.
