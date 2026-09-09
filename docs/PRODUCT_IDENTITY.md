# Product identity and retained technical names

## Decision

**Horistum** is the primary product name. **Flow AI** is the former product name, retained when explaining project history. This decision changes presentation, not the underlying automation standard or its implementation contracts.

Use Horistum in current product introductions, overview headings and new product-facing prose. A brief "formerly Flow AI" explanation is sufficient at an entry point; do not present two competing current product brands.

Existing technical identifiers remain intentional and supported under their existing compatibility rules. Their presence is not unfinished rebranding work and does not create a requirement for a later mass rename.

## Naming boundary

| Surface | Naming decision |
| --- | --- |
| Main project presentation | Horistum |
| Historical product references | Flow AI, with context where needed |
| Current repository location | `milank78git/FlowAi`, unchanged by this change |
| Technical model and source language | Flow, Flow AST, `flow` syntax and `.flow` files remain unchanged |
| Kotlin and JVM identities | `org.flowlang`, existing classes, functions and entry point remain unchanged |
| Build and distribution identities | `flow-core`, `flow-*` modules, package coordinates and launchers remain unchanged |
| Tooling and CI | `.flow-agent/`, script paths, workflow identities, check names and configuration keys remain unchanged |
| Public data contracts | Existing fields such as `flowName`, artifact filenames, producer IDs and schema identities remain unchanged |
| Generated and historical evidence | Existing bytes, snapshots, hashes, receipts and version-specific documents are not rewritten for branding |

These are retained technical names, not additional product brands. New code continues to use responsibility-based names under [CODE_NAMING.md](CODE_NAMING.md); it does not need a `Horistum` prefix.

## Compatibility and authority

This change does not alter command names, CLI output, environment-variable prefixes, parser behavior, serialized payloads, schema `$id` values or public versions. In particular, it does not rename `flow-ast.json`, `flow-artifact-bundle.json`, `flowName` or `FLOW_SECRET_` references.

The existing [schema identity policy](SCHEMA_ID_POLICY.md) remains authoritative for schema IDs. The [architecture constitution](../.flow-agent/architecture-constitution.md), [release state](../.flow-agent/release-state.yaml) and [roadmap](../.flow-agent/roadmap.yaml) continue to own architecture, lifecycle and delivery planning. References to Flow in those authorities still apply to Horistum; the naming decision neither replaces them nor weakens their checks.

Branding alone does not change package, standard or artifact-contract versions. It does not imply new capabilities, expanded target support or a passed validation boundary.

## Repository and installation instructions

Keep links, clone locations, build paths and commands pointed at resources that actually exist. This documentation change does not rename the GitHub repository, register a domain, change repository metadata, or add a `horistum` executable.

A later repository rename or new launcher must be handled as an explicit operational change with its consumers and verification. It is not an automatic second stage of this decision. Do not publish a new clone URL or command before it is available and tested.

## Maintenance rule

When editing a surface, ask whether the name identifies the product or an existing technical contract. Use Horistum for the former and preserve the exact identifier for the latter. Do not perform a repository-wide text replacement.

Keep old evidence and historical documents intact. Update active presentation where useful, not every occurrence of an old name. Any future technical rename needs its own functional justification and compatibility review; the brand itself is not that justification.
