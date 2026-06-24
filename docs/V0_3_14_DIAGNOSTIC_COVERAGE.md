# Flow v0.3.14 - Diagnostic Coverage Report

v0.3.14 closes the loop introduced in v0.3.13.

v0.3.13 published stable diagnostic codes. v0.3.14 verifies that public reports actually use those codes.

## Public artifact

The new artifact is:

```text
diagnostic-coverage-report.json
```

It is validated by:

```text
schemas/diagnostic-coverage-report.schema.json
```

The report contains:

- `catalogCodes`,
- `observedCodes`,
- `unknownCodes`,
- `unusedCatalogCodes`,
- `status`.

`status` is:

- `PASS` when all observed diagnostic codes are present in the Standard Diagnostic Code Catalog,
- `FAIL` when a public report emits an unknown diagnostic code.

## What it observes

The lowering pipeline collects codes from:

- `intent-capability-validation-report.json`,
- `validation-report.json`,
- `execution-readiness-report.json`,
- `target-adapter-contract.json`,
- `adapter-diagnostics.json`.

This is intentionally conservative. The report does not reinterpret the original human/AI intent and does not make target decisions. It only checks diagnostic code stability.

## Why this belongs in core

Flow is a standardization layer. Stable public artifacts matter only if downstream tools can rely on their identifiers.

The diagnostic coverage report lets external consumers ask:

```text
Are all diagnostic codes emitted by this Flow run known by the standard?
```

That is a core standard question, not an adapter/runtime question.

## Boundary

This version does not add:

- new Flow syntax,
- target renderer behavior,
- SDK runtime behavior,
- plugin/module execution.

It only strengthens the public contract around diagnostics.
