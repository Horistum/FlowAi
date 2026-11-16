# AI-A3-S3.5: reproducible worker evaluation

Baseline: PR #272 merged as `b32c51dfe374899a13a1a03fd107656363faecab`.

## Problem and completed behavior

The legacy authoring evaluator drives the data-only proposal interface. Reusing it
would not measure the newer worker's observation choices, retained candidate
selection or independent review. A collection of successful draft checks would
also conflate compiler acceptance with satisfaction of the original requirements.

The new public evaluation CLI orchestrates the existing worker and compiler review
over a fixed case portfolio with at least three repetitions. It freezes independent
requirements, all bounded discovery exports, shared settings, code and compiler
identity, public contracts and an explicit total turn allowance before dispatch.
It checks private requirements against every case's grants, including cross-case
exposure, and keeps snapshots and expectations outside the model's inputs.

Every planned trial remains in the denominator. Ordinary blocked selections and
wrong-goal plans are measured outcomes. Preparation failures, uncertain dispatch,
changed sources and invalid evidence stop further work. An outer reservation is
durable before worker dispatch; an ambiguous reserved slot cannot be replaced.
Completed worker receipts and independent reviews recover without another model
or compiler invocation. Missing summary files are derived again from exact evidence.

The implementation reuses worker configuration, bounded discovery, authority hashes,
locks, atomic writes and canonical compiler assessment. It adds no model transport,
scenario-to-plan production mapping, Python semantic evaluator or execution path.

## Lifecycle review and counterexamples

| Boundary | Counterexample exercised | Enforced behavior |
| --- | --- | --- |
| Whole experiment | Mutate an export the worker never read. | Full private snapshots invalidate recovery and later dispatch. |
| Private oracle | Put another case's requirements under a granted root. | Reject the entire portfolio before dispatch. |
| Turn preparation | Fail commissioning or catalog preparation. | No outer turn is reserved; corrected prerequisites can resume frozen inputs. |
| Crash window | Reserve a trial without a worker intent, or lose a dispatched turn's receipt. | Retain the incomplete slot and forbid automatic replay. |
| Completed worker | Interrupt before independent review or before outer aggregation. | Recover the completed worker and finish only the missing review/aggregation. |
| Partial review | Leave compiler output without a completed review receipt. | Do not overwrite or rerun the partial review. |
| Review recovery | Substitute candidate metadata, omit artifacts or change process status. | Reconstruct from exact captured inputs and compiler evidence; reject contradictions. |
| Review success | Remove both graph digests or use nonregular evidence files. | Require a valid graph identity and safe regular inputs; absence cannot equal successful assessment. |
| Authority drift | Change public contracts while keeping the same executable. | Bind contracts independently before dispatch and during recovery. |
| Outcome quality | Select a compiler-valid plan with the wrong failure disposition. | Record compiler acceptance separately and retain independent requirement rejection. |

Independent review now retains the actual compiler process exit status separately
from compiler JSON, and derives the complete review metadata and artifact inventory
when recovering. Older private records without this process receipt remain
historical evidence under their original checkout; they are not silently migrated.
The existing code-identity boundary also prevents reusing old commissioned adapters
or planning runs under changed host code.

## Validation

- **58 worker/review/evaluation tests pass**, using the actual pinned worker package
  and scripted stdio peers. They exercise preparation, selection, private inputs,
  counters, crash boundaries, process-free recovery and evidence corruption.
- **12 installed evaluation outcomes pass** through the public CLI and real compiler:
  three cases repeated three times, then completed recovery, contradictory review
  rejection and changed unread-export rejection. All nine trials complete; six meet
  independent requirements and all three deliberate wrong-goal plans are blocked.
  Recovery, independent review and summary add no model turns.
- **20 existing installed worker outcomes pass**, as do the MCP transport test and
  **14 installed MCP outcomes**.
- The full existing Python suite ran **495 tests** locally: **483 passed** and the
  same **12 deployed-observer tests** were blocked by sandbox Unix socket `EPERM`.
  A direct socket probe reproduced the restriction. Those tests remain enabled in CI.
- Concurrent local executor sessions reproduced a JVM performance-data collision:
  `Cannot use file /tmp/hsperfdata_agent/19 because it is locked by another process`.
  JVM startup put that warning before the catalog marker on stdout, despite exit 0.
  The catalog boundary correctly rejected it before reserving any turn. The proof
  wrapper now prints its retained report on failure. The unchanged evaluator passes
  when run independently; CI already runs these installed suites sequentially.
  No parser relaxation, warning filter or automatic retry was added.
- Final-revision exact-head and merge-candidate GitHub checks, including JVM tests,
  conformance, adapter certification and all installed proofs, remain required.
  The existing 45-minute job budget and every existing gate are unchanged.

The installed portfolio is deliberately scripted integration evidence. It is not a
measurement of live model reasoning. The original twelve-case live corpus and
held-out rendering gates remain unchanged. Independently verified live provenance,
source interpretation, requirement completeness and remote precondition/ownership
revalidation remain separate acceptance work. A worker-turn allowance is not a
provider-request, token or monetary cap. S3/AI-A3 are not declared complete by this
runner; no reference controller or workflow execution is activated.
