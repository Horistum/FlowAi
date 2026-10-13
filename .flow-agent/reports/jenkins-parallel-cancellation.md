# AR-06V: bounded Jenkins parallel cancellation

The preceding parallel slice could demonstrate only the false failure policy. This
slice preserves either canonical failFast value for the same narrow two-input
shape. Core contracts and generic target support are unchanged.

## Behavior and evidence

The adapter still accepts one top-level parallel group containing exactly two
unbound manual approval gates with distinct messages. It renders the canonical
boolean literally. Duplicate authored labels retain separate wrapper identities.
All previously rejected branch bodies, bindings and malformed metadata remain
blocked.

Two new real-runtime scenarios exercise successful join and rejection with
failFast true. The external observer sees both inputs paused before deciding the
left input. For cancellation it waits for natural completion, recording the right
input's native FlowInterruptedException / ParallelStep$FailFastCause without
approving that input, and the original left rejection as the terminal origin.
A 30-second bound permits an intentionally disabled-cancellation mutant to be
observed and released. This is an evidence timeout, not a latency guarantee.
The observer does not choose behavior by mutant identity and does not instrument
the generated pipeline.

Exact-artifact mutants disable cancellation, swallow rejection, or execute the
successor prematurely. The disabled-cancellation mutant has the same final
ABORTED result as the baseline but differs in pending inputs, decisions and
native errors. The successful-join scenario also rejects premature continuation.
All false-policy scenarios remain live. The existing parallel producer publishes
all four scenarios to the source-bound, independently reauthenticated portfolio.

Jenkins documents the native failure policy at
<https://www.jenkins.io/doc/pipeline/steps/workflow-cps/>. The plugin's
ParallelStep failure handler creates the cancellation cause and propagates the
first failure. These sources guide the expected oracle; actual runtime evidence
is required independently and plugin versions remain pinned.

## Validation boundary

Local Flow Agent validation, context generation, 162 Python tests and whitespace
checks passed before release-state selection was updated. The pinned Kotlin
2.4.10 plugin is unavailable in the offline cache, so local Kotlin compilation
cannot supply a receipt. Fresh exact-head and synthetic-merge compilation,
tests, ordered conformance checks, all runtime producers and the portfolio are
required on this candidate. Their final receipts belong in the PR, not an
unvalidated follow-up commit.

PR #218's immutable baseline records 2117 tests and 274 checks per PR revision,
23 assessments and 72 authenticated observations. Actual main
3a7e2b33dd70b29ce7d7ce6316d68b53a126467c has the same tree and both workflows
passed. These are predecessor receipts, not current-revision validation.

## Limits

This proves cancellation of a pending native input only. Arbitrary branch work,
workspace/state/output isolation, nesting, general scheduling, real human
authorization and portable execution are outside scope. No public version,
generic support, accepted-slice inventory or finding closure changes. AR-06
remains active, AR-07 planned, EF-09 paused and F-20 AR-07-owned.
