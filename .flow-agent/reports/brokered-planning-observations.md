# AI-A3-S3.4: existing worker planning and independent handoff review

Baseline: merged PR #271, main `5b1599da18f35da49c5493e5cec19e2ef83737d1`.
Review correction: open PR #272 at `8a56453ecd976b226cb32f7994cf6c44f7120e8e`.

## Confirmed defects in the first implementation

The previous revision passed CI, but its tests did not establish the complete
planning-to-review contract. The maintainer's review prompted executable counterexamples:

| Finding | Reproduced behavior | Correction |
| --- | --- | --- |
| Stale selection | Check a draft, read another source, select the old draft: the host returned `awaiting-independent-review`. | Selection must match the final observation snapshot. New observations identify drafts needing recheck; unchanged repeated reads remain usable. |
| Inconsistent recovery | Change the selected review metadata while retaining all artifact hashes: `verify_handoff` accepted it. | Retain `selection.json`; compare exact typed metadata with the captured candidate result, compiler review, snapshot and derived status. Recovery rejects substitutions without another turn. |
| Premature reservation | Set `maxChecks` to zero: validation failed after `intent.json` had already been reserved. | Share configuration validation with `PlanningTools`; complete compiler/catalog preparation before reserving dispatch. |
| Unconnected independent review | Documentation and the installed proof invoked the raw compiler, bypassing handoff revalidation. | Add a model-free review mode that recovers the exact selected receipt, freezes independent requirements and checks source/input integrity around canonical compilation. |
| Unrecoverable lookup typo | A wrong artifact ID raised a fatal host error. | Only lookup errors become correctable tool responses; changed retained bytes remain fatal. |

The initial regression run failed on the original implementation. The old fixtures
kept observations fixed before all drafts, mutated artifacts rather than duplicated
receipt metadata, omitted preparation failures, and tested independent compilation
outside the actual handoff. The new regressions exercise those missing transitions.

## Resulting design

The portable `agent_worker.AppServerWorker` remains pinned to control-plane commit
`92a664b7ce894b48ce0fdcb35283c43a5fed661e`. It consumes existing planning tools and
selector-free discovery v3. No second model transport or semantic evaluator is added.
MCP, shell, edits, approval and workflow execution remain disabled in this worker.

Preparation validates the configuration envelope and bounds and captures compiler
contracts/catalog before model dispatch is reserved. Each checked draft retains its
observation snapshot. Final selection and subsequent recovery verify the same
candidate, snapshot, compiler identity, contracts, artifact bytes and status.
Completed receipts recover without model/compiler calls; uncertain turns never replay.

Final review also reproduced omission of an unselected draft from the receipt's
artifact map. The retained selection now freezes the complete pre-selection
inventory, so dropping an older draft's artifact is rejected during recovery.

Independent review is a separate host operation using the same CLI entrypoint with
`--review-requirements` and `--review-out`. It cannot start a worker, select another
candidate or feed reviewer expectations back to the proposing model. Requirements
stay outside model-readable roots and planning outputs. The host copies exact inputs,
invokes canonical `proposal --requirements`, revalidates the original handoff and
requirements, checks compiler receipts, and publishes a private `review.json`.

The compiler alone assesses request matching and requirement semantics. A successful
review means satisfaction of the supplied confirmed requirements. Natural-language
completeness, source interpretation, remote truth and execution permission are not
established. No public Intent or standard version changes.

## Validation of the correction

- **36 worker/review tests pass** against the actual packaged worker and scripted
  stdio peer. New cases cover late observations, typed receipt substitutions, real
  journal recovery without replay, preparation failures, repairable lookups,
  model-free review, independent-input isolation, stale sources and changed inputs
  or inconsistent compiler receipts during review.
- **20 installed-compiler outcomes pass** through the public worker/review CLI
  across infrastructure and isolated data recovery. The independent-review cases
  select an actual retained worker candidate and prove positive acceptance,
  compiler-valid wrong-goal rejection, request mismatch rejection and stale-source
  rejection. Review and completed recovery add no model turns.
- The existing MCP transport test and **14 MCP installed-compiler outcomes pass**.
- The full existing Python suite ran **495 tests** locally: **483 passed**; the same
  12 deployed-observer process tests are blocked by sandbox Unix socket `EPERM`.
  They remain enabled and required in GitHub CI.
- Flow Agent structure/context and diff checks pass. Final-revision exact-head and
  merge-candidate CI, including JVM/conformance, isolation and all installed-host
  proofs, remain required before claiming completion of this correction.

The existing full-job budget remains 45 minutes. Its earlier increase addressed a
measured 35-minute job timeout during the last allocation proof. This correction
does not change budgets, skip tests or weaken any CI gate. Previous green jobs are
historical evidence, not validation of the corrected revision.

## Remaining boundaries

These are scripted protocol/compiler checks, not live-model reasoning. Private
fixture attestations are not deployment commissioning evidence. The reference
delivery controller is not activated. Source observations are not automatically
promoted to operation-parameter authority or remote ownership/completeness claims.
Live model evaluation and execution-time state/ownership revalidation remain next.
S3, AI-A3, AR-06, AR-07 and F-16/F-20 remain open; EF-09 stays paused.
