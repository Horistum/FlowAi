# Flow Core Report

Current package line: `0.8.2`
Active public standard version: `0.7.6`

The version difference is intentional. Package releases may continue roadmap work while the active public standard version remains pinned until snapshot, export and conformance contracts are deliberately advanced together.

## Current scope

The current package line includes:

- v0.7.6 semantic correctness hardening
- v0.7.7 scenario pack quality analysis
- v0.8.0 core contract check
- v0.8.1 target capability matrix
- v0.8.2 target negotiation explanation report
- consolidated review fixes for package-line metadata, module contracts, expression rendering, error-handler projection, planner data dependencies, schema ids, and legacy generator facades

## Current validation source

Release-specific validation details are kept in `.flow-agent/reports/`, `CHANGELOG.md`, and pull request CI history. This top-level report intentionally avoids hardcoded historical test counts because the test and conformance counts change as gates are added.

The current validation source is the local Gradle run performed by the reviewer or GitHub Actions on the branch commit:

- `Compile and Test`
- `Run Conformance`

## Architecture boundary

No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change is introduced by the package-line review fixes.
