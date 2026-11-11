# Resource producer and creator invocation lineage

Baseline: merged PR #265 at `860f54d81939b17eddde5d88d0ded3c37841cfad` on `main`.
Work package: [AI-A3-S2.6](../work-packages/resource-invocation-lineage.yaml).
Protocol and boundaries: [Resource invocation lineage](../../docs/RESOURCE_INVOCATION_LINEAGE.md).

## Result

Version 2 output bindings are assessed against their actual successful producer invocation,
canonical output and structured path. The existing trace reviewer records which producer
was available to each consumer. Retry entry and failure-handler entry preserve the declared
availability boundary; identity equality and attempt-number equality are insufficient.
Every reached consumer attempt is checked, including failed retries. Additive review fields
show the actual consumer/producer relationship and independently confirmed creator.

Creation evidence remains separate from output binding. A failed creation command may
leave a resource but cannot publish an output. All consumers must follow the creation
capture. The durable observer accepts exact late bindings through its existing transaction,
checkpoint, trust and publication boundary. It does not choose producers or execute work.

## Evidence and limits

Frontend regressions cover producer placement inside/outside retries, final-iteration
outputs used by later work, missing earlier/latest bindings, output-path mismatches,
conflicting resource values, failed producers, partial creation and unreached handlers.
Python tests cover output inventory, bounded paths, exact completion, independent grants
and signed late capture across collector restarts. The installed proof creates actual local
HTTP, SQLite and credential resources, injects read failures, then verifies 18 positive and
negative variants without repeating creation. The PR records final validation receipts.

Version 1 behavior, version 2 signing domains, canonical graph and execution permissions
remain unchanged. Exact scoped identities must be independently declared before capture.
No provider adapter, runtime identity allocation, production observer deployment, global
replay registry, cloud execution or live model is certified. Broader milestones remain open.
