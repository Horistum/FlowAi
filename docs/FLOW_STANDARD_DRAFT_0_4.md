# Flow Standard Draft 0.4

Flow is not intended to become another workflow syntax, runtime executor or target-specific YAML generator.

v0.4.3 keeps this draft surface, hardens semantic correctness, removes SDK-like boundary language and adds architecture governance guardrails: Flow publishes target conformance obligations, not adapter implementation APIs.

Flow's purpose is:

```text
Human / AI intent
-> standard intent model
-> validation of rules, risks and capabilities
-> execution plan
-> target adapter boundary
-> public artifacts, evidence and compliance
```

## Public draft surface

v0.4.3 publishes:

- `standard-freeze-report.json`,
- `compatibility-policy.json`,
- `reference-corpus-index.json`,
- `negative-conformance-corpus.json`,
- `target-conformance-profile.json`,
- `standard-index.json`,
- `conformance-suite.json`,
- `flow-standard-draft.json`.

These artifacts close the standard surface around:

- stable contracts,
- compatibility rules,
- reference behavior,
- negative behavior,
- target conformance,
- conformance suite discovery,
- final compliance status.

## What remains outside v0.4.3

v0.4.3 intentionally does not add:

- new Flow syntax,
- a runtime executor,
- SDK/plugin execution,
- new target renderers,
- direct AI-to-YAML generation.

Target-specific tools should consume the public artifacts, especially:

- `execution-plan.json`,
- `execution-readiness-report.json`,
- `target-adapter-contract.json`,
- `standard-compliance-report.json`,
- `flow-standard-draft.json`.

That keeps Flow as a standardization layer between Human/AI intent and execution platforms.
