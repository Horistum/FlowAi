# AR-06AE-C3 — Shared native condition composition

PR #230 merged at `991393ecb14f4e8f1329989ff163010bc09e43bb`, tree
`70cdaca7c49165e0e354c8e19207d116df6f820f`. It generalized GitHub Boolean
conditions and successor scheduling, but compared Jenkins only at projection
level. This correction adds native comparison of that same compiled composition.

## Architecture and change

An external conformance oracle derives selected action identities and ordering
obligations from the original parsed AST. It checks canonical graph task/parameter
and ordering correspondence, binds each target manifest's identities, and compares
native observations against those source expectations. It accepts any order of
independent siblings and rejects missing, duplicated, substituted or reordered
dependent work. No adapter supplies the expected selected branch.

The oracle is independent of fixture names and task counts. Its supported evidence
domain is explicit: typed Boolean expressions and inputs, nested condition/action
composition, resolved ordering and immutable checkout observations. Unit tests
exercise renamed identities, additional inputs/actions and different valid orders.
Unsupported expressions, value coercion, missing input values and control constructs
fail closed. This is verification code, not an interpreter or product runtime.

The new CI job consumes both existing GitHub native artifacts and checks identical
AST, graph and generated artifacts plus source/workflow/run/attempt provenance.
The GitHub API supplies actual completed job status and start/completion times;
job outputs supply observed revisions. Jenkins runs eight builds in one disposable
pinned controller: both inputs, each with a baseline and three mutations.

Every Jenkins native checkout is wrapped in an observation stage. Removing these
wrappers restores the generated artifact byte-for-byte; guards, calls and relative
order remain unchanged. A native SCM listener reads the actual workspace revision
after each checkout, identifies the enclosing observation stage and records the
native FlowNode. Completed native stage boundaries provide ordering evidence.
No expected action list or scenario identity enters that observer. Mutations invert
the optional guard, omit the join checkout, or swap the join and its successor.
The independent oracle must reject all six native mutant observations.

## Boundaries

The proof establishes selected-task and authored-order agreement for this one
composition under both Boolean assignments in checked observation envelopes.
It does not establish general portability, workspace equivalence, sibling
concurrency, output transport or injected failure/cancellation equivalence.
Compiler preparation, CI runners, native SCM callbacks and GitHub API/job outputs
are trusted. Content receipts bind every input, executed artifact, native log and
observation; this proof is separate from the signed certification inventory.

Production Kotlin, Core semantics, source grammar and adapter eligibility do not
change. Existing 39 assessments/132 signatures and all prior native regressions
remain required. AR-06 remains active, AR-07 planned, EF-09 paused and F-20
AR-07-owned. Next, remove remaining fixture-shaped retry/loop/match/parallel
restrictions using justified control and topology contracts and independent evidence.

## Validation

Local validation passed 177 Python tooling tests and Flow Agent structure/context
checks. Replaying #230's actual GitHub artifacts verifies the new oracle against
previously observed branches, revisions and job ordering. Local Java 17 and the
absence of Docker prevent claiming a local pinned JVM/native run. Required
exact-head and synthetic-merge CI, module isolation, new cross-runtime execution
and the complete authenticated portfolio run in GitHub. Final observed receipts
are recorded in the PR after completion.
