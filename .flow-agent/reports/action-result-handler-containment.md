# AR-A01: Action result handler containment

Predecessor audit PR #242 merged at `19c8a802cce5d9be5bf40478dd513060ca0e5714`.
The maintainer authorized continuing with its findings; A-01 was the first bounded
correction recommended by the audit.

## Problem and change

Action-local `expect`, `when` and `when error` rules were parsed and validated but
omitted from planning. The audit demonstrated different handler meaning producing
identical canonical graphs and executable artifacts on both reference targets.

A shared compiler-owned admission rule now rejects every non-empty action result
handler with `ACTION_RESULT_HANDLER_UNSUPPORTED`. Validation retains source
locations and existing reference/type/safety diagnostics. Direct planning enforces
the same rule independently through `UnsupportedActionResultHandlerException`.
The common compiler cannot issue graph authorization for these inputs.

This is containment, not an implementation of handler execution. Empty handler
lists, ordinary result bindings, standalone result conditions and existing local
or workflow failure contracts retain their prior behavior. No adapter, schema,
package version, signed assessment or executable reference snapshot changes.

## Regression evidence

- Standalone conformance compiles seven handler forms, independently exercises
  direct planning and compares absent versus empty-handler plans and digests.
- A compiler-only test submits typed ASTs with a neutral module catalog and no
  frontend implementation, checking validation-stage rejection and direct planning.
- Source regressions cover 13 control/failure placements, including false branches,
  empty rule bodies, source locations and retained invalid-reference diagnostics.
- Positive controls authorize and render ordinary/empty-handler checkout on both
  existing executable targets; existing checkout-result and recovery suites remain.
- Historical source examples now have explicit rejection expectations. Separate
  projection fixtures preserve the original review-only renderer tests without
  deleting assertions from authored examples. Shell leaf rejection remains tested
  independently of the earlier handler admission failure. The live API-sync scenario
  matrix entry retains its source and is reclassified as negative compiler coverage.
- The relocated product proof keeps a supported Flow source positive control and
  rejects the packaged `hello.flow` assertion with no execution plan.

## Validation

Local Python tooling: 257 tests passed. Flow Agent structure/context validation,
changed YAML loading and Python verifier syntax passed. The compiler-only typed-AST
regression passed. A broader local run exercised 2,174 JVM tests and exposed five
fixture/inventory expectations affected by this correction; all affected suites
were repaired and rerun (32 tests across five suites, zero failures).

The unchanged audit probe was replayed against the corrected code: all three
handler cases reject during `FLOW_VALIDATION`, while both positive controls still
change their graph digests. Ordinary checkout artifacts retain their audited
Jenkins and GitHub Actions byte hashes. Other audit findings remain reproduced.

Standalone conformance from the installed distribution passed all 277 checks.
Exact-head, synthetic merge-candidate, module-isolation and native runtime results
belong to the final PR revision and are recorded in the PR after CI completes.

## Roadmap disposition and remaining work

A-01 is contained when the final revision's gates pass; complete canonical handler
support remains future work. A-02 through A-06 are unchanged. The next recommended
bounded correction is A-04: a deterministic availability-analysis work/state budget
with a reproducer and explicit rejection before exhausting memory.

AR-06 remains active, AR-07 planned, EF-09 paused and F-20 owned by AR-07. Historical
milestone receipts are not rewritten, and no target-wide support or portability
promotion follows from this compiler correction.
