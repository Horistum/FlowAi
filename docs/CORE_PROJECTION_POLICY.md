# Core Projection Policy

## Rule

Flow Core must not use a raw command string as the universal action representation or default projection model.

A raw command string hides runtime assumptions, target assumptions, tool availability, operating system behavior, credentials, network access and materialization status.

## Required direction

Flow must move toward:

```text
Intent
  -> AST
  -> Semantic Action Graph
  -> notes-based validation and decision analysis
  -> materialization negotiation
  -> target projection from notes
  -> target artifact
```

The semantic action graph must represent capabilities, subjects, inputs, safety policy, runtime requirements and materialization status as structured data.

## Materialization honesty

Every action must be classified before it is treated as executable:

- `NATIVE`
- `NOTES_PROJECTED`
- `ADAPTER_REQUIRED`
- `DECLARATIVE_ONLY`
- `SEMANTIC_ONLY`
- `UNSUPPORTED`
- `BLOCKED`

Semantic-only output must not be reported as successful automation materialization.

## Existing implementation status

Existing command-oriented paths are legacy defects to inventory and remove through the v0.9.5.x correction track.

The inventory begins in v0.9.5.1 and must include:

- `TargetManifest.run`
- `runCommandFor`
- command-oriented action mappings
- semantic placeholder output
- renderer script blocks
- tests that assert command-oriented output
- documentation that calls command-oriented projection portable

## Boundary

This policy does not introduce a runtime executor, SDK API, plugin lifecycle, target-specific public DSL or Flow syntax change.
