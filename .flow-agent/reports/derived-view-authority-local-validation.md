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

## GitHub Flow CI boundaries

| Boundary | Exact head | Synthetic merge candidate | Flow CI | Result |
|---|---|---|---:|---|
| Implementation | `bb2a5254cc6e41430e7570e91bc30687521d7e85` | `affca90d9cc3039de59a0d0649814ac5d9d8e4d6` | #3145 | PASS |
| Validation | `d9e48a5bbdd8e86bf7a804a8c78b02fe1ef8e62c` | `47bdd893afde9df826121ab33fa9659a0d761eb7` | #3146 | PASS |
| Completion | `4bbd9437c30d1fc44bfe7a1e2ebd2f17f4ab1fcf` | `ecbaf9f71ecaa5fc65d1049751894264a0cba974` | #3147 | PASS |

Each lifecycle boundary used a distinct exact head and a distinct synthetic merge candidate. Both required Flow CI jobs passed at every accepted boundary. The completion boundary therefore closes AR-01D without reusing implementation or validation evidence.

The final roadmap/evidence closure commit is intentionally later than the lifecycle completion boundary. It contains no new production semantics and must itself pass exact-head and synthetic merge-candidate Flow CI before PR #165 is marked ready for review.
