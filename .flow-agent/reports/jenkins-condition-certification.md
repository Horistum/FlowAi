# AR-06G candidate: real Jenkins conditional execution

Jenkins already declares and renders conditions, but the previous real runtime
scenarios cover only native checkout and failure propagation. This slice adds
independent execution evidence for two complementary boolean equality guards.
It extends the existing conformance runner and lifecycle gate; no new product
authority or execution entry point is introduced.

The first new regression failed against the predecessor planner: an authored
`boolean default true` rendered as `defaultValue: false`. `FlowPlanner` retained
the boolean only in `defaultExpression`, while target inputs consume the existing
concrete `defaultValue` field. The repair preserves boolean literals at their
compiler-owned input boundary, alongside string literals; it does not infer a
target-specific default or evaluate symbolic expressions. Both boolean values
and an absent default are checked in compiler isolation. Existing graph-integrity
conformance now includes target-neutral boolean-default vectors without changing
the 274-check inventory.

Two Flow Source fixtures declare opposite boolean defaults. Each passes through
the production frontend, canonical compiler, materialization and renderer. The
positive script executes unchanged on isolated real Jenkins. The observer reads
workspace contents, native checkout count/errors and completion from Jenkins's
actual execution graph without adding instrumentation to the positive artifact.

Each source has two mutants: remove both guards, or invert both guards. Flattening
must execute both children; inversion must select the other branch. The false
default intentionally produces the same marker after flattening, so checkout
count must distinguish the extra execution. Exact source, graph, script, runtime
and observation references pass the existing authenticated admission path.

The dedicated `jenkins-condition-runtime` CI job executes six builds and publishes
separate true/false trust declarations, proofs and JSON/Markdown views. Views
identify bounded structural occurrences and retain all unobserved structures.
Ordinary tests do not start Docker or Jenkins. Existing checkout and native-failure
runtime jobs remain required.

## Predecessor boundary

PR #203 merged into main `ceea4cc68525bdafbeedfcad6759a11e7345b63f`.
The merged-main evidence is inspected separately from this candidate's validation.
The earlier AR-06A/B acceptance and AR-06F baseline remain immutable.
Both actual-main workflows passed: Flow CI 37900685791 and runtime CI 37900685703.
The five independently downloaded archives match GitHub's SHA-256 metadata and
contain 1,925 tests in 333 suites, no failures/errors/skips, the unchanged ordered
274-check conformance inventory, 769 verified isolation input hashes and six
verified Ed25519 runtime observations. Their test inventory also matches PR #203's
head and synthetic merge candidate.

The immutable receipt is `.flow-agent/evidence/jenkins-condition-baseline.json`
(SHA-256 `05261471566c273815b3e6d9d518f7bb5378ca0ad593060bff8c7825f825131c`).
The existing lifecycle gate selects AR-06G while replaying the earlier AR-06F and
AR-06A/B boundaries; no previous candidate becomes formally accepted by this edit.

## Validation

Current exact-head and synthetic-merge compilation/tests, standalone conformance,
physical isolation and all three runtime jobs must pass. Their observed results
belong in the PR; predecessor CI never substitutes for candidate validation.
Local validation passed on pinned JDK 25 and Gradle 9.5.0:

- 72 targeted tests in 10 suites, including all 14 additions; zero failures,
  errors or skips. Compiler, runtime protocol, historical lifecycle and view
  regressions are included.
- The installed verification distribution passed all 274 standalone conformance
  checks with the exact predecessor order and identities preserved.
- All 157 tooling tests, structure/context generation and whitespace checks passed.

The initial new fixture test failed before the boolean-default repair, proving
that the regression detects the predecessor defect. Actual Jenkins execution
remains required in current-revision CI; local synthetic tests do not replace it.

## Limits

This proves the two boolean equality guards with native checkout children on the
recorded runtime. External parameter overrides, other expression forms, general
condition semantics and error boundaries remain separate obligations. Equivalent
execution on a materially different adapter is still required before portable
execution can be claimed. Public support/maturity, schemas and versions do not
change; AR-06 findings remain open and EF-09 remains paused.
