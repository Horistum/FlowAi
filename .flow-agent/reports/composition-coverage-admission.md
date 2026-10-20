# AR-06AE-C9 — Source-bound composition coverage admission

Based on merged PR #236, `8fd10a8f08ce38b3cd14bffc79c06cbc18473b31`.

## Problem and correction

C8 separated repository reference maturity from runtime certification. The next
roadmap step requires independently admitted composition observations to be
reconciled with target coverage. The five existing composition gates previously
published independent archives, but the authenticated target matrix could not
consume them: they are not signed portfolio assessments. Copying their `passed`
flags into that matrix would overstate both provenance and evidence scope.

The new verification-only consumer pins the successful same-attempt producers'
exact bytes through GitHub job outputs, regenerates compiler inputs and native
observation envelopes, then replays native records using the existing independent
oracles. Rewritten verdicts, surviving mutants, stale provenance and changed
inputs fail admission. Cross-runtime conditions additionally reread final native
GitHub job observations for that same attempt. No target-specific implementation
eligibility or new fixture family is introduced.

Independent regeneration exposed a random temporary source filename in parsed
composition AST metadata. The conformance helper now calls the same parser and
compiler with a stable logical identity. A regression proves identical-source
AST/graph reproducibility and changed-source distinction. Core semantics, adapter
projection behavior and public syntax are unchanged.

The new artifact preserves the original signed portfolio and target coverage
bytes, with a separately labeled CI-bound composition matrix. Its rows retain
signed status and scenario identities, show bounded observations for six cells,
and leave every other cell without composition evidence. Producer claims and
limits remain visible. Atomic publication receipts cover the actual output bytes.

The 39 existing signed assessments and 132 signatures remain unchanged. Existing
native runtime and conformance checks remain required. Focused test results are
uploaded separately so each native archive has an exact root inventory.

## Scope and trust

This strengthens compatibility verification for all declared targets without
creating an executor or a new support catalog. CI runners, compiler preparation,
native observers and GitHub API/job outputs are trusted. CI-bound observations
are not signed assessments; neither evidence class establishes target-wide
certification. The consumer starts no native runtime and reuses existing oracles.

AR-06 remains active. Broader control interactions, general value/state transfer,
provider coverage and portable composition evidence remain open. AR-07 remains
planned, EF-09 paused and F-20 AR-07-owned. No historical acceptance receipt or
support/portability claim changes.

See [the artifact and admission contract](../../docs/validation/composition-coverage.md)
for per-producer limits, output fields and explicit gaps.

## Validation

Local validation passed: 231 Python tooling tests (12 new admission/publication
regressions), Flow Agent structure validation and whitespace checks. Tests cover
failed/stale/missing producers, exact byte binding, unsafe or ambiguous inputs,
replayed native failures and surviving mutants despite authored PASS, preserved
signed bytes, complete gap rows and publication cleanup after a write failure.
The fixture reproducibility regression requires the pinned JVM toolchain in CI.

Validation status remains current-revision CI required. Exact-head and synthetic
merge compilation, full tests, 274 standalone conformance checks, module isolation,
the complete native certification workflow and the new coverage gate must pass
for this revision. The local JDK does not match the pinned JDK 25 toolchain;
previous successful runs are baseline evidence only.
