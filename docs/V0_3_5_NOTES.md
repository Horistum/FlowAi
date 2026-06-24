# Flow v0.3.5 Notes

v0.3.5 adds the Intent Decision Model.

The purpose is explicit: Flow must not silently guess critical automation values. It should either extract a decision, ask for it, or record a visible assumption.

## Public report

The new public output is:

```text
intent-decision-report.json
```

It contains:

- extracted decisions,
- missing decisions,
- assumptions,
- risks,
- safety gates,
- lowering decision.

## Blocking decisions

The initial model blocks lowering for:

- cleanup without retention/safety,
- database migration without backup,
- production deploy without approval.

## Recommended decisions

The initial model reports but does not block:

- backup schedule without timezone.

## Why this matters

This strengthens the original Flow path:

```text
Human / AI intent
-> Standard Intent Model
-> explicit decisions and missing decisions
-> validation of rules, risks and capabilities
-> Execution Plan
-> target capability negotiation
```

The decision model is intentionally not a runtime, plugin API or target renderer. It is a design-time standardization layer.
