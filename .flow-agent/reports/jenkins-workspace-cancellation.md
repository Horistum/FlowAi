# AR-06X: bounded Jenkins workspace cancellation

AR-06W certified two approval-then-checkout branches with failFast false.
AR-06X admits the same bounded bodies with failFast true and adds three external
runtime scenarios: successful join, rejected approval, and native checkout failure
while the sibling input remains undecided. Core contracts and public syntax do
not change. Directory identity still comes from preserved branch IDs, including
duplicate authored labels; every prior false-policy and gate-only proof remains.

## Native observations

The observer sees both inputs pending, decides the left input, and gives native
cancellation the existing bounded observation window. It records an undecided
cancelled right input distinctly from a right input explicitly approved after
the disabled-cancellation mutant. Neither observer decisions nor observations
depend on mutant identity. Generated artifacts contain no observation probes.

The failed-checkout scenario binds the missing-revision AbortException to its
native checkout node and checks that the terminal origin remains that operation.
The sibling's FailFastCause is a separate observation, never a substitute for
the original failure. The existing strict error/index and workspace protocols
are reused. Successful execution still requires approval before branch work,
distinct directory ownership, and the join before the root successor.

Sixteen new baseline/mutant runs falsify disabled cancellation, omitted or
suppressed checkout failure, swallowed rejection, aliased/swapped directories,
omitted checkout, premature branch work and premature continuation. Signed
observations bind exact source, graph, artifact, runtime and challenge. The
portfolio retains the bounded ARTIFACTS_WORKSPACES category; no generic support
or portable-execution claim is promoted.

## Limits and validation

Cancellation is certified only while the sibling is at its input, before its
checkout starts. This is not proof of interruption of an already-running Git
operation, a latency guarantee, rollback, arbitrary/nested branch work, human
authorization, filesystem security or fresh workspaces. As in AR-06W, the
fixture's first root clone removes preceding branch directories; no workspace
survival or export after the join is claimed.

Local validation passed 162 Python tests, Flow Agent validation/context generation
and whitespace checks before state selection. Offline Kotlin validation stopped
before compilation because the pinned Kotlin 2.4.10 plugin is unavailable in the
cache. Fresh exact-head and synthetic-merge tests/conformance, all native producers,
portfolio reassessment and independent artifact verification remain mandatory.
Final external receipts belong in the PR, without an untested receipt commit.

The immutable baseline records independently verified PR #220: 2135 tests / 370
suites and 274 ordered checks per revision, 27 assessments, 88 signatures, four
isolation proofs with 779 source digests, and 18 verified ZIP digests. Merged main
has the same tree; its runtime workflow passed and Flow CI was still running at
baseline observation. These remain predecessor observations, not candidate
receipts. Only AR-06A/B remain formally accepted; AR-06 is active, AR-07 planned,
EF-09 paused and F-20 AR-07-owned.
