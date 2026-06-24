# Work In Progress

Status: v0.3.0-rc1.8.3.

## Still Draft

- Vendor renderers are demonstrators of the generator contract, not production-safe emitters.
- Tekton generator is partial and must be gated by compatibility reports.
- Snapshot conformance checks currently verify presence and standard-version metadata; exact canonical diffing is planned.
- Target capability taxonomy needs more detail for artifacts, workspaces, secrets and runtime requirements.
- Intent taxonomy needs more conformance vectors for backup, restore, data pipeline, runbook, incident response and secret rotation.

## Architectural Rule

Do not solve platform differences by adding ad-hoc syntax. Add capability metadata, validation rules, standard intent semantics or target manifest mappings.

## v0.3.0-rc1.8.3 work in progress

The code now includes a Standard Intent Catalog and an Intent Design Report. This is a deliberate correction back toward the original Flow goal: the user describes architecture-level intent, while Flow discovers required systems, missing decisions and target portability constraints.

Still open:

- Organization-specific policy catalogs are not implemented.
- Stable conformance vectors are required for every `stable` standard capability.
- Some operational capabilities still lower to `standard.execute` until concrete modules exist.
- Target renderers are still draft renderers, not production-grade vendor generators.

## v0.3.0-rc1.8.3 work in progress

- Tekton remains a partial target. Simple input equality conditions are mapped to `when`; complex Flow expressions still require a runtime adapter or a target-specific mapping. This is intentional and reported through mapping notes instead of being silently hidden.
- `standard.execute` remains an auditable semantic placeholder for capabilities whose production-grade lowering contract is not yet implemented.
- Renderer output is useful for first generated DevOps artifacts, but production users should review mapping notes and compatibility reports before execution.


## AI Intent Normalization Layer

Implemented as a deterministic MVP in `0.3.0-rc1.8.3`. This is intentionally provider-neutral: core defines the contract and a scenario-pack normalizer, while real LLM providers must be adapters that return the same normalized model and pass the same validators.

Open work:
- richer entity extraction,
- provider adapter interface packages,
- golden normalization vectors,
- reverse engineering existing vendor pipelines into normalized intents.


## v0.3.0-rc1.8.3 Scenario Pack Follow-ups

- Scenario packs are currently deterministic/rule-based MVP implementations.
- Future work should add pack-specific golden snapshots and richer entity extraction.
- Scenario packs must remain platform-neutral and must not emit Jenkins/GitHub/Tekton syntax directly.
- Additional packs planned: IaC/provisioning, scheduled jobs, policy/compliance, migration, observability checks.
