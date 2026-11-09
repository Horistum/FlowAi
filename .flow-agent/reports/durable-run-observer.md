# Durable observer and interrupted capture reconciliation

Baseline: merged PR #264 at `309df5688a78a12ed35bb8d229d1121879e2ba9f` on `main`.
Work package: [AI-A3-S2.5](../work-packages/durable-run-observer.yaml).
Protocol, use and boundaries: [Durable run observer](../../docs/DURABLE_RUN_OBSERVER.md).

## Result

A reusable external Python host captures exact source, independent requirements,
compiler correlation metadata and actual worker observations. SQLite transactions,
explicit preceding checkpoints and bounded hash-chain replay keep durable ingestion
separate from operation dispatch. Identical redelivery returns the original receipt;
conflicting delivery cannot overwrite an earlier event or implicitly rebase a writer.

An outstanding start remains pending across process restart. A later actual result
completes the same invocation without executing the operation again. Run closure and
evidence completeness remain distinct: a closed run may still need late result/ownership/
creation captures and fresh final state. Open runs export no synthetic run closure.
The existing Kotlin evaluator remains the only graph and outcome authority.

Export binds an explicit retained checkpoint, original inputs, installed compiler and
independent signing grant. The existing version 2 signature protocol is unchanged.
Prepared evidence is published into a reserved private destination only after signing,
compiler assessment and synchronization. Rejected or interrupted preparation never
replaces a published snapshot or changes the journal.

## Evidence and limits

Tests kill real writer processes around commit, race competing writers, replay deliveries,
restore an older journal prefix, mutate captures and reject inconsistent observations.
Installed integration launches fresh observer processes around real local service startup,
SQLite recovery and credential replacement. Collection pauses after the repair has already
happened, exports unresolved evidence, then reconciles the retained reply and proves that
the repair executed once. Additional variants cover late results, stale checkpoints,
reported false state and altered journal contents.

Durability depends on the local filesystem honoring SQLite locking and synchronization.
Externally retained receipts detect rollback only outside the journal's rollback domain.
The storage owner, signing key and observer truth remain trusted; hash chains are not
distributed consensus or proof against signed forks. Capture storage is sensitive.
Deployment of a production observer, external checkpoint retention and exact dynamic
resource producer-invocation lineage remain separate work. No cloud, live-model, human
approval, arbitrary parallel execution or global workflow correctness is claimed.

The PR records local and final revision validation receipts. AI-A3, AR-06/AR-07 and
F-16/F-20 remain open; EF-09 remains paused.
