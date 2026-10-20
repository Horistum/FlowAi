# AR-06AE-C13: Conditional workspace consumers

Predecessor PR #240 merged at `784ca80d1ec4a3d22464d4c1638c10d11dc21fd9`.

## Problem and change

Conditions and workspace transfers were individually materializable, but their composition rejected every conditional consumer. The endpoint contract now permits a single-source consumer under nested conditions while retaining an unconditional top-level producer. It traverses only condition ancestry: loops, retries, parallel groups, recovery and conditional producers remain outside the contract.

The generated job keeps its authored guard and contains both download and build. One producer upload still serves all consumers. The renderer checks job/action guard consistency, Boolean context availability, endpoint identities and ordering. Core availability and the existing expression/leaf gates remain authoritative. The internal endpoint scope identity and its active executable-reference binding change together; reference snapshots and frozen profiles do not change.

## Behavioral evidence

The existing workspace experiment retains two unconditional consumers, an independent checkout and both transfer mutants. It adds three consumers under nested then/otherwise branches: one executes and two are skipped. A native guard-omission mutant restores and builds in an unselected branch; the independent source-derived oracle must reject that extra execution.

The Python oracle checks source branch structure against canonical node ownership, exact predicates and manifest guard provenance before comparing native selection and regular, binary and hidden-file inventories. It rejects moved branches, changed predicates, lost guards, wrong skips, unexpected output and missing or unsuccessful mutants. JVM regressions cover input predicates and unavailable event input contexts as well as direct-manifest tampering.

## Validation

All 257 local Python tooling tests passed, including 12 workspace-oracle regressions. Flow Agent structure/context generation, changed YAML parsing and diff checks passed. The final exact-head and synthetic merge-candidate JVM/conformance, isolation and native results are recorded in the PR after CI completes. This environment has JDK 17; the repository requires JDK 25, so no local JVM run is claimed.

## Limits and next work

This is an adapter composition change, not a Core or public-schema change. Native conditional evidence covers literal predicates; input-driven predicates have JVM coverage here. Conditional producers, repeated-control transport, producer-failure native experiments, filesystem metadata fidelity, VALUE/STATE continuity and independent admission of this workspace proof remain open. No signed portfolio expansion, target-wide support promotion, portability claim or AR-06 closure is recorded.
