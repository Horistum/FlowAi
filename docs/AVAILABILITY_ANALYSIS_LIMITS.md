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
independent compilations. Increasing the heap does not alter admission. Conditions
are not constant-folded merely to evade the limit.

## Availability-neutral joins

After analyzing a complete `if`, loop zero/body, `match` or `try`/error split, the
analyzer compares every alternative's exit with its own entry. Temporary loop-item
and error bindings are restored before comparison. If every alternative preserves
its bindings, producer and external coverage, types, merge status and reachability
exactly, the join reuses the original pre-split state. This also preserves partial
coverage across `exists` and short-circuit guards: the original state remains
maybe-defined outside the guard.

All conditions and branch bodies are still analyzed and charged. Diagnostics,
statement entry/exit snapshots and authored plan control nodes remain present.
This is an availability optimization, not constant folding or removal of runtime
control. Twenty empty conditions from the original 557-byte audit reproducer now
finish with one path. Repeated neutral statements and nested neutral alternatives
also avoid multiplying path identities.

A changed binding or reachability uses the existing explicit join. Equal values
from different producers are not equivalent. Parallel joins always retain their
non-mergeable path identities. Neutral exclusive control surrounding concurrent
paths preserves that evidence. This conservative rule does not merge arbitrary
states with different historical paths or producer combinations.

The equivalence scans consume the same cumulative work budget. All four limits
remain unchanged; temporary alternatives are still subject to them. Reduced path
evidence can change canonical graph digests for newly compiled sources. Previously
issued authorization is not reused; graph integrity is checked on the new result.

Within the budgets, producer identities, path-sensitive definite/maybe values,
existence guards, explicit merges and concurrent-path restrictions retain their
existing meaning. Public AST/plan schemas, adapters and support claims are unchanged.

## Scope and follow-up

A-04 now has deterministic resource containment and reduction of availability-neutral
splits. General compaction of distinct producer combinations remains open. There
is no claim of polynomial analysis for every workflow or unrestricted symbolic
execution. Limits still reject non-equivalent exponential growth without returning
a partial accepted result.

Regression coverage includes the original byte-identical audit reproducer in a
256 MiB child JVM, inclusive budget boundaries, changed-state growth through each
control construct, wide bindings and branches, deep typed ASTs, shared multi-workflow accounting,
compiler/direct-API rejection and positive path-sensitive joins.
