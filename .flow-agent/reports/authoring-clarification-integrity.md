# Authoring clarification integrity

Baseline: merged PR #258, main `d4f98d525a8ad4ae9a6fc051155e75dcb4752f1a`.
Scope: a bounded AI-A3 continuation under the existing authoring work package.

## Review of the merged change

PR #258 preserves accepted canonical and independent fidelity evidence for declared
targets without projection implementations. Its production check runs after semantic
acceptance, fidelity review and required provider decisions. Compiler input errors in
the immutable request stop the host rather than spending repair calls. Regression
coverage includes all six declared targets, target conflicts, proposal report versions
and installed distribution publication. No missing implementation was found in that
correction's declared scope.

The merge has the same tree, `dd42ec0989ac9d6994bf0975e47072cdcb730099`, as the
validated PR head. Post-merge workflow results are recorded in this followup PR's
validation record. The wider AI-A3 milestone remains incomplete: deterministic peers
do not measure live model quality, and the specified held-out certificate proposal
still lacks an authorized executable target artifact.

## Real continuation defect

Before this change, `resume` checked original-request equality, answers, confirmation
and reviewer attribution, but did not compare the new requirement entries with the
previous revision. For example, retaining the request text while dropping
`approval-first` or replacing the expected image produced a new oracle for the next
compiler call. Documentation asked the caller to preserve constraints without
enforcing that boundary.

The host now checks stable, unique requirement identities and preserves the entire
captured JSON entry for every non-clarification requirement. New entries may add
constraints; answered clarification entries may disappear or become concrete checks.
Reordering is allowed. A later round also protects requirements added by earlier
rounds. JSON comparison distinguishes booleans from numeric values.

The check precedes answer/requirements publication and provider dispatch. Rejected
changes leave the waiting session, evidence and reservations unchanged, so a corrected
continuation remains usable. A changed established constraint requires a new run,
independent review and a new explicit budget. This deliberately avoids introducing a
second semantic-equivalence authority in the Python host.

## Validation ownership

Six new Python process regressions cover removed and rewritten constraints, duplicate
or invalid IDs, nested predicate changes, JSON value types, allowed additions and
reordering, answered question removal and multiple continuation rounds. Negative
cases assert unchanged state/evidence and provider-call counts, followed by a valid
continuation.

The installed-product verifier adds five independent requirement mutations:
removed approval ordering, changed image, changed verification resource, relaxed
approval count and duplicate identity. It then resumes successfully with the original
requirements. These mutations exercise the existing host process proof in both
exact-head and merge-candidate CI. The unchanged twelve-case corpus still runs three
times; no expected proposal or requirement fixture is modified.

No compiler/Core contract, target projection or native runtime behavior changes, so
the executable host regressions own this boundary rather than expanding the frozen
Core conformance inventory. Existing JVM, conformance, physical isolation and adapter
certification remain required checks.

Local command execution is unavailable in this session. No local test pass is claimed.
The final PR validation record must identify the tested head/merge SHAs and actual
GitHub Actions results before the change is described as validated.

## Remaining work

AI-A3 still requires a configured live provider/model and budget, captured corpus
measurements and independent quality review. The existing certificate materialization
blocker must drive a reusable implementation-binding decision under AI-A4 and a
second independent composition; changing target selection alone is insufficient.
AR-06, AR-07, F-16 and F-20 remain open and EF-09 remains paused.
