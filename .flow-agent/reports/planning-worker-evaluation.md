# AI-A3-S3.5: worker evaluation and lifecycle correction

Merged baseline: PR #272, `b32c51dfe374899a13a1a03fd107656363faecab`.
Review correction: PR #273, initially `bb641c42927dfbe485026af1e6e3b20c90fd3d52`.

## Purpose and ownership

The legacy evaluator bypasses worker observations, retained candidate selection
and independent review. The portfolio runner connects those existing boundaries
for at least three repetitions, with private expectations, frozen exports and
explicit total turn accounting. Compiler acceptance, independent requirement
satisfaction and incomplete evidence are reported separately.

The compiler remains the sole semantic evaluator. This change adds no model
transport, scenario-to-plan production mappings, public Intent semantics,
rendering or execution authority. The original twelve-case corpus is unchanged.

## Findings confirmed during review

The initial revision passed CI, but several lifecycle transitions were not covered.
New regressions run against that revision produced eight failing assertions and
one subprocess timeout. These demonstrate the following defects rather than
assuming that an existing green suite establishes the declared invariant.

| Finding | Reproduced behavior | Correction |
| --- | --- | --- |
| Invalid review counted as completion | An independent compiler input error in the last trial returned `complete` and counted every trial as completed. | Exit 2 is invalid measurement evidence, excluded from completed outcomes and retained with an explicit diagnostic. |
| Reservation count preceded durability | A failed reservation write reported one reserved turn while the journal contained zero. | Reservation publication does not mutate an in-memory authority; reports and dispatch decisions reread the journal. An error after replacement preserves the spent slot. |
| Summary ignored changes during aggregation | An unread export changed while collecting trial rows, yet a complete report was published. | Revalidate the complete frozen experiment and journal after aggregation and before publication. |
| Verification could create a new experiment | Clearing retained state before summary acquired its lock caused it to prepare new snapshots and a new plan. | Separate preparation from read-only plan verification; recheck summary admission under the lock. |
| Metrics escaped their verified snapshot | A draft result changed after handoff recovery; statistics consumed the substituted bytes. | Every metric input must match the recovered artifact digest, and the independent review must refer to the same planning receipt. |
| Review lacked a selected-graph anchor | Mutating both independent graph digests to the same different value was accepted; missing/substituted canonical-plan artifacts were accepted too. | Require the original selected graph identity and exact canonical-plan bytes. Compare artifacts, without recreating graph semantics in Python. |
| Completed compiler review could not finish | A crash after compiler completion but before publishing `review.json` permanently blocked normal resume. | Retain the compiler exit status and full output inventory; normal resume can finalize that unchanged evidence without compiler or model I/O. Incomplete evidence remains blocked. |
| Nonregular nested evidence could hang recovery | Replacing a selected proposal with a FIFO reached a blocking reader during summary. | All retained planning/review/evaluation JSON uses one bounded, nonblocking, no-follow reader. The public CLI regression has an independent subprocess timeout. |

Earlier tests corrupted final metadata, failed before reservation publication,
changed exports before aggregation, or interrupted after a finished review. They
missed failures inside those boundaries. One existing test even expected invalid
review input to count as a completed outcome; that expectation is corrected.
The scripted compiler now emits the canonical plan for independently reviewed
accepted graphs, matching the installed compiler's actual artifact contract.

A further regression protects read-only recovery when a completed review disappears
during assessment. Only explicit normal completion may republish it. Summary mode
never finalizes a missing review, including this race.

## Resulting lifecycle

Preparation validates the portfolio and all cross-case expectation isolation,
freezes bounded exports and requirements, and completes worker/catalog checks
before the first new reservation of an invocation. Each dispatch uses one durable slot. No uncertain model
turn is replayed and no failed measurement is silently replaced.

The trial retains the selected handoff, independent inputs, complete compiler
output inventory and actual process exit status. Recovery checks these against the
same original proposal, graph, configuration, observations and authority. A complete
compiler receipt permits publishing a missing final review. Missing, changed or
partial compiler evidence does not permit another process. Summary reconstructs
its rows and counts from durable evidence and rechecks inputs before publication.

Historical records remain tied to their original authority checkout. There is no
silent migration of older process receipts without the complete inventory. No
public compiler contract or version changes, and no CI gate is removed or weakened.

## Validation

The correction is checked by worker/review/evaluation regressions and the public
CLI against the installed compiler. The installed evaluation covers nine trials
(three repetitions), six accepted plans and three deliberate wrong-goal rejections,
plus completed recovery, unpublished-review finalization, changed completion
inventory, substituted graph, missing canonical plan, contradictory review and
changed unread export. The installed probes invoke the actual canonical compiler;
the proposing worker is explicitly scripted.

All **71 worker/review/evaluation tests** and **16 installed evaluation outcomes**
pass locally. The existing **20 installed worker outcomes** also pass. The earlier
reproduction run failed on the original implementation before production changes.

Final local results and exact-head/merge-candidate GitHub evidence are recorded in
PR #273. The full existing Python suite retains all 495 tests, including the twelve
Unix-socket observer cases blocked only by the local sandbox. Both full CI jobs and
adapter certification remain required on the corrected revision. Previous green
jobs for `bb641c4` are historical evidence, not validation of this correction.

Live-model provenance and reasoning quality, source interpretation, requirement
completeness and execution-time remote ownership/precondition checks remain separate
acceptance work. A turn allowance is not a provider-request, token or monetary cap.
S3/AI-A3 are not closed, and no controller or workflow execution is activated.
