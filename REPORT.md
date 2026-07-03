# Flow Core Report

Current package line: `0.9.1`
Active public standard version: `0.7.6`

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

## Current validation source

Release-specific validation details are kept in `.flow-agent/reports/`, `CHANGELOG.md`, and pull request CI history. This top-level report intentionally avoids hardcoded historical test counts because the test and conformance counts change as gates are added.

The current validation source is the local Gradle run performed by the reviewer or GitHub Actions on the branch commit:

- `Compile and Test`
- `Run Conformance`

## Versioning boundary

- Package version: `0.9.1`
- Active public standard version: `0.7.6`
- Intent artifact version: `1.0`
- AST artifact version: `1.0`
- Execution plan artifact version: `1.1`
- Target manifest artifact version: `1.0`
- Target registry artifact version: `1.0`

The v0.9.1 package line does not bump the public standard or artifact schema versions.

## Architecture boundary

No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change is introduced by the v0.9.1 projection stability package line.
