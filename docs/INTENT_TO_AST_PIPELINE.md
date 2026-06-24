# Intent to AST Pipeline v0.1.7

This document describes the first implemented bridge from the high-level Standard Intent Model to low-level canonical Flow AST.

## Why this exists

Flow must not become only a hand-written low-level DSL. The primary architecture is:

```text
Human / AI Intent
  -> Standard Intent Model
  -> IntentToAstPlanner
  -> Canonical Flow AST
  -> Validator
  -> Execution Plan
  -> Target Compatibility Report
  -> Generator / Runtime
```

The intent layer is the stable, AI-friendly representation. Low-level `.flow` remains useful, but it is no longer the only entry point.

## Implemented in v0.1.7

- `IntentYamlLoader`
- `IntentToAstPlanner`
- CLI command:

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target jenkins"
```

## CLI output

The intent command prints:

1. Normalized intent JSON
2. Generated Flow AST JSON
3. Validation report
4. Execution Plan JSON
5. Target compatibility report
6. Draft generator output for supported targets

## Current mapping rules

| Standard capability | Generated low-level Flow AST |
|---|---|
| `CHECKOUT` | `git.checkout` |
| `TEST` | `shell.run` with `mvn test` default |
| `BUILD` | `shell.run` with `mvn package` default |
| `BUILD_IMAGE` | `docker.build` |
| `PUSH_IMAGE` | `docker.push` |
| `APPROVE` | `approve manual`, optionally wrapped in `if` when approval policy has a condition |
| `DEPLOY` | `kubernetes.deploy` |
| `VERIFY` | `kubernetes.get` with basic `ok` expectation |
| `NOTIFY` | `notify.send` |
| `RUN_COMMAND` | `shell.run` |

## Work in progress

- Rollback is represented by `standard.rollback`; fully target-native rollback strategies remain a compatibility/module concern.
- Intent dependencies are respected for ordering, but the low-level Execution Plan dependency model still needs an explicit intent-dependency bridge.
- Target-specific generators are still drafts.
- More high-level capabilities are planned: backup, restore, cleanup, report, API sync, DB sync.
