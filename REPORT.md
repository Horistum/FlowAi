# Flow Core Report

Current package line: `0.8.2`
Active public standard version: `0.7.6`

The version difference is intentional. Package releases may continue roadmap work while the active public standard version remains pinned until snapshot, export, and conformance contracts are deliberately advanced together.

## Current scope

The current package line includes:

- v0.7.6 semantic correctness hardening
- v0.7.7 scenario pack quality analysis
- v0.8.0 core contract check
- v0.8.x conformance quality-gate wiring
- v0.8.1 target capability matrix
- v0.8.2 target negotiation explanation report
- v0.8.3 review-quality fixes for module metadata, condition rendering, planner dependencies, and error-handler projection safety

## Current validation source

Release-specific validation details are kept in `.flow-agent/reports/`, `CHANGELOG.md`, pull request history, and CI runs. This top-level report intentionally avoids hardcoded historical test or conformance counts because those counts change as gates and regression tests are added.

The current validation source is GitHub Actions on the latest pull request or main commit:

- Compile and Test
- Run Conformance

## Architecture boundary

The current package line does not add a runtime executor, SDK API, plugin lifecycle, target-specific public DSL, or Flow syntax expansion.

## Notes

Older mixed-version sections were removed from this file because they made the current repository state harder to audit. Historical details remain available through release reports and pull request history.
