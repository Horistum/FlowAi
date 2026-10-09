# AR-06H candidate: real Jenkins workflow error boundaries

The existing renderer implements workflow-level error handlers, but earlier real
runtime scenarios cover only native checkout/failure and boolean conditions.
This slice tests successful and failing bodies against the existing `PROPAGATE`
contract and corrects a missing structural occurrence in certification capture.

The new binding regression failed before the repair: canonical workflow policy
contained a handler while bound certification reported no `ERROR_BOUNDARY`.
Capture inspected local graph nodes only. It now also reads the typed workflow
failure policy without creating a synthetic local try node or deriving Core
meaning from a target manifest. Existing admission requires the occurrence to
remain associated with its source scenario.

Two adapter-owned Flow Source fixtures pass through production compilation and
the unchanged Jenkins renderer. The failing body requests a missing revision,
must skip its later checkout, execute the handler checkout and propagate the
original error. The successful body must complete without running the handler.
Independent observers inspect actual Jenkins execution and fresh workspace bytes;
the original generated scripts contain no added instrumentation.

Four mutants omit the handler, suppress propagation, execute the handler
unconditionally or omit the successful body. Build result, workspace marker,
native checkout count/errors and terminal error origin distinguish all six runs.
In particular, an omitted handler preserves failure status, while suppression
preserves marker and checkout count. A terminal error from another operation
cannot impersonate the intentional missing-revision failure.

The `jenkins-error-boundary-runtime` CI job publishes separate failure/success
trust declarations, signed proofs and JSON/Markdown views. Existing runtime jobs
remain required. Observation bytes gain scenario-specific terminal error fields;
no public schema or previous observation format changes. Ordinary tests never
start Docker or Jenkins.

## Predecessor boundary

PR #204 merged into main `5f4eda34cf367291aeab0182df4b78309ed27e32`.
Its post-merge validation is inspected independently from this candidate.
Both actual-main workflows passed: Flow CI 37910523520 and runtime CI
37910523509. Six independently downloaded archives match GitHub SHA-256
metadata and contain 1,939 tests in 335 suites, no failures/errors/skips, the
unchanged ordered 274-check conformance inventory, 769 verified isolation input
hashes and twelve verified Ed25519 observations across four scenarios. The test
inventory matches PR #204 head and synthetic-merge validation.

The immutable receipt is `.flow-agent/evidence/jenkins-error-boundary-baseline.json`
(SHA-256 `cc117ba5c48c2a2abfc4b069eb709ecf0564b8f83e3790d344d4aaa109be8375`).

The existing lifecycle gate selects AR-06H while replaying AR-06G, AR-06F and the
earlier accepted AR-06A/B boundaries. No new authority or conformance identity is
added, and earlier receipts remain immutable.

## Validation

Local validation passed on pinned JDK 25 and Gradle 9.5.0:

- 79 targeted tests in 11 suites, including all 12 additions; zero failures,
  errors or skips. Binding, runtime protocol, authenticated views and historical
  lifecycle regressions are included.
- The installed verification distribution passed all 274 standalone conformance
  checks with predecessor identities and order preserved.
- All 157 tooling tests, structure/context generation and whitespace checks passed.

The initial binding regression failed before the occurrence repair. Exact-head
and synthetic-merge compilation/tests, standalone conformance, physical isolation
and all four real Jenkins jobs must pass on the published revision. Current PR
results belong in the PR; predecessor CI and synthetic observations do not
substitute for this candidate's execution evidence.

## Limits

The evidence is bounded to workflow-level propagation with native checkouts on
the recorded runtime. Local recovery, retries, cancellation, arbitrary exception
semantics and equivalent execution on a materially different adapter remain
separate obligations. Public support/maturity, schemas and package versions do
not change. AR-06 findings remain open, AR-07 stays planned and EF-09 stays paused.
