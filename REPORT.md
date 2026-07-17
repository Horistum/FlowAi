# Flow Core Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Active architecture track: `v0.9.7 Universal Semantic Foundation`
Completed roadmap item: `0.9.7.0 Roadmap Separation and Universal Drift Lock`
Next Core roadmap item: `0.9.7.1 Governance Signal Integrity`
Bounded closure item: `0.9.7.10 Bounded Semantic Closure Gate`

## Active direction

The roadmap has separate Core, adapter and conformance streams.

- Core owns universal meaning, effects, dependencies, policy, controls, materialization authority and abstract topology requirements.
- Adapter work owns concrete bindings, topology evidence, target artifacts and maintenance of existing reference evidence.
- Conformance owns bounded domain evidence, topology behavior, equivalence and adapter profiles.

Core cannot depend on unfinished adapter or conformance work. Those streams may consume declared Core contracts but may not redefine them.

Existing executable reference evidence stays active throughout the Core track. It provides feedback rather than semantic authority. Adapter-local regressions are repaired at the adapter boundary; readiness labels disproved by continuity or topology evidence are deliberately demoted.

## Revised v0.9.7 order

1. `0.9.7.1 Governance Signal Integrity`
2. `0.9.7.2 Canonical Module and Notes Authority`
3. `0.9.7.3 Mandatory Materialization Authority`
4. `0.9.7.4 Universal Dependency and Continuity Contract`
5. `0.9.7.5 Canonical Intent Meaning`
6. `0.9.7.6 Universal Effect and State Transition Model`
7. `0.9.7.7 Universal Control and Policy Requirements`
8. `0.9.7.8 Abstract Execution Topology Model`
9. `0.9.7.9 Intent Lowering and Diagnostic Honesty`
10. `0.9.7.10 Bounded Semantic Closure Gate`

Known validation and contract defects are intentionally scheduled before heavier semantic design.

## Domain boundary

The active completion corpus is limited to:

- software delivery;
- data transformation or synchronization;
- infrastructure state change.

Other automation domains remain future or exploratory work and cannot expand the current completion gate.

## v0.9.7.0 outcome

- Replaced the mixed roadmap with a top-level index and three active planning streams.
- Moved completed v0.7 through v0.9.6 work into historical evidence.
- Added structural checks for stream paths, next-item state, mandatory Core evidence fields and dependency direction.
- Removed the proposed roadmap keyword scanner.
- Added continuous reference maintenance and intended-demotion rules.
- Added bounded exit criteria tied to the declared completion evidence.
- Kept package, standard and artifact versions unchanged.
- Changed no production Kotlin source.

## Validation

The unmodified standard Flow CI on the final PR #64 head is authoritative. It must run Flow Agent checks, compilation, tests and full conformance without weakened gates.

## Architecture boundary

Flow remains a notes-driven universal automation language and standardization model. It does not execute workflows, expose an SDK lifecycle, own a plugin framework, project raw runtime instructions or allow concrete implementations to define universal meaning.
