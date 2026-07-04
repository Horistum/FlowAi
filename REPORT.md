# Flow Core Report

Current package line: `0.9.4`
Active public standard version: `0.7.6`
Next expected package line: `0.9.5.0`

The version difference is intentional. Package releases may continue roadmap work while the active public standard version remains pinned until snapshot, export and conformance contracts are deliberately advanced together.

## Current scope

The current package line includes:

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

## Architecture recenter direction

The next package line is `0.9.5.0 Architecture Recenter - Notes-Driven Flow`.

This is a correction track before any end-to-end readiness claim. Flow Core must be re-centered around the original architecture:

- Flow is a language and universal automation standardization model.
- Flow uses a universal decision, validation and generation engine over declarative notes packages.
- Flow Core is not an SDK, framework, plugin lifecycle or CI/CD transpiler.
- Domain, capability, runtime, safety, target and projection behavior must be declared through structured notes packages before it is treated as supported.
- CI/CD target projections are adapter concerns, not the semantic source of truth.
- Semantic-only placeholders must not be reported as successful automation materialization.

The former direct `0.9.5 End-to-End Standard Scenarios` step is replaced by the `0.9.5.x` correction track in `.flow-agent/roadmap.yaml`.

## Current validation source

Release-specific validation details are kept in `.flow-agent/reports/`, `CHANGELOG.md`, and pull request CI history. This top-level report intentionally avoids hardcoded historical test counts because the test and conformance counts change as gates are added.

The current validation source is the local Gradle run performed by the reviewer or GitHub Actions on the branch commit:

- `Compile and Test`
- `Run Conformance`

## Versioning boundary

- Package version: `0.9.4`
- Active public standard version: `0.7.6`
- Next expected package line: `0.9.5.0`
- Intent artifact version: `1.0`
- AST artifact version: `1.0`
- Execution plan artifact version: `1.1`
- Target manifest artifact version: `1.0`
- Target registry artifact version: `1.0`

The v0.9.4 package line does not bump the public standard or artifact schema versions.

## Architecture boundary

No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change is introduced by the v0.9.4 reference scenario matrix package line.
