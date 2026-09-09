# CI cost policy

The optimization target is aggregate runner minutes, not just elapsed time.
Running duplicate builds in parallel does not reduce this cost.

## Automatic validation

| Event | Full validation jobs |
| --- | --- |
| Push to a development branch without a PR | None; local checks or explicit manual validation |
| Draft PR opened or updated | None; a skipped job is not validation evidence |
| PR marked ready for review | Exact HEAD and synthetic merge candidate |
| New commit in a ready PR, or ready PR reopened | Exact HEAD and synthetic merge candidate |
| Push to main, including a merge | Exact pushed revision |
| Manual Flow CI dispatch | Exact selected revision, not an inferred PR merge candidate |

Both final PR checks retain their original job identities:
`compile-test-conformance` and `merge-candidate-compile-test-conformance`.
Each verifies the actual Git checkout, runs the Python tooling tests, validates
and generates the agent context, executes the complete Kotlin test suite,
proves kernel isolation without product sources, proves the compiler and contracts
without frontends or concrete adapters, proves generic adapter materialization
and evidence without concrete/reference sources, and runs standalone conformance.
There are no source-path filters: documentation and lifecycle metadata remain
inputs to repository-aware tests.

Use a draft PR during iterative development. Mark it ready only after local
validation; the `ready_for_review` event then starts both final checks. Returning
to draft cancels an older in-progress run. Further pushes to a ready PR still
require fresh validation of both revisions. Do not use `[skip ci]` as a substitute
for this lifecycle or treat a draft's skipped checks as completed validation.

Push-triggered builds run only on main. A development-branch push therefore does
not race a second build of the same code through the PR event. Workflow-level
concurrency cancels superseded runs per PR/ref, without grouping unrelated PRs.
Cheap Python checks run before Java/Gradle setup so a rejected structure does
not pay for downloading and initializing the toolchain.

## Caching without substituting test evidence

Normal CI enables the Gradle build cache for compilation and retains `clean test`.
The existing `setup-gradle` action manages Gradle user-home state; there is no
second overlapping cache mechanism. Same-repository PR runs can save cache state
for later revisions of that PR. Fork PRs use this action in read-only mode.

All root and module test tasks explicitly disable output reuse and test-result
caching. Their tests execute on every validated revision even when Kotlin
compilation is restored from cache. Source-ownership and production-classpath
guards remain in the task graph. The separate kernel-isolation script still
performs its uncached offline proof. The compiler-isolation proof compiles the
kernel dependency but runs only the compiler and module-contract suites, avoiding
a third execution of the kernel suite. The adapter proof compiles its actual
product dependencies but executes only catalog/runtime/evidence suites, not all
frontend, kernel and concrete suites again. Standalone conformance is not replaced by
a cached success receipt.

A cold cache must remain correct. Warm-cache acceleration is an additional
benefit, not a prerequisite and not a promised five-minute build time.

## Explicit offline portability proof

`Flow Offline Proof` has only `workflow_dispatch`. It has no PR, push, or scheduled
trigger and allocates no runners during the normal PR workflow. One dispatch
validates one exact revision, not two jobs with overlapping claims.

Run it deliberately for release/offline-delivery evidence or after a relevant
wrapper, toolchain, dependency, or module-layout change. In Actions, select
`Flow Offline Proof`, choose the branch/tag carrying the workflow, and optionally
provide a full lowercase 40-character commit SHA in `revision`. Leaving that
input empty uses the event's immutable SHA for the selected branch/tag. The
checkout is compared with the requested SHA before any build. An explicit SHA
can identify a PR head or a synthetic merge commit; this does not automatically
validate the other one.

The existing shell entry point is unchanged:

```bash
bash tools/offline_gradle_build.sh prepare
bash tools/offline_gradle_build.sh verify
```

Preparation resolves the complete dependency closure with a clean online
build, tests and conformance. Verification relocates those inputs and repeats
the clean build with `--offline --no-build-cache`. The phases keep independent
20-minute limits and a 45-minute combined safety ceiling. These are timeout
ceilings for an explicitly requested proof, not expected durations or a normal
PR budget. Gradle offline mode is dependency resolution from cache, not a
network-isolation guarantee for arbitrary processes.

Local offline development remains available through the same script. Local
validation is the preferred place for iterative failures before publication.

## Budgets and evidence

Normal validation jobs have a 20-minute ceiling each. Failure logs, all module
JUnit reports and isolation evidence are still uploaded after failures. Canceled,
superseded runs do not upload obsolete reports; retained artifacts expire after
seven days. Artifact retention saves storage, not build minutes.

Before this change, a build-affecting PR could execute two normal builds and
four additional full builds through the two-phase, two-revision offline workflow.
The normal ready-PR path now executes only the two required builds and retains
both kernel-isolation checks. This removes four full build/test/conformance
invocations from that automatic path before any warm-cache savings. Non-build
PRs did not necessarily incur the previous offline runs; savings vary by event.

Measure cost as the sum of job durations, not the longest concurrent job.
Report cold- and warm-cache results separately; do not imply that a timeout
reduction or increased parallelism is a measured runner-minute saving.

The new workflow policy does not edit repository protection or rulesets. The two
normal checks remain the final merge checks. A manually requested offline proof
must not be configured as an unconditional required status for every PR.
Historical four-job implementation receipts remain historical evidence for their
recorded SHAs, not assertions about later optimized commits. Record current
validation against the actual current HEAD and merge SHA in the PR.

Regression coverage in `tools/tests/test_ci_cost_policy.py` and
`tools/tests/test_offline_workflow.py` checks scheduling, draft transitions,
cache/test boundaries, revision guards, phase budgets and failure propagation.
Shell fixtures test orchestration; complete compilation and conformance are
validated by the normal CI jobs.
