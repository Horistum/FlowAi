# Flow Core Report

Current package line: `0.9.4`
Active public standard version: `0.7.6`
Next expected package line: `0.9.5`
First scoped correction item: `0.9.5.0`
Current scoped correction item: `0.9.5.7.1`

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

The next umbrella package line is `0.9.5`, and the first scoped correction item is `0.9.5.0 Architecture Recenter - Notes-Driven Flow`.

This is a correction track before any end-to-end readiness claim. Flow Core must be re-centered around the original architecture:

- Flow is a language and universal automation standardization model.
- Flow uses a universal decision, validation and generation engine over declarative notes packages.
- Flow Core is not an SDK, framework, plugin lifecycle or CI/CD transpiler.
- Domain, capability, runtime, safety, target and projection behavior must be declared through structured notes packages before it is treated as supported.
- CI/CD target projections are adapter concerns, not the semantic source of truth.
- Semantic-only placeholders must not be reported as successful automation materialization.

The former direct `0.9.5 End-to-End Standard Scenarios` step is deferred until the `0.9.5.x` correction track is complete.

## Current correction progress

`0.9.5.1 Shell Usage Inventory and Prohibition` records the known command-oriented projection paths and classifies them as legacy defects scheduled for removal.

`0.9.5.2 CI/CD Bias Inventory` records remaining hardcoded CI/CD, target, infrastructure, data-system and workflow vocabulary assumptions and adds drift tests that keep adapter-boundary evidence separate from semantic-core debt.

`0.9.5.3 Notes Package Contract Model` defines the first internal contract surface for declarative domain, capability, safety, runtime, target, projection and conformance notes packages.

`0.9.5.4 Universal Semantic Action Graph` defines a target-neutral action graph over notes declarations and prevents command or shell vocabulary from becoming universal semantic action meaning.

`0.9.5.5 Materialization Negotiation` adds explicit per-node materialization decisions so semantic actions cannot be inferred as fulfilled without evidence.

`0.9.5.6 No-Shell Target Projection` adds projection artifact records that must be target-native, notes-backed, adapter-boundary, review or conformance records instead of generic command representation.

`0.9.5.7 Target Registry Honesty` separates declared, experimental, implemented, tested, production-supported, deprecated and blocked target states so target names cannot imply support by default.

`0.9.5.7.1 Connect Materialization Pipeline` starts the repair subtrack by wiring real execution-plan tasks into semantic graph, materialization negotiation and projection artifact evidence before target manifest emission.

The repair subtrack is recorded in `.flow-agent/roadmap-v0.9.5.7-repair-track.yaml` and continues through `0.9.5.7.9` before the project resumes the broader `0.9.5.8` safety-policy step.

## Current validation source

Release-specific validation details are kept in `.flow-agent/reports/`, `CHANGELOG.md`, and pull request CI history. This top-level report intentionally avoids hardcoded historical test counts because the test and conformance counts change as gates are added.

The current validation source is the local Gradle run performed by the reviewer or GitHub Actions on the branch commit:

- `Compile and Test`
- `Run Conformance`

## Versioning boundary

- Package version: `0.9.4`
- Active public standard version: `0.7.6`
- Next expected package line: `0.9.5`
- First scoped correction item: `0.9.5.0`
- Current scoped correction item: `0.9.5.7.1`
- Intent artifact version: `1.0`
- AST artifact version: `1.0`
- Execution plan artifact version: `1.1`
- Target manifest artifact version: `1.0`
- Target registry artifact version: `1.0`

The v0.9.4 package line does not bump the public standard or artifact schema versions.

## Architecture boundary

v0.9.5.7.1 is a wiring repair. It connects existing notes, semantic, materialization and projection contract models to manifest task materialization without claiming broad executable target readiness.
