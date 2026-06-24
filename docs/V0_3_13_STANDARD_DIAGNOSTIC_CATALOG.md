# Flow v0.3.13 - Standard Diagnostic Code Catalog

v0.3.13 adds a stable diagnostic code catalog to the public Flow standard surface.

The goal is narrow: every public report may still contain human-readable messages, but tools, CI jobs, adapters and AI agents should key decisions on stable codes.

## Public artifact

The new artifact is:

```text
standard-diagnostic-catalog.json
```

It is validated by:

```text
schemas/standard-diagnostic-catalog.schema.json
```

The report contains:

- `standardVersion`,
- `diagnosticCatalogVersion`,
- `codes`.

Each code contains:

- `code`,
- `area`,
- `severity`,
- `stability`,
- `usedBy`,
- `description`.

## CLI

Print JSON:

```bash
./gradlew run --args="diagnostics"
```

Print Markdown:

```bash
./gradlew run --args="diagnostics --markdown"
```

Export the public artifact:

```bash
./gradlew run --args="diagnostics --out generated/diagnostics"
```

The conformance export also writes the catalog:

```bash
./gradlew run --args="conformance --out generated/conformance"
```

## Standard areas

v0.3.13 publishes stable codes for:

- `intent`,
- `flow-validation`,
- `safety`,
- `target`,
- `adapter`,
- `module`,
- `schema`,
- `conformance`.

## Boundary

This version intentionally does not add a new SDK, runtime executor, target renderer or workflow syntax.

The diagnostic catalog belongs to the core standard layer because it makes the existing pipeline more deterministic:

```text
Human / AI intent
-> standard intent model
-> validation of rules, risks and capabilities
-> execution plan
-> target adapter boundary
-> diagnostics with stable codes
```

Adapters can emit target-specific details, but the decision codes remain part of the Flow standard surface.
