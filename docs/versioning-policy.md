# Flow Versioning Policy

Flow uses multiple version axes. They are intentionally separate so package work can continue without accidentally publishing a new public standard or changing artifact contracts.

## Version axes

### Package version

The package version is the implementation and distribution line. It is the value in `build.gradle.kts`.

Examples:

- `0.8.1` target capability matrix
- `0.8.2` target negotiation report
- `0.8.3` package version and release integrity
- `0.8.4` planner capability constraints

Package versions may change for implementation, documentation, release metadata, report, test or internal quality work.

### Public Flow standard version

The public Flow standard version is `FlowStandardVersions.FLOW_STANDARD_VERSION`.

It must not change merely because the package version changes. It changes only when the public standard contract, exported standard surface, rendered snapshots and conformance expectations are intentionally advanced together.

Current active public standard version:

```text
0.7.6
```

### Artifact contract versions

Artifact contract versions are independent from the package version. They are versioned in `FlowStandardVersions`:

```text
Intent: 1.0
AST: 1.0
ExecutionPlan: 1.1
TargetManifest: 1.0
TargetRegistry: 1.0
```

These values change only when the corresponding artifact contract changes. They must not be bumped for release metadata cleanup, documentation cleanup, report generation or package-only fixes.

### Conformance gate identifiers

Conformance gate identifiers are stable contract names. Existing gate identifiers must not be renamed for cosmetic version alignment. A new gate may be added when a new behavior or invariant is introduced, but old identifiers remain stable unless an explicit compatibility migration exists.

## Required release metadata

Every package release must keep these files aligned:

- `build.gradle.kts`
- `REPORT.md`
- `CHANGELOG.md`
- `.flow-agent/release-state.yaml`
- `.flow-agent/roadmap.yaml`
- `.flow-agent/reports/<version>-*.md`

## Release PR declaration

Every release PR must state:

- package version target
- active public standard version impact
- artifact version impact
- conformance impact
- architecture boundary

## Current package-line policy decision

The v0.8.4 release adds planner capability constraints as a pre-projection gate over existing target compatibility analysis.

It does not bump:

- public Flow standard version
- Intent version
- AST version
- ExecutionPlan version
- TargetManifest version
- TargetRegistry version
- schema version paths

It also does not introduce:

- runtime executor
- SDK API
- plugin lifecycle
- target-specific public DSL
- renderer expansion
- Flow syntax expansion

## Historical package-line policy decisions

- v0.8.3 aligned package and release metadata only.
