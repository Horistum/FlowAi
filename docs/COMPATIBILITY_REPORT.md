# Flow Compatibility Report v1.0

The Compatibility Report explains whether an Execution Plan can be generated or executed on a target platform.

## Purpose

Flow should not pretend that all targets behave the same.

The compatibility report protects the user from silent semantic loss.

## Report shape

```json
{
  "target": "tekton",
  "status": "PARTIAL",
  "issues": [
    {
      "level": "ERROR",
      "target": "tekton",
      "nodeId": "approve_1",
      "feature": "approvals",
      "message": "Feature 'approvals' is not supported by target 'tekton'."
    }
  ]
}
```

## Status calculation

- no issues -> SUPPORTED
- warning only -> PARTIAL
- at least one error -> UNSUPPORTED

## Where it fits

```text
Flow AST
  -> Validator
  -> Execution Plan
  -> Compatibility Analyzer
  -> Compatibility Report
  -> Generator / Runtime
```

## Work in progress

- Severity rules are still basic.
- Some partial cases should become generator-specific advisory messages.
- Compatibility should later understand organization policy, not only target capability.
