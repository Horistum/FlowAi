# Runtime allocated resource identity and instance evidence

Baseline: merged PR #267, `59db8dafc74f4904291fb451f4bf4d4ab8ff65f9`.
Work package: [AI-A3-S2.8](../work-packages/runtime-resource-identities.yaml).
Contract and migration: [runtime resource identities](../../docs/RUNTIME_RESOURCE_IDENTITIES.md).

## Result

Outcome version 3 freezes logical resource IDs, exact scope/type, creator output slots,
literal key constraints and ownership obligations without freezing an unknown address.
An explicitly granted ALLOCATION assertion resolves one address and stable provider instance
against the unchanged requirements digest. Creation, ownership, each invocation result,
canonical producer lineage and fresh final state remain independently required. Missing
instance evidence remains unresolved; conflicting allocations and substituted instances are
violations. A failed creator cannot publish an allocation. Existing trace validation is reused;
no graph, allocator, dispatcher or provider-specific semantic rule is introduced.

Versions 1 and 2 retain exact-address behavior and signing domains. Version 3 has separate
requirements, payload, trust, bundle, run and review schemas and distinct signing domains.
CLI artifact receipts identify the v3 review schema, including unresolved null resources.
Schema resources and source ownership are registered with their existing module owners.

Observer event version 2 captures resource IDs and instance evidence for v3 sessions.
Original bytes, immutable allocation slots, duplicate receipts, collector/witness commit-gap
reconciliation and confirmed export remain durable. Session/checkpoint and service protocols
retain version 1; requirements and compiler review digests bind the required event version.
No journal is silently migrated and no external operation is repeated during evidence replay.

## Validation boundary

Focused JVM tests cover actual producer slots, every retry, missing evidence, independent
creation/ownership, generation substitution, wrong scope/type/key, conflicting allocation,
partial failure, incompatible fields and versions, signatures and grants. Seven public
signed conformance vectors exercise acceptance and falsification, with all v3 schemas checked.
Nine observer regressions cover event versions, capture, duplicate receipts, contradictory
slots, partial creation, missing instances, ownership timing and distinct signing domains.

The installed integration obtains real HTTP ports and database filenames after requirements
capture, reads native instance records, and experiences actual failed/successful readiness
attempts. Its default mode uses separately packaged collector/witness processes, tests death
after durable creation capture, identical redelivery, both-service restart and rollback
rejection. Negative signed evidence includes an actual database replacement at the same
address. The direct mode isolates allocation/evidence checks where Unix sockets are prohibited;
it cannot substitute for the deployed proof. Neither mode mutates frozen requirements.

Local validation passed 208 frontend tests, 76 CLI tests, 319 conformance checks and all
43 direct allocation integration variants. The full deployed variant is reserved for CI.

Final exact-head and merge-candidate checks, job links, test counts and installed-proof
receipts are recorded in the PR. The local workspace rejects Unix socket creation with EPERM;
its process-test failures are not suppressed or counted as passes. Full GitHub checks remain
the completion gate for deployed behavior. Architecture validation and historical contracts
remain required alongside the new tests.

## Remaining work

S3 evidence-driven system discovery is selected next. This slice does not establish provider
truth, cloud execution, replacement authorization, production deployment, live AI reasoning or
held-out rendering. Existing resources, aliases and more general execution shapes require
separate contracts. AI-A3, AR-06/AR-07 and F-16/F-20 remain open; EF-09 stays paused.
