# Scoped resources and operation outcomes

Baseline: merged PR #261, `230cfe1608a8829814ae384163fd78ff36a473db`.
Work package: [AI-A3-S2.2](../work-packages/operation-outcomes-resources.yaml).
Contract: [Operation outcomes](../../docs/OPERATION_OUTCOMES.md).

## Result

The separate `outcomes` command evaluates reviewer-owned scalar postconditions over
an accepted compilation unit. Exact scoped resource identity and canonical value
edges bind the observations to real selected operations. Successful operation results,
post-result state observations, ownership and actual creation are distinct claims.

An Ed25519 observer protocol binds original payload and captured source bytes to the
exact requirements digest, graph, run and challenge. Caller-owned scoped trust grants
are supplied separately. Invalid, stale or contradictory records cannot be selected
away to obtain a passing result. The evaluator and reports do not authorize execution.

The implementation reuses existing frontends, semantic graph and atomic publication.
Existing authoring/plan contracts and prompts are unchanged; the new outcome artifacts
are version 1. Schemas are syntactic interchange definitions with explicit ownership.

## Evidence and limitations

Three manually authored fixtures exercise infrastructure creation and a shared resource,
isolated data copying, and credential rotation. The installed verifier creates ephemeral
local test observers and signs synthetic evidence using OpenSSL; private keys stay in
temporary test directories. It exercises the independent JVM authentication protocol,
successes and negative mutations. No external operations or live-model calls occur.

Flow Source module calls currently omit the semantic capability identity required by
outcome selectors. Its CLI entry is explicitly withheld; direct library assessment
remains unresolved. No new capability inference bypass was added.

Observer correctness, key lifecycle, persisted replay prevention, complete multi-resource
coverage, runtime control paths and attempt/recovery semantics remain outside this slice.
Historical clocks certify freshness only relative to the recorded evaluation instant.
AI-A3, AR-06/AR-07, F-16/F-20 remain open and EF-09 remains paused.

## Validation

- Focused JVM verification: 19 outcome tests and two product CLI tests pass, including
  actual merged-value ownership, creation/removal chronology, decimal precision,
  strict parsing, authentication and atomic publication.
- Python tooling: 339 tests pass; agent structure and whitespace checks pass.
- Installed conformance: all 305 checks pass, including the new three registered vectors.
- Installed authoring proof passes: 36 frozen corpus runs / 49 attempts, five rejected
  clarification changes, six investigation cases, ten system cases, eleven structured
  authoring cases and 27 new synthetic observer cases across three domains.
- All six affected conformance/release regression classes and the remaining frontend,
  module-contract, semantic-kernel, standard-artifact and reference-distribution suites pass.
- Full JVM regression and final-head/merge-candidate GitHub checks remain required;
  the PR checks are the authoritative completion receipts.

Local dependency restoration used a temporary Maven mirror with the same pinned
Gradle/Kotlin dependencies and JDK 25. No repository override is part of this change.
