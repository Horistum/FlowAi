# Real-World Pipeline Corpus

This directory contains an immutable source catalog, a broad scenario catalog and executable case packages.

Run the full project conformance suite:

```bash
./gradlew run --args="conformance"
```

Run the focused tests:

```bash
./gradlew test \
  --tests org.flowlang.tests.RealWorldCorpusTests \
  --tests org.flowlang.tests.RealWorldCorpusSerializationTests \
  --tests org.flowlang.tests.DeclaredIntentOutputValidationTests
```

## Acceptance rule

A case is executable evidence only when it contains immutable source provenance and SPDX notice, source observations, strict Canonical Intent YAML, expected plan semantics, target expectations, at least one negative mutation and accepted evidence matching the actual runner result.

Catalog entries without a case package remain hypotheses.

## Evidence separation

Source-authored dependency relations are checked independently from generated ExecutionPlan edges. Lowering-added ordering cannot certify that the original automation declared fan-in, continuity or serialization.

All corpus metadata is parsed strictly. Unknown fields and duplicate keys fail rather than disappearing into the traditional YAML swamp.

## Post-Core boundary

The corpus checks run after Core closure and adapter stream checks. They cannot rewrite either frozen inventory. The validator correction exercised by C06, A06 and A11 only aligns existing declared-output semantics across Intent lowering, AST validation and planning.
