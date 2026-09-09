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
and generates the agent context, executes the complete Kotlin test suite, and
runs standalone conformance. Both also require successful physical isolation
as a fail-closed prerequisite, rather than repeating it inside their job budget.

A cheap `isolation-candidates` job verifies the immutable event checkout and, for
PRs, the ordered base/head parents of the synthetic merge. It compares **complete
Git tree identities**, including file modes and metadata, not a changed-path
heuristic. Equal HEAD/merge trees select one `module-isolation` matrix entry;
different trees select two. Push/manual events select exactly their event commit.
The selection is a plan, not a successful verification receipt.

Each selected tree then runs all three existing proofs: kernel without product
sources, compiler/contracts without frontends or adapters, and generic adapter
materialization/evidence without concrete/reference sources. Each still copies
actual inputs to its own empty directory, builds without compiled-output cache,
runs its actual tests and compiler probes, and emits input SHA-256 fingerprints.
These copies contain neither `.git` nor prior outputs. Sharing this file-based
proof for byte-identical complete trees therefore does not share Git-sensitive
root tests. **HEAD and merge still run their own complete tests and conformance.**
There is no sharing across runs or commits with different trees and no reuse of
an old success marker.

Both original required jobs explicitly inspect the selection and proof job
results before checkout or toolchain setup. A failed or skipped prerequisite
fails those required checks instead of silently turning them into successful
skips. Obsolete workflow cancellation still cancels downstream work.
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
guards remain in the task graph. The source-isolation scripts still
perform uncached clean proofs. The independent CI proof job permits dependency
resolution so a cold cache remains supported; this does not enable compiled
output reuse. Local `--offline` remains supported and the distinct relocated
cache portability workflow remains manual-only. The compiler-isolation proof compiles the
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

Full-validation jobs retain a 20-minute ceiling each. The shared isolation job
has its own 20-minute ceiling and the cheap selector has a three-minute limit.
These are separate bounded responsibilities, not a longer limit for one
repeated-build job. Failure logs and all module JUnit reports are uploaded by
the original full-validation jobs; `flow-isolation-<label>` artifacts own the
three proof reports, copied-input fingerprints, logs and exact checkout/tree
identity. Isolation artifacts use the same seven-day retention and are uploaded
after failures. Canceled, superseded runs do not upload obsolete reports; retained artifacts expire after
seven days. Artifact retention saves storage, not build minutes.

The earlier automatic relocated-offline workflow could add four complete
build/test/conformance invocations to a build-affecting PR. That workflow was
already made manual-only; the current correction does not claim that saving
again.

The canceled AR-03C layout placed two full builds plus six physical-isolation
invocations on the automatic path. Deduplication now reduces equal HEAD/merge
trees to two full builds plus three physical-isolation invocations. Different
trees intentionally retain six proofs and pay the small extra job setup cost.
The reduction is in repeated compilation work, not fewer test identities or a
shorter timeout. Actual wall time and runner-minute savings still depend on
runner performance and compilation-cache hits.

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
`tools/tests/test_ci_isolation_candidates.py` also exercises real Git commits:
equal/different trees, mode-only and metadata changes, two-generation shallow
clones, malformed/reordered parents, dirty tracked inputs, replacement objects,
invalid identifiers and output publication only after successful selection.
Shell fixtures test orchestration; complete compilation and conformance are
validated by the normal CI jobs.
