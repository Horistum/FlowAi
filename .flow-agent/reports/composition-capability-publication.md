# AR-06AE-C8 — Reference maturity and composition publication

Based on merged PR #235, `55ab3204cfe7fcada06ab5ccf5f5d8c240e6252c`.

## Defect and correction

`AdapterTargetMaturityPublisher` previously promoted reference snapshots to
`BEHAVIORALLY_CERTIFIED` when implementation/test paths existed. It did not
execute those tests or authenticate runtime observations. That report was
published by both CLI hosts and the installed distribution. A reference marked
executable could also retain `EXECUTABLE` despite invalid scope evidence.

Report v2 publishes repository reference maturity and provider declarations.
`REFERENCE_EXECUTABLE` requires a fully valid reference scope; explicit metadata
states that runtime certification is not assessed and execution is not
authorized. Existing unrelated test files cannot manufacture a runtime claim.
The registry section is explicitly labeled as declarations. Structural mappings
are read directly from the composed provider catalog, preserving `PLAN_SCOPED`
versus `TARGET_WIDE`; missing providers publish no mappings. No duplicate support
catalog or target-name eligibility matcher is introduced.

The evidence inventory's limitations now describe reference scope and independent
composition gates. Its input schema stays at v1; the public report moves to v2
with a documented consumer migration. No Core or adapter execution behavior changes.

## Composition audit against remaining AR-06 outcomes

| Boundary | Current corrected contract | Remaining evidence or implementation boundary |
| --- | --- | --- |
| Producer values (C1) | Native Jenkins checkout result fields follow lexical producer availability; unused aliases do not restrict eligibility. | General output mappings, exports from child scopes and cross-job value transport remain separate work. |
| Boolean conditions (C2–C3) | GitHub Boolean expressions compose across native leaves, nested branches and ordering successors; the same bounded canonical compositions are observed on Jenkins and GitHub. | Input-dependent GitHub guards require a manual trigger and usable Boolean inputs. Output predicates, other controls and general portability are not supplied by these observations. |
| Retry (C4) | Jenkins fixed zero-delay retries re-enter sequential tasks, conditions and nested retries; leaf eligibility remains independent. | Nonzero delay, other control interactions, cancellation/recovery and general state isolation need separate contracts and evidence. |
| Loop (C5) | Finite literal string iteration preserves order and multiplicity with sequential tasks, conditions, nested loops and distinct lexical item bindings. | Dynamic collections, value exports and other control interactions remain outside this contract. |
| Match (C6) | Inert literal string sources and exclusive literal Boolean selection compose with sequential tasks, conditions and nested matches. | Dynamic matching, error branches, branch exports and other control interactions need independent evidence. |
| Parallel (C7) | Top-level groups use preserved branch identities, branch-local sequential dependencies, native input routing and workspace directories. Empty branches remain structural. | Nested controls, value/state transfer, shared-service isolation and checkout modes beyond the independently supported mapping remain outside this contract. |
| Publication (C8) | Repository references and composed structural scopes are visible without a runtime claim. | Authenticated coverage still derives from the existing signed portfolio; newer composition observations must be independently admitted before they can fill its gaps. |

Authenticated target coverage already distinguishes declared capabilities,
provider availability, bounded native leaves, bounded scenarios and missing
behavioral evidence. Its trust boundary is `AdapterCertificationPortfolio`, not
the repository maturity inventory. The existing 39 assessments and 132 signatures
are preserved. Newer composition jobs do not silently expand that portfolio.

AR-06 remains active: complete provider-owned behavioral coverage, broader
control/value/state interactions and bounded cross-runtime equivalence must be
assessed against the whole milestone. Scenario counts and green CI do not close
it. AR-07 remains planned, EF-09 paused and F-20 owned by AR-07.

## Validation and unchanged evidence

Regression coverage checks both real CLI hosts, installed contract resources,
unrelated existing test paths, missing and invalid scope evidence, provider
removal and changed structural support scopes. Existing installed-distribution
coverage retains the distinction between an archived executable GitHub reference
and current `REVIEW_ONLY` rendering.

The four existing maturity conformance checks now enforce reference-only
publication and provider-derived structural scopes. Their identities and the
274-check inventory are unchanged; the old requirement to publish an unsupported
certification label is corrected, not treated as authenticated evidence.
Historical acceptance receipts and native certification machinery are unchanged.

Local validation: all 211 Python tooling tests and Flow Agent structure validation
passed. Validation status: current-revision CI required. Exact-head and synthetic merge
compilation, tests, standalone conformance, module isolation and the complete
native certification workflow remain required before acceptance. Local JVM
validation is unavailable because this environment lacks the pinned JDK/Gradle
toolchain; no previous run is presented as validation of this revision.
