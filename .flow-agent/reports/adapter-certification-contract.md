# AR-06A: adapter certification contract candidate

## Problem and change

Adapter evidence currently spans native catalogs, support profiles, scoped
maturity evidence and historical portfolio records. There is no single typed
candidate binding an adapter revision to canonical scenarios, artifact bytes,
runtime prerequisites and independently supplied positive/negative observations.

This slice adds that contract and atomic admission in `flow-adapter-evidence`.
Admission requires complete declared inventories, actual provider structural
ownership, exact run identities, byte-bound evidence and effective negative
mutants. Infrastructure failure cannot masquerade as a killed mutant.

The generic module keeps its existing dependencies. It adds no concrete adapter
or conformance dependency, runtime engine, wire schema, support default or public
version change. Evidence authentication and semantic normalization remain
independent caller/conformance responsibilities. Admission is not certification.

## Validation boundary

The candidate requires fresh exact-head and synthetic-merge compilation, tests,
standalone conformance and source-deletion isolation in Flow CI. Final observed
run identities and results are recorded in the pull request after execution;
this report does not self-attest future CI.

Added tests exercise admission and mutation failures, compilation without
concrete adapters/conformance, and uncovered inventory admission for all three
real providers. Historical tests and conformance inventories are preserved.
Documentation: `docs/migrations/adapter-certification-bundle.md`.

## Roadmap boundary

Prepared on PR #197 head `397d22e2ee44150717e645f14854fb8683d4f708` while that PR
is open. The work package is a candidate; milestone activation is blocked on
predecessor merge and actual-main validation. AR-06 remains planned, its four
findings remain open and EF-09 stays paused. Existing AR-05 acceptance receipts
are untouched. After prerequisite acceptance, populate real adapter bundles and
behavioral runners before migrating support/maturity publication.
