# v0.9.5.0 Architecture Recenter - Notes-Driven Flow

## Purpose

v0.9.5.0 recenters Flow around the original project idea before any end-to-end readiness claim.

Flow is a language and universal automation standardization model. Flow uses a universal decision, validation and generation engine over declarative notes packages. Flow is not an SDK, framework, plugin lifecycle platform or CI/CD transpiler.

The v0.9.5.x track exists because the current implementation drifted toward hardcoded CI/CD targets, command-oriented projection and semantic placeholders. That direction must be corrected before v1.0.0.

## Architecture identity

Flow Core must remain responsible for:

- interpreting automation intent
- validating rules and safety boundaries
- classifying risk
- negotiating materialization
- generating target artifacts from declared rules
- reporting unsupported, degraded or semantic-only behavior honestly

Flow Core must not treat a concrete tool, runtime or target platform as semantic truth.

## Notes packages

The future architecture is notes-driven. Notes packages are declarative rule sets, not SDK plugins and not runtime extensions.

Required notes families:

- Domain Notes: define automation domains and their vocabulary
- Capability Notes: define the meaning and inputs of capabilities
- Safety Notes: define risk, approvals and policy boundaries
- Runtime Notes: define required environment, credentials, tools, network and execution substrate
- Target Notes: define what a target can represent
- Projection Notes: define how a semantic action becomes target artifact structure
- Conformance Notes: define how honesty is verified

## Correction track

The v0.9.5.x correction track replaces the former direct jump to end-to-end standard scenarios.

Planned sequence:

1. v0.9.5.0 Architecture Recenter - Notes-Driven Flow
2. v0.9.5.1 Shell Usage Inventory and Prohibition
3. v0.9.5.2 CI/CD Bias Inventory
4. v0.9.5.3 Notes Package Contract Model
5. v0.9.5.4 Universal Semantic Action Graph
6. v0.9.5.5 Materialization Negotiation
7. v0.9.5.6 No-Shell Target Projection
8. v0.9.5.7 Target Registry Honesty
9. v0.9.5.8 Policy-Driven Safety and Environment Classification
10. v0.9.5.9 Trigger and Schedule Notes
11. v0.9.5.10 Conformance Honesty Gates

## Boundary

This roadmap recenter does not add:

- runtime execution
- SDK API
- plugin lifecycle
- target-specific public DSL
- renderer expansion
- Flow syntax expansion
- public standard version bump
- artifact schema version bump

The active public standard remains `0.7.6`.
