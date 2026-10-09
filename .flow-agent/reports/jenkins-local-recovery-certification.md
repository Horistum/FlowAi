# AR-06I candidate: real Jenkins local recovery

Local `try` / `on error` differs from the workflow-level propagation boundary
verified by AR-06H. This slice certifies recovery and continuation using existing
Core semantics, canonical occurrence capture and the unchanged Jenkins renderer.

Two Flow Source fixtures compile through the production pipeline. The failing
body must stop at its missing-revision checkout, execute its handler once and
continue after the local boundary. The successful body skips the handler and
continues. Original generated scripts run unchanged on disposable real Jenkins.

Five mutants omit the handler, rethrow the caught error, omit continuation on
either path or run the handler unconditionally. Native checkout counts distinguish
omissions even when final build status and workspace marker remain identical.
Caught-error positions prevent an unrelated operation's failure from replacing
the expected body failure. Terminal error identity and origin distinguish the
intentional rethrow from infrastructure failure.

The observer reads the completed Jenkins execution graph outside the pipeline.
The host signs source/graph/artifact/observation bindings using the existing
runner trust and challenge protocol. Separate failure/success proofs and evidence
views retain bounded `ERROR_BOUNDARY` coverage. The new Gradle task and CI job
execute seven builds; ordinary unit tests exercise the protocol without Docker.

## Predecessor boundary

PR #205 merged as `ffcbf16b27f6ccd176038a13927638c435499a88`.
Both actual-main workflows passed: Flow CI 37944211606 and runtime CI
37944211585. Seven independently downloaded archives match GitHub SHA-256
metadata and contain 1,951 tests in 337 suites, no failures/errors/skips, the
unchanged ordered 274-check conformance inventory, 769 verified isolation input
hashes and eighteen verified Ed25519 observations across six scenarios. The test
inventory matches PR #205 head and synthetic-merge validation.

The immutable receipt is `.flow-agent/evidence/jenkins-local-recovery-baseline.json`
(SHA-256 `691b289deb07f766f0a247a015f5117e42f3731fb4b51e2de36aca3e500b2b8c`).

The existing lifecycle gate selects AR-06I and replays all earlier receipts.
The conformance inventory remains unchanged. Candidate execution evidence belongs
in this PR and does not retroactively validate its source baseline.

## Validation

Local validation on JDK 25 and Gradle 9.5.0 passed 96 targeted tests in 14
suites, including all 12 additions, with zero failures, errors or skips. These
cover runtime protocols, compiler binding, authenticated evidence views and the
historical lifecycle chain. All 157 tooling tests and structure/context checks
also passed.

The installed standalone conformance distribution passed all 274 checks with
predecessor identities and order preserved.

Exact-head and synthetic-merge compilation/tests, standalone conformance,
physical isolation and all five real Jenkins jobs must pass on the published
revision. Synthetic protocol records do not constitute runtime evidence.

## Limits

This is a local recovery occurrence with literal native Git checkout operations
on the recorded runtime. Nested handlers, cancellation, retries, error-value
binding and general exception semantics remain outside the claim. Public target
support, general continuity, portable execution and package versions do not
change. AR-06 findings remain open, AR-07 stays planned and EF-09 stays paused.
