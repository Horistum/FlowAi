# Deployed observer and independently retained checkpoints

Baseline: merged PR #266, `a5eb8494bf1e1dd63b3684b49964b7db0e6bfae2`.
Work package: [AI-A3-S2.7](../work-packages/deployed-observer-checkpoints.yaml).
Contract: [deployed observer](../../docs/DEPLOYED_RUN_OBSERVER.md).

## Result

Independent Linux collector and witness processes retain the existing journal events and
checkpoints. An acknowledgement requires the journal commit followed by a durable witness
compare-and-advance. The collector checks the witnessed prefix before admission, rejects
rollback and conflicting history, and requires identical redelivery to close a one-event
commit gap. Duplicate receipts keep their original identity. No observed operation is
dispatched by either service. Witness-checked export uses the existing compiler, signatures
and atomic evidence publication while holding the collector coordination lock.

The transport has private Unix sockets, peer UID checks, exclusive service locks, bounded
length-prefixed frames, absolute exchange deadlines and sanitized diagnostics. Witness
enrollment is explicit and separate from startup. Existing direct observer commands retain
their contracts. The new service schema documents host-only requests; no Kotlin standard,
outcome version, signing domain or target support claim changes.

The runtime-allocated identity proposal freezes scope, producer and allocation obligations
before work, separates instance identity from addresses, and requires independent creation,
ownership and final-state evidence. It is design only, with versioning, migration, concrete
acceptance domains and unresolved decisions documented for the next work package.

## Validation boundary

New tests exercise real process death at both commit boundaries, restart, concurrency,
rollback, forks, unavailable witnesses, unconfirmed exports, storage guards, bounded frames
and uncoordinated writes. The installed integration reuses the existing lineage proof across
service, database and credential resources, with separately installed observer code and
separate processes. It adds committed-capture interruption, exact redelivery, dual restart
and restored-prefix rejection to the existing outcome and late-evidence mutations.
Both full CI jobs run this proof through the existing host-suite entry point.

Final exact-head and merge-candidate validation receipts belong in the PR. Local Python
syntax, existing observer regression and architecture checks are available; the local
workspace prohibits socket creation, so socket/process and actual HTTP integration evidence
must come from CI. No local socket failure is suppressed or converted to a passing test.

## Limits

The witness assumes honest, separately retained local storage. Distinct directories do not
prove separate physical failure domains. The shared UID is a trusted operator boundary, not
mutual isolation. Simultaneous witness rollback, malicious storage owners, native observation
truth, remote transport, production deployment, dynamic runtime identity allocation and live
models remain unproven. AI-A3, AR-06/AR-07 and F-16/F-20 stay open; EF-09 stays paused.
