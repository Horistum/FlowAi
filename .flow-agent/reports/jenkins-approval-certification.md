# AR-06J candidate: real Jenkins manual approval

This slice verifies a manual approval before one protected native checkout using
the existing compiler, canonical graph and unchanged Jenkins renderer. The same
Flow Source and generated artifact serve both test decisions.

The external observer finds a live Jenkins `InputAction`, records its message,
native `PauseAction` and pending execution state, native checkout count and workspace before submitting
an automated approve or reject decision. It then observes completed native input
and checkout nodes, input errors and terminal error origin. Approval must resume
the checkout once; rejection must abort at that input without executing checkout.

Five mutants omit the gate on either path, move it after checkout on either path,
or suppress rejection. Pre-decision observations distinguish premature execution
even when the final build result and marker match. Completed `ABORTED` is accepted
only for the rejection scenario with a native input rejection and matching origin;
unrelated cancellation or runtime failures cannot supply rejection evidence.

Provider approval definitions were previously absent from the certification leaf
inventory. Binding and admission now retain them alongside ordinary native leaves.
Existing scenarios explicitly list the input leaf as unobserved; the new scenarios
bind it to the actual compiler-rendered occurrence. Missing or borrowed approval
coverage fails admission. This changes bounded evidence coverage, not public support.

## Predecessor boundary

PR #206 merged as `761191c6ebadeef99544f3418d29899795588397`.
Both actual-main workflows passed: Flow CI 37960137752 and runtime CI
37960137918. Eight independently downloaded archives match GitHub SHA-256
metadata and contain 1,963 tests in 339 suites, no failures/errors/skips, the
unchanged ordered 274-check conformance inventory, 769 verified isolation input
hashes and 25 verified Ed25519 observations across eight scenarios. The test
inventory matches PR #206 head and synthetic-merge validation.

The immutable receipt is `.flow-agent/evidence/jenkins-approval-baseline.json`
(SHA-256 `b2dc9f7a63755c9960bc71f4a8b4a2f136ca7b918c06ad4969ff1d14c31348ba`).

The existing lifecycle gate selects AR-06J and replays all historical receipts.
The ordered 274-check conformance inventory is preserved. Candidate runtime and
exact-head/merge validation evidence belongs in this PR, not its own source receipt.

## Validation

Local validation on JDK 25 and Gradle 9.5.0 passed 110 targeted tests in
17 suites, including all 13 additions, with zero failures, errors or skips.
The tests cover all runtime protocols, provider inventories, compiler binding,
authenticated evidence views and historical lifecycle receipts.

The installed standalone conformance program passed all 274 checks with names
and order preserved. All 157 tooling tests and structure/context validation passed.

The published revision requires exact-head and synthetic-merge compilation/tests,
standalone conformance, physical isolation and all six real Jenkins runtime jobs.
The new job executes seven builds. Synthetic protocol test records are not runtime
evidence.

## Limits

The controller submits test decisions; no human identity, role, submitter permission,
timeout, restart, concurrent approval or general change-control policy is certified.
The protected operation is one sequential native checkout. General approval scope
binding, public target support and portable execution remain unclaimed. The input
plugin is pinned to the version already observed on the predecessor runtime.
Core semantics, renderer behavior, package versions and historical acceptance do
not change. AR-06 remains active, AR-07 planned and EF-09 paused.
