# Flow Core Report

Current published package line: `0.9.4`
Package release status: `published`
Active correction track: `v0.9.5.x`
Correction-track release status: `unreleased`
Current scoped correction item: `0.9.5.7.7`
Next scoped correction item: `0.9.5.7.8`
Next expected package line: `0.9.5`
Active public standard version: `0.7.6`

The version axes are intentionally separate. `0.9.5.7.7` is a roadmap correction identifier, not the Gradle package version, public Flow standard version or an artifact contract version. The published package remains `0.9.4` until the v0.9.5 correction line is deliberately promoted as a release.

## Current package scope

The published `0.9.4` package line includes:

- v0.7.6 semantic correctness hardening
- v0.7.7 scenario pack quality analysis
- v0.8.0 core contract check
- v0.8.1 target capability matrix
- v0.8.2 target negotiation explanation report
- v0.8.3 package version and release metadata integrity
- v0.8.4 planner capability constraints
- v0.8.5 safety boundary hardening
- v0.8.6 review-hardening fixes
- v0.8.7 safety-boundary result handler coverage
- v0.9.0 generator projection contract
- v0.9.1 Jenkins/GitHub/Tekton projection stability
- v0.9.2 renderer contract hardening
- v0.9.3 capability degradation semantics
- v0.9.4 reference scenario matrix

## Unreleased correction direction

The next package line is `0.9.5`, but the former direct `0.9.5 End-to-End Standard Scenarios` milestone is deferred. Before any end-to-end readiness claim, Flow Core is being repaired through the v0.9.5.x correction track:

- Flow is a language and universal automation standardization model.
- Flow uses a universal decision, validation and generation engine over declarative notes packages.
- Flow Core is not an SDK, framework, plugin lifecycle, runtime executor or CI/CD transpiler.
- Domain, capability, runtime, safety, target and projection behavior must be declared through structured notes packages before it is treated as supported.
- CI/CD target projections are adapter concerns, not the semantic source of truth.
- Semantic-only placeholders must not be reported as successful automation materialization.

## Correction-track progress

- `0.9.5.0 Architecture Recenter - Notes-Driven Flow` re-established the project identity and correction direction.
- `0.9.5.1 Shell Usage Inventory and Prohibition` recorded and prohibited command-oriented projection paths.
- `0.9.5.2 CI/CD Bias Inventory` separated concrete domain vocabulary from universal semantics.
- `0.9.5.3 Notes Package Contract Model` introduced declarative domain, capability, safety, runtime, target, projection and conformance notes contracts.
- `0.9.5.4 Universal Semantic Action Graph` introduced a target-neutral semantic graph over notes declarations.
- `0.9.5.5 Materialization Negotiation` required explicit materialization decisions and evidence.
- `0.9.5.6 No-Shell Target Projection` replaced generic command representation with structured projection artifacts.
- `0.9.5.7 Target Registry Honesty` separated declared, implemented, tested and production-supported target states.
- `0.9.5.7.1 Connect Materialization Pipeline` connected real plan tasks to semantic, materialization and projection evidence.
- `0.9.5.7.2 Governance Scanner Honesty` replaced wording obfuscation and text-only ceremony with structural checks.
- `0.9.5.7.3 Renderer Failure Semantics Unification` unified executable, review-only and fail-fast renderer behavior.
- `0.9.5.7.4 Remove Legacy Shell Generator Fixtures` removed obsolete test generators and shell-output assertions.
- `0.9.5.7.5 Compatibility and Readiness Honesty` reconciled capability claims with concrete materialization and projection evidence.
- `0.9.5.7.6 Release Metadata Reconciliation` documented the package/correction boundary and removed future roadmap schedules from production analyzers.
- `0.9.5.7.7 Policy-Driven Safety Prelude` moves environment sensitivity and approval-environment selection behind explicit safety-policy evidence.

The next scoped repair item is `0.9.5.7.8 Target Expression and Unknown Target Safety`.

## Validation source

Release-specific validation details are kept in `.flow-agent/reports/`, `CHANGELOG.md` and pull request CI history. This top-level report intentionally avoids hardcoded historical test counts because test and conformance totals change as gates are added.

Authoritative branch validation consists of:

- Flow Agent tooling tests
- Flow Agent structure validation
- Flow Agent context generation
- offline tests when the dependency cache is available
- offline conformance when the dependency cache is available
- clean compile and test
- full conformance

## Versioning boundary

- Published package version: `0.9.4`
- Active correction track: `v0.9.5.x`
- Current correction item: `0.9.5.7.7`
- Correction item is a package version: `false`
- Next expected package version: `0.9.5`
- Active public standard version: `0.7.6`
- Intent artifact version: `1.0`
- AST artifact version: `1.0`
- Execution plan artifact version: `1.1`
- Target manifest artifact version: `1.0`
- Target registry artifact version: `1.0`

The unreleased v0.9.5.x correction track does not bump the public standard or artifact schema versions. Package promotion must be an explicit release decision after the correction boundary is complete enough to support the package claim.

## Architecture boundary

v0.9.5.7.7 changes safety-policy evidence and target approval-environment selection. It does not add renderer payload implementations, runtime execution, an SDK surface, a framework lifecycle, a shell generator or target-specific public Flow syntax.
