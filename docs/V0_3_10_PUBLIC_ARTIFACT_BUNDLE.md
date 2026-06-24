# Flow v0.3.10 - Public Artifact Bundle Contract

v0.3.10 keeps Flow on the main standardization path:

```text
Human / AI intent
  -> Standard Intent Model
  -> decision, safety and capability validation
  -> ExecutionPlan
  -> target capability negotiation
  -> execution readiness decision
  -> target selection report
  -> target decision trace
  -> public artifact bundle
  -> target manifest / renderer
```

This release does not add syntax, runtime execution, SDK hooks or target templates. It adds a public manifest for exported artifacts.

## Public artifact

`flow-artifact-bundle.json` describes:

- artifact names,
- artifact roles,
- schema paths,
- required vs optional status,
- derived vs source status,
- pipeline order,
- derivation sources.

## Why this belongs in Flow

If Flow is a standard, consumers outside the Kotlin CLI need one stable manifest that explains what was exported. The bundle manifest makes the output directory self-describing and easier for adapters, CI jobs, AI agents and conformance runners to consume.
