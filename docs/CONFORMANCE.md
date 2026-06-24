# Flow Conformance v0.1.8 Draft

Flow is intended to become a standard, not only an implementation. For that reason,
new semantic layers must be backed by conformance vectors.

## v0.1.8 Conformance Areas

- Intent YAML loading and normalization
- Intent capability taxonomy
- Generic DAG lowering from `requires`
- Non-CI/CD intent lowering through semantic `standard.execute`
- Compatibility analysis and gate enforcement
- Missing dependency and cyclic dependency detection
- Deploy lowering without hardcoded `environment` / `version`
- Rollback lowering as explicit semantic action

## Current In-Repo Test Entry

The conformance vectors are implemented in:

```text
tests/FlowIntentConformanceTests.kt
```

and are called from:

```text
src/test/kotlin/FlowSpecJUnitTest.kt
```

## Important Principle

Every new intent capability must have at least one conformance vector before it can
be treated as part of the stable standard surface.
