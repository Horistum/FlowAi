# Flow Versioning Policy

Flow uses multiple version axes. They are intentionally separate so implementation and correction work can continue without accidentally publishing a new package, public standard or artifact contract.

## Version axes

### Package version

The package version is the implementation and distribution line. It is the value assigned to `version` in `build.gradle.kts`.

The currently published package version is:

```text
0.9.4
```

Package versions may change for implementation, documentation, release metadata, report, test or internal quality work, but only through an explicit package promotion. A roadmap item does not change the package version by existing.

### Roadmap correction scope

Roadmap correction identifiers describe bounded internal work. They are not package versions.

The active correction track is:

```text
v0.9.5.x
```

The current scoped correction item is:

```text
0.9.5.7.6
```

This identifier means "the Release Metadata Reconciliation item inside the v0.9.5.x correction track." It does not mean that Gradle, a published archive, the public Flow standard or any artifact schema has version `0.9.5.7.6`.

The correction track remains unreleased. The published package stays `0.9.4` until package `0.9.5` is deliberately promoted with aligned metadata and validated release evidence.

### Public Flow standard version

The public Flow standard version is `FlowStandardVersions.FLOW_STANDARD_VERSION`.

It must not change merely because the package version or roadmap correction scope changes. It changes only when the public standard contract, exported standard surface, rendered snapshots and conformance expectations are intentionally advanced together.

Current active public standard version:

```text
0.7.6
```

### Artifact contract versions

Artifact contract versions are independent from the package version and correction scope. They are versioned in `FlowStandardVersions`:

```text
Intent: 1.0
AST: 1.0
ExecutionPlan: 1.1
TargetManifest: 1.0
TargetRegistry: 1.0
```

These values change only when the corresponding artifact contract changes. They must not be bumped for release metadata cleanup, documentation cleanup, report generation, correction-track scheduling or package-only fixes.

### Conformance gate identifiers

Conformance gate identifiers are stable contract names. Existing gate identifiers must not be renamed for cosmetic version alignment. A new gate may be added when a new behavior or invariant is introduced, but old identifiers remain stable unless an explicit compatibility migration exists.

## Required release metadata

Every published package release must keep these files aligned:

- `build.gradle.kts`
- `REPORT.md`
- `CHANGELOG.md`
- `.flow-agent/release-state.yaml`
- `.flow-agent/roadmap.yaml`
- `.flow-agent/reports/<version>-*.md`

Every active correction item must additionally keep these files aligned:

- `.flow-agent/roadmap-v0.9.5.7-repair-track.yaml`
- the correction-item report in `.flow-agent/reports/`
- documentation that states whether the package line is promoted or remains unchanged

## Release PR declaration

Every release or correction PR must state:

- published package version impact
- roadmap correction scope
- active public standard version impact
- artifact version impact
- conformance impact
- architecture boundary

## Current package-line policy decision

The published package remains `0.9.4`, which introduced the reference scenario matrix before end-to-end readiness work.

The unreleased v0.9.5.x correction track repairs architecture and metadata before package `0.9.5` can be claimed. Correction item `0.9.5.7.6` therefore does not bump:

- Gradle package version
- public Flow standard version
- Intent version
- AST version
- ExecutionPlan version
- TargetManifest version
- TargetRegistry version
- schema version paths

It also does not introduce:

- runtime execution
- SDK API
- framework or plugin lifecycle
- shell projection
- target-specific public DSL
- renderer payload expansion
- Flow syntax expansion

## Analyzer boundary

Production analyzers report current repository evidence and stable architectural follow-up areas. They must not contain lists of future roadmap versions. Roadmap scheduling belongs in roadmap metadata, where it can change without changing production analysis behavior.

## Historical package-line policy decisions

- v0.9.4 added a target-neutral reference scenario matrix.
- v0.9.3 added capability degradation semantics over TargetManifest outputs.
- v0.9.2 added renderer contract hardening before Jenkins, GitHub Actions or Tekton serialization.
- v0.9.1 added smoke-level projection stability guards for Jenkins, GitHub Actions and Tekton renderers.
- v0.9.0 added a generator projection contract over TargetManifest artifacts.
- v0.8.7 added safety-boundary result handler coverage.
- v0.8.6 added review hardening fixes around manifest honesty, runtime secrets, rollback negation and concrete output validation.
- v0.8.5 added safety boundary hardening as a pre-projection validation gate over Flow AST and module contracts.
- v0.8.4 added planner capability constraints as a pre-projection gate over existing target compatibility analysis.
- v0.8.3 aligned package and release metadata only.
