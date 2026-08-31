# AR-01D Local Validation Evidence

Base revision: `b1edf195cedcabfa03963056d1cda3662f6c9da1`

This report records local verification performed before and during publication of the AR-01D implementation. It is evidence only; GitHub Flow CI remains the authoritative exact-head and merge-candidate validation boundary.

## Verified implementation scope

- `CanonicalExecutionGraph` is the execution authority for both `ExecutionPlan` and `CanonicalExecutionPlan` compatibility views.
- Target projection, capability resolution and execution gates require `CompilationAuthorization` rather than detached raw-plan authority.
- The notes-backed execution-looking `SemanticActionGraph` production type is retired in favor of `ArchitectureObligationGraph`, whose role is explicitly evidence/governance only.
- Adapter `module.action` binding cannot synthesize canonical semantic capability or a substitute execution graph.
- Historical public Target Manifest `semanticGraph` metadata and stable `flow.semantic.<task>` evidence identifiers remain preserved for wire compatibility.
- The serialization-free semantic package inventory was migrated from retired package `semantic` to `obligations` in both its canonical package authority and production conformance copy. This was exposed by a clean synthetic merge-candidate checkout after the local worktree's empty retired directory had masked the missing-package condition.

## Local JDK 25 verification

The implementation content passed:

- production Kotlin compilation;
- test Kotlin compilation;
- complete Gradle test suite before publication: **1,265 tests, 0 failures, 0 errors, 0 skipped** across 236 test classes;
- targeted AR-01D authority-retirement and compatibility regressions;
- targeted clean-checkout package-boundary tests after the inventory correction (`PackageLayeringIntegrityTests` and `SemanticCoreSerializationBoundaryTests`);
- standalone `conformance` execution before publication;
- 31 Flow Agent Python unit tests;
- Flow Agent structure validation and context generation;
- patch whitespace validation / `git diff --check` equivalent;
- static audits for retired symbols, production canonicalizer callers and raw-plan authority bypasses.

The clean-checkout inventory correction is not considered complete on local evidence alone; the following GitHub Flow CI exact-head and synthetic merge-candidate run is the blocking verification boundary.

## CI boundary

Implementation, independent validation and completion lifecycle boundaries remain pending until distinct GitHub Flow CI exact-head and synthetic merge-candidate runs succeed. The pull request must remain draft until those boundaries are recorded and a final evidence-bearing head is green.
