# AR-06U: Bounded Jenkins Parallel Approval Certification

## Purpose and boundary

AR-06T preserved the canonical parallel branch contract. This slice adds a bounded
Jenkins implementation: one top-level group containing exactly two manual approval
gates, distinct messages, no result/output bindings or explicit gate dependencies,
and failFast false. The adapter uses preserved wrapper IDs as closure keys, so
duplicate authored branch labels cannot overwrite a branch. Canonical semantics,
public syntax, artifact versions and generic registry support are unchanged.
Plan-scoped branch-isolation evidence identifies the two independent native input
executions; it does not assert fresh workspaces or mutable-state isolation.

## Behavioral evidence

Two external certification scenarios execute the exact compiler-rendered pipeline.
The observer inspects native pending inputs before making test decisions. It records
both gates simultaneously paused with no checkout, then the remaining paused gate
after the first decision, then native input errors and terminal failure origin.
Approval must join before one successor checkout. Rejection must leave the sibling
pending, then propagate the original input rejection after the sibling completes,
with no successor checkout. No observer probes are added to generated pipeline bytes.

Five mutants distinguish serialization, omitted parallel work, premature continuation
in both outcomes and suppressed rejection. A bounded ten-second observation window
allows a serialized mutant to complete and produce different evidence instead of
treating serialization as an infrastructure timeout. Native error identity separates
the expected ABORTED result from infrastructure failure. Ed25519 authentication binds
the observations to exact artifacts, source, graph, runtime prerequisites and runner
challenge. The portfolio replays both new assessments with all existing producers.

Unit tests exercise admission, forged rendering scope, duplicate labels, observation
shape, sibling wait, failure origin, mutant discrimination and immutable lifecycle
receipts. Synthetic unit records are protocol regressions, not native runtime proof.

## Validation and predecessor

Before the release-state update, all 162 Python tooling tests, Flow Agent validation,
context generation and whitespace checks passed. Local Gradle stopped before
compilation because the pinned Kotlin 2.4.10 plugin is absent from the offline cache.
Fresh exact-head and synthetic-merge compilation/tests/conformance, four isolation
proofs, all runtime producers and the complete portfolio remain required. CI receipts
will be attached to the PR after verification; this candidate does not preclaim them.
The first CI revision exposed missing bounded branch-topology evidence and two
catalog inventory assertions that still expected no parallel implementation. The
scope now supplies input-execution isolation evidence under the same gate-only
restriction, and the retained negative test explicitly rejects an out-of-scope body.

PR #217 merged as 1a78680e38ad73f3191a10b97e1e632228650090 with the same tree as its
validated revisions: 2106 tests and 274 ordered checks each, 21 assessments, 65 verified
signatures and 17 verified archive digests. The immutable baseline preserves those
receipts and separates the actual-main workflow observations from PR validation.

## Limits and follow-up

This scope proves overlapping suspension and a native join. It does not claim parallel
CPU scheduling, failFast true, cancellation, nested or arbitrary branch work, workspace
isolation, result transfer, human authorization or portable execution. Broader branch
bodies and cancellation need independent certification. AR-06 stays active, only A/B
are formally accepted, AR-07 remains planned, EF-09 paused and F-20 AR-07-owned.
