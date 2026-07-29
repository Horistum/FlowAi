# Real-World Pipeline Corpus

This directory contains an immutable source catalog, a broad scenario catalog and executable case packages.

Run the full project conformance suite:

```bash
./gradlew run --args="conformance"
```

Run the focused tests:

```bash
./gradlew test --tests org.flowlang.tests.RealWorldCorpusTests
```

## Acceptance rule

A case is executable evidence only when it contains immutable source provenance and SPDX notice, source observations, strict Canonical Intent YAML, expected plan semantics, target expectations, at least one negative mutation and accepted evidence matching the actual runner result.

Catalog entries without a case package remain hypotheses.

## Post-Core boundary

The corpus checks run after Core closure and adapter stream checks. They cannot rewrite either frozen inventory.
