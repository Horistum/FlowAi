# Flow v0.3.1 Notes

Flow v0.3.1 focuses on making Flow a stronger standardization layer rather than adding new syntax.

## Main changes

- Stabilized the Execution Plan as a richer public contract.
- Expanded `execution-plan.schema.json` with inputs, outputs, dependencies, effects, safety, required capabilities, target hints and assumptions.
- Added target capability negotiation reports across all registered targets.
- Added scenario packs:
  - `database-migration`
  - `certificate-renewal`
  - `kubernetes-maintenance`
- Added standard capabilities:
  - `DATABASE_MIGRATE`
  - `CERTIFICATE_RENEW`
  - `KUBERNETES_MAINTENANCE`
- Expanded AI normalization reports with extracted entities, missing decisions, safety gates, target portability and scenario selection rationale.
- Added conformance vectors for the new scenario packs and target negotiation.

## Design rule

No scenario pack may invent critical values. Missing database, certificate or Kubernetes scope information becomes a required clarification and blocks lowering.

## Architecture direction

The codebase already keeps core concepts mostly separate from adapters. v0.3.1 strengthens that direction by keeping:

- public contracts in schemas,
- semantic capability data in the standard catalog,
- target differences in the target registry,
- platform rendering in generators.

Future work should continue separating pure core contracts from Jackson/YAML/CLI/rendering adapters.
