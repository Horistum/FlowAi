# Availability analysis limits

Horistum bounds the compiler's path-sensitive availability analysis before it can
exhaust resources by enumerating combinations of workflow branches. Source byte,
token and nesting limits alone do not bound this analysis: audit A-04 reproduced
an out-of-memory failure from 20 shallow conditions in a 557-byte source file.

## Admission contract

`FlowAvailabilityLimits` belongs to the target-neutral compiler and applies to
parsed source, typed ASTs, lowered Intent and reviewed AI proposals. It has no
adapter dependency and does not depend on JVM heap size or elapsed time.

| Dimension | Limit | Accounting |
| --- | ---: | --- |
| Paths | 4,096 | Distinct paths in a state or join; overlapping logical alternatives are counted once. |
| Work | 1,000,000 units | Cumulative AST visits, state/path/binding/provenance entries scanned or copied, join and merge comparisons, and retained diagnostic characters. |
| Generated path text | 8,000,000 characters | Cumulative child path identity lengths, charged before strings and remapped states are constructed. |
| Depth | 128 | Nested analysis visits, including document, statements, conditions and expressions. |

Limits are inclusive. Work and generated-text counters are cumulative even when
objects are shared or an intermediate state becomes unreachable. They are
conservative accounting units, not an exact heap-byte or time guarantee. The
first exhausted dimension determines rejection. Source/parser limits still apply
independently and may reject an input earlier.

Each public analysis starts with a fresh ledger. A compilation containing multiple
Intent workflows shares one ledger across those workflows; dividing work among
workflow declarations cannot reset it. A later independent compilation receives a
fresh ledger. There is no CLI or environment-variable bypass.

## Rejection and migration

Exhaustion produces `FLOW_AVAILABILITY_LIMIT_EXCEEDED`, identifies the exhausted
dimension, limit, consumed/requested amount and structural statement path, and
retains a source location when the AST provides one.

- The common compiler returns a `FLOW_VALIDATION` rejection with its inspectable
  AST and invalid validation report. It issues no accepted compilation, graph
  authorization or execution plan, including when earlier workflows were analyzed.
- Direct `FlowValidator.validate` returns an invalid report with the same code.
- Direct `FlowAvailabilityAnalyzer.analyze` and `FlowPlanner.plan` throw
  `FlowAvailabilityLimitException`; no partial analysis product is returned.
- Analysis stops immediately. Other validation diagnostics are not promised after
  budget exhaustion; treating an incomplete analysis as valid is forbidden.

Inputs exceeding these budgets must simplify branching or be split into genuinely
independent compilations. Increasing the heap does not alter admission. Even empty
branches contribute path evidence in the current representation. Conditions are
not constant-folded merely to evade the limit.

Within the budgets, producer identities, path-sensitive definite/maybe values,
existence guards, explicit merges and concurrent-path restrictions retain their
existing meaning. Public AST/plan schemas, adapters and support claims are unchanged.

## Scope and follow-up

This correction contains A-04's unbounded analysis. It does not replace explicit
path enumeration with symbolic states or merge equivalent paths. Such optimization
needs separate semantic parity evidence: careless coalescing can admit undefined
values or erase explicit-merge provenance. A-04's scalability follow-up therefore
remains open rather than being reported as full closure.

Regression coverage includes the original byte-identical audit reproducer in a
256 MiB child JVM, inclusive budget boundaries, each path-expanding construct,
wide bindings and branches, deep typed ASTs, shared multi-workflow accounting,
compiler/direct-API rejection and positive path-sensitive joins.
