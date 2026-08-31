# AR-01D Local Validation Evidence

Base revision: `b1edf195cedcabfa03963056d1cda3662f6c9da1`

This report records local verification performed before publishing the AR-01D implementation to the review branch. It is evidence only; GitHub Flow CI remains the authoritative exact-head and merge-candidate validation boundary.

## Verified implementation scope

- `CanonicalExecutionGraph` is the execution authority for both `ExecutionPlan` and `CanonicalExecutionPlan` compatibility views.
- Target projection, capability resolution and execution gates require `CompilationAuthorization` rather than detached raw-plan authority.
- The notes-backed execution-looking `SemanticActionGraph` production type is retired in favor of `ArchitectureObligationGraph`, whose role is explicitly evidence/governance only.
- Adapter `module.action` binding cannot synthesize canonical semantic capability or a substitute execution graph.
- Historical public Target Manifest `semanticGraph` metadata and stable `flow.semantic.<task>` evidence identifiers remain preserved for wire compatibility.

## Local JDK 25 verification

The exact published implementation content passed:

- production Kotlin compilation;
- test Kotlin compilation;
- complete Gradle test suite: **1,265 tests, 0 failures, 0 errors, 0 skipped** across 236 test classes;
- targeted AR-01D authority-retirement and compatibility regressions;
- standalone `conformance` execution;
- 31 Flow Agent Python unit tests;
- Flow Agent structure validation and context generation;
- patch whitespace validation / `git diff --check` equivalent;
- static audits for retired symbols, production canonicalizer callers and raw-plan authority bypasses.

## CI boundary

Implementation, independent validation and completion lifecycle boundaries remain pending until distinct GitHub Flow CI exact-head and synthetic merge-candidate runs succeed. The pull request must remain draft until those boundaries are recorded and a final evidence-bearing head is green.
