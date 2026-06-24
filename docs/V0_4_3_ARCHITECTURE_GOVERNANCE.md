# v0.4.3 Architecture Governance Guardrails

v0.4.3 adds governance guardrails around Flow's public standard boundary.

The purpose is not to add a feature. The purpose is to prevent future changes from turning Flow into an SDK, runtime executor, plugin framework, template engine or target-specific workflow language.

## Added files

- `docs/ARCHITECTURE_CONSTITUTION.md`
- `docs/adr/ADR_TEMPLATE.md`
- `standard/architecture/forbidden-directions.yaml`
- `standard/architecture/feature-classification.yaml`
- `standard/architecture/release-checklist.yaml`
- `standard/architecture/drift-score.yaml`

## Added conformance

- `v0.4.3.architecture-governance-guardrails`

The check validates that required governance files exist, required terms are present, forbidden directions are documented and active source does not expose SDK/runtime/plugin drift indicators.

## Report budget rule

New public reports are rejected by default. Existing reports must be extended first unless a new report defines a stable public contract that cannot fit an existing artifact.

## Negative conformance rule

Every release must include a negative conformance vector unless the release is documentation-only and explicitly states so.

## Drift Score rule

Every larger feature proposal must be evaluated with Drift Score. Negative-score proposals are rejected unless an Architecture Decision records a specific exception and adds a conformance guardrail.
