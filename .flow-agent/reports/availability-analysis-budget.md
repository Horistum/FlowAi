# AR-A04: Availability analysis budget

Predecessor PR #243 merged at `0b308420610fdcefda30cf0df44cdd59bf3ad315`.
The maintainer requested continuation according to the roadmap. The merged audit
identified A-04 as the next correction after action-handler containment.

## Problem and implementation

Path-sensitive analysis explicitly enumerates branch combinations. Twenty shallow
conditions in a 557-byte source exhausted a 256 MiB JVM in the audit. Input byte and
parser nesting limits cannot bound that growth.

The compiler now owns a deterministic ledger for distinct state paths, cumulative
analysis work, generated path text and recursive visits. State mutations, remapping,
joins, expression traversal, merge comparisons and retained diagnostic text consume
the ledger. Charges precede expansion or retention; counters use overflow-safe
comparisons. Multiple Intent workflows share one compilation ledger.

Exhaustion throws `FlowAvailabilityLimitException` before an analysis product can
be returned. The common compiler translates it into a `FLOW_VALIDATION` rejection
with `FLOW_AVAILABILITY_LIMIT_EXCEEDED`, the original AST and an invalid validation
report. Direct validation reports the same error; direct planning independently
fails through the shared analyzer. No partial plan or authorization is issued.

The analyzer remains stateless between requests. Accepted path identities, joins,
producer provenance and graph meaning are unchanged. No adapter, public schema,
package version or signed certification assessment changes.

## Regression evidence

- Compiler-only tests cover inclusive path/work/text/depth limits, overflow,
  cumulative binding work, wide branch names, deep typed statements/expressions,
  direct analyzer/validator/planner/compiler rejection and reuse after rejection.
- Multi-workflow Intent coverage admits one workflow and rejects combined work at
  the availability boundary, proving that workflow declarations do not reset the
  budget. The initial fixture reused output names across workflows and correctly
  failed earlier Intent validation; the final fixture uses distinct identities.
- Integration coverage retains exact 16/256/4,096 path counts for the audit's
  4/8/12-condition positive inputs and rejects growth through conditions, loops,
  matches, try/error and parallel branches.
- A child JVM with `-Xmx256m` runs the byte-identical 557-byte audit input. The test
  requires explicit limit rejection and successful process termination, with a
  45-second outer guard against a returned unbounded implementation.
- Standalone conformance exercises source rejection, direct planning and an
  accepted explicit merge paired with a rejected maybe-defined read.

## Validation

Python tooling: 257 tests passed. Compiler-only budget regressions: 9 tests passed.
All 62 selected integration and path-sensitive semantic regressions passed, including
the byte-identical audit reproducer in the 256 MiB child JVM.
Installed-distribution standalone conformance passed all 280 checks.
Exact-head, synthetic merge-candidate, module-isolation and native certification
results will be recorded in the PR after the final revision's CI completes.

## Roadmap disposition

A-01 containment is merged. A-04 resource exhaustion is contained by this candidate;
compact or equivalent-state analysis remains an explicit A-04 follow-up requiring
branch/merge parity evidence. A-02, A-03, A-05 and A-06 remain open.

AR-06 stays active, AR-07 planned, EF-09 paused and F-20 AR-07-owned. Historical
completion evidence is preserved. This change does not promote any target support
or portable execution claim. See [the admission contract](../../docs/AVAILABILITY_ANALYSIS_LIMITS.md).
