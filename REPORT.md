# Flow Core Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed correction track: `v0.9.5.x`
Active architecture track: `v0.9.7 Universal Semantic Foundation`
Completed roadmap item: `0.9.7.0 Roadmap Separation and Universal Drift Lock`
Next Core roadmap item: `0.9.7.1 Canonical Intent Meaning`

## Active architecture direction

The roadmap is now split into independent Core, adapter and conformance streams.

- Core owns universal semantic meaning, effects, dependencies, policy, control requirements, materialization authority and abstract topology requirements.
- Adapter work owns concrete bindings, topology evidence and target artifact generation.
- Conformance owns cross-domain semantic adequacy, abstract topology behavior, equivalence rules and independent adapter certification.

The Flow Agent selects the next primary item from the Core stream while allowing other streams to maintain independent planning state. Core roadmap items must declare a universal invariant, forbidden scope and completion evidence. Concrete platform and implementation-tool names are rejected from Core item names, purposes and required outcomes.

## v0.9.7.0 outcome

- Replaced the single mixed roadmap with a top-level index and three active roadmap authorities.
- Moved completed v0.7 through v0.9.6 work into a historical roadmap.
- Added mechanical validation for stream declarations, repository-contained paths, one next item per stream, a required next Core item and mandatory Core governance fields.
- Added negative tests for concrete implementation scope in the Core roadmap.
- Updated generated Flow Agent context to include all active roadmap streams while keeping the Core stream primary.
- Updated the work-package template, architecture constitution and forbidden-direction catalog to encode the same boundary.
- Kept package, public standard and artifact contract versions unchanged.
- Changed no production Kotlin source.

## Validation source

Local isolated validation completed for:

- Flow Agent roadmap tooling unit tests;
- split-roadmap structure validation;
- primary Core next-item resolution;
- negative drift tests.

The unmodified standard Flow CI on the pull request branch remains authoritative for full repository tests and conformance.

## Architecture boundary

Flow remains a notes-driven universal automation language and standardization model. It does not execute workflows, expose an SDK lifecycle, own a plugin framework, project raw runtime instructions or allow concrete platforms to define universal semantic meaning.
