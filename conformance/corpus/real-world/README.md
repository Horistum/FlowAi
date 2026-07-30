# Real-World Pipeline Corpus

This directory contains an immutable source catalog, a broad hypothesis catalog, a separate accepted-scenario index and executable case packages.

Run the full project conformance suite:

```bash
./gradlew run --args="conformance"
```

Run the focused tests:

```bash
./gradlew test \
  --tests org.flowlang.tests.RealWorldCorpusTests \
  --tests org.flowlang.tests.RealWorldCorpusSerializationTests \
  --tests org.flowlang.tests.DeclaredIntentOutputValidationTests \
  --tests org.flowlang.tests.ConformancePhaseBoundaryTests
```

## Acceptance rule

A case is executable evidence only when it contains immutable source provenance and SPDX notice, source observations, strict Canonical Intent YAML, expected plan semantics, target expectations, at least one negative mutation and accepted evidence matching the actual runner result.

Catalog entries without a case package remain hypotheses.

## Source classes

The manifest distinguishes production workflows, official examples and official semantic references. Those classes are not interchangeable:

- production workflows provide repository-specific accumulated behavior;
- official examples provide independent but intentionally bounded teaching cases;
- semantic references define or screen behavior but are not executable fixtures.

The current executable baseline has one production-workflow primary fixture and five official-example primary fixtures. Source-class counts are machine-checked and must remain visible in reports.

## Evidence separation

Source-authored dependency relations are checked independently from generated ExecutionPlan edges. Lowering-added ordering cannot certify that the original automation declared fan-in, continuity or serialization.

All corpus metadata is parsed strictly. Unknown fields and duplicate keys fail rather than disappearing into the traditional YAML swamp.

## Post-Core boundary

The runner constructs frozen pre-closure, closure, adapter and real-world phases separately. Semantic closure receives only the dedicated pre-closure collection.

Real-world checks run after adapter certification and appear in neither the frozen Core inventory nor the adapter inventory. A regression test locks this separation without weakening closure through prefix filtering.

The validator correction exercised by C06, A06 and A11 only aligns existing declared-output semantics across Intent lowering, AST validation and planning.
