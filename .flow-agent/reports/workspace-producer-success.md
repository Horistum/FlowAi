# AR-06AE-C16: Asserted workspace action errors

## Scope

PR #251 merged at `e7a9824ce7add45f5d58b25737a6581a3c3747d5`. This candidate retains success-only production scheduling and adds native checkout/upload error assertions, output-bound consumer guards and independent replay. The compiler derives seventeen baseline jobs, eleven workspace edges and five artifact identities; six mutants must be detected.

## Correction of the red workflow design

The prior design deliberately failed two whole jobs. Its observer succeeded, but GitHub correctly displayed the experiment as a failed workflow. That is not an acceptable positive CI contract. Job-level tolerance was previously tried and rejected because it changes downstream result semantics.

The corrected harness tolerates only each injected action, exports its original outcome, explicitly gates later production steps and requires an always-running failure assertion. Only the two fault consumer guards substitute this output for the production job result. The compiler validator pins every adaptation and rejects altered assertions, outcome/conclusion substitutions, tolerance changes and guard bypasses. Production workflows contain no such adaptations.

The scope is deliberately narrower: actual action errors, unavailable bytes and native output-bound expression evaluation are covered; untolerated failed-job scheduler propagation is not certified by this harness. That remains an explicit gap. The API's successful conclusion for a tolerated action is never presented as raw failure evidence. The pinned assertion and original outcome supply that evidence under the existing trusted-runner boundary.

## Independent validation

Both native producer harnesses and the standalone workflow must succeed. Original producer outcomes must be failure; baseline consumers must be skipped. Forced-consumer mutants must encounter and assert actual download and build failures. Missing, skipped, cancelled or unexpectedly successful faults fail the test. Independent replay regenerates all compiler inputs and authenticates same-attempt API jobs, assertion execution, outputs, guards and artifact identities.

Event-digest pairing, immutable attempt endpoints, artifact-ID pinning, attempt-specific archives, bounded polling and fail-fast manual selection remain intact. Both automatic and manual admission require successful workflow completion. No error is hidden at job or workflow level, and no write-enabled dispatcher is introduced.

Local validation runs 302 Python tests, including 18 independently runnable lifecycle tests, plus Flow Agent structure/context and diff checks. Existing JVM envelope mutation tests cover the new adaptations; production scheduling regressions are retained. Required JDK 25, exact-head, merge-candidate, native composition and independent replay checks must finish successfully for the final immutable head; results are recorded in the PR without a subsequent source commit.

## Limits

Untolerated failed-job scheduler propagation, cancellation and infrastructure experiments, retries, repeated-control transport, filesystem metadata and general VALUE/STATE continuity remain open. AR-06 stays active, AR-07 planned and EF-09 paused. No signed portfolio or target support promotion follows.
