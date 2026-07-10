# v0.9.5.7 Target Registry Honesty

## Purpose

v0.9.5.7 separates target existence from target support.

A target may be known to the registry without being implemented, tested, production-supported or safe to project to. This prevents concrete target names from becoming support claims by default.

## Registry statuses

The target registry now models support as explicit statuses:

- `DECLARED_ONLY`: target name and notes declaration exist, but no execution support is claimed
- `EXPERIMENTAL`: target is under review or early exploration
- `IMPLEMENTED`: implementation evidence exists
- `TESTED`: implementation, projection and test evidence exist
- `PRODUCTION_SUPPORTED`: implementation, projection, test, conformance and capability evidence exist
- `DEPRECATED`: target is retained only with review evidence
- `BLOCKED`: target is prohibited by policy or review decision

## Evidence model

Support status requires evidence. The model records:

- notes declarations
- implementation references
- projection plan references
- test references
- conformance references
- review decisions
- policy decisions

This keeps registry state reviewable and prevents silent upgrades.

## Baseline registry

The baseline snapshot records Jenkins, GitHub Actions and Tekton as `TESTED`, not `PRODUCTION_SUPPORTED`.

The baseline also records shell as `BLOCKED` so it cannot return as a default projection target through registry drift.

## Validation rules

The validator checks:

- valid and unique target ids
- display names and status reasons
- evidence presence and evidence references
- status-specific evidence requirements
- declared-only targets do not cite implementation, projection, test or conformance evidence
- tested targets cite implementation, projection and test evidence
- production-supported targets cite implementation, projection, test and conformance evidence and declare capabilities
- deprecated targets cite review evidence
- blocked targets cite policy or review evidence
- shell-like targets are registered only as blocked
- registry evidence does not use implicit support language

## Correction result

This step creates the honesty layer needed before future target support can be safely advertised.

v0.9.5.8 can now move safety and environment classification into policy-driven notes without relying on target names or production string guessing.

## Versioning boundary

- Current package version remains `0.9.4`.
- Active public standard version remains `0.7.6`.
- This is roadmap correction work inside the v0.9.5.x correction track.
- No artifact schema version is changed.
