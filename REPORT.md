# Flow Core Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed correction track: `v0.9.5.x`
Active architecture track: `v0.9.7 Universal Semantic Foundation`
Completed roadmap item: `0.9.7.0 Roadmap Separation and Universal Drift Lock`
Next Core roadmap item: `0.9.7.1 Canonical Intent Meaning`

## Release purpose

Flow 0.9.5 completes the universal semantic and projection model that the v0.9.5.x repair track prepared. The release has one reconciled manifest generation boundary, first-class trigger semantics, target-neutral lowering, extensible target semantics and evidence-driven native projection.

## Corrected integrity gaps

1. Manifest generators expose only a final reconciled `generate` path. `TargetManifestGenerationPipeline` is the CLI boundary, so CLI and conformance cannot serialize different compatibility truths.
2. Existing target renderers contain no no-op job or phantom task fallback. Executable rendering requires a structured native payload.
3. Generic DEPLOY and VERIFY intent lower to semantic standard actions. Concrete systems, namespaces and selectors appear only through explicit provider intent.
4. Intent, AST, ExecutionPlan and TargetManifest carry manual, schedule, event and webhook triggers. Interval schedules use ISO-8601 durations such as `P30D`.
5. `TargetSemanticsEntry` uses `semanticsByTarget`, keyed by target registry ids. Adding a target does not change the public Kotlin data model.
6. Materialization is selected by declarative target projection rules. Missing rules fail closed as `ADAPTER_REQUIRED`; concrete native payload evidence is required before executable target syntax can be produced.
7. The package, public standard and serialized artifact contracts are deliberately promoted and documented instead of leaving materially changed formats at historical version numbers.

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

## Versioning boundary

- Published package version: `0.9.5`
- Next package version: `0.9.6`
- Active public standard version: `0.8.0`
- Intent artifact version: `2.0`
- AST artifact version: `2.0`
- ExecutionPlan artifact version: `2.0`
- TargetManifest artifact version: `2.0`
- TargetRegistry artifact version: `2.0`
- Target semantics matrix version: `2.0`

Historical correction and roadmap identifiers remain traceability labels for completed work. They are not package or artifact versions.

## Validation source

GitHub Actions **Flow CI #1520**, run `29560189402`, completed successfully on implementation head `08ee3947792cd703d54f4f270d7e83568685e509` with:

- Flow Agent tooling tests;
- Flow Agent structure validation;
- Flow Agent context generation;
- offline tests when cache was available;
- offline conformance when cache was available;
- clean compile and full test suite;
- full conformance;
- CI logs and test report artifacts.

The final validation-metadata-only head must also pass the same unmodified standard Flow CI workflow. No temporary workflow, patch transport or snapshot writer is part of the diff.

## Architecture boundary

Flow remains a notes-driven universal automation language and standardization model. It does not execute workflows, expose an SDK lifecycle, own a plugin framework, project raw runtime instructions or allow concrete platforms to define universal semantic meaning.
