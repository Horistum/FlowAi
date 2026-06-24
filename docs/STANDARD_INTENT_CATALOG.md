# Flow Standard Intent Catalog

Flow is intended to make users describe automation intent, rules and risk boundaries instead of memorizing workflow-specific YAML dialects.

The standard capability catalog is the vocabulary that connects human/AI intent with lower-level Flow AST, execution plans and target generators.

## Design rule

A new automation scenario should first be expressed as a standard capability before falling back to a low-level command or custom module action.

Preferred:

```yaml
capability: BACKUP
```

Fallback only when necessary:

```yaml
capability: RUN_COMMAND
params:
  command: ./backup.sh
```

## Maturity levels

- `stable`: intended for regular conformance vectors.
- `draft`: valid standard direction, but requires more target/module contracts.
- `extension`: organization or module-specific capability.

## Current catalog

The executable catalog is implemented in:

```text
src/main/kotlin/org/flowlang/standard/StandardIntentCatalog.kt
```

CLI:

```bash
./gradlew run --args="catalog"
./gradlew run --args="catalog --markdown"
```

## Work in progress

- Some draft capabilities still lower to `standard.execute` until a specific module contract exists.
- The next step is to attach conformance vectors to each stable capability.
