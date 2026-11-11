# Runtime-allocated resource identities (outcome version 3)

A provision operation may return an address that did not exist when requirements were
approved. Version 3 freezes a logical allocation obligation and assesses the returned
instance against it. It never substitutes observed values into the requirements or changes
their byte digest. Core validates evidence; the external worker still performs every operation.

## Frozen requirements

All v3 resources are newly owned `CREATED_IN_RUN` resources. Each has a unique `id`, a
`creator` selector, a required `owner`, and an `allocation` instead of `identity`:

```json
{
  "id": "database",
  "disposition": "CREATED_IN_RUN",
  "creator": {"capability": "PROVISION"},
  "owner": "tenant",
  "allocation": {
    "scope": "isolated",
    "type": "database",
    "binding": "make",
    "path": ["handle"],
    "keyPrefix": "db-",
    "maxKeyLength": 1000
  }
}
```

Scope and type are exact strings. `keyPrefix` is an explicit literal prefix (empty means
no prefix restriction); `maxKeyLength` defaults to 1000 and is bounded to 1–1000. Keys
remain nonblank. No URI normalization, regular expression or executable predicate runs.
The creator's reviewed `parameter` and optional `path` select its **allocation request**:
a structured literal with matching `scope` and `type`. This is not a predeclared address.
The declared output/path identifies the allocation slot. Every non-creator operation must
consume an output through an existing canonical VALUE edge, with its own parameter/path.

Requirements v3 retain the v2 execution obligation, recovery obligations, freshness limits
and per-operation evidence policies. Every task is covered. Existing sequential trace
restrictions remain: no parallelism, branches, value merges, nested recovery or write retries.
There is exactly one creator invocation per logical resource; imports, existing resources,
aliasing and automatic replacement are not admitted by v3.

## Independent evidence

Every v3 operation observation names `resourceId`, the actual `resource` triple
`{scope,type,key}`, its exact `invocationId`, and optionally `instanceId`. `instanceId` is
a nonblank bounded string read from a stable provider instance/generation record. Its
absence is representable but always unresolved; an address, timestamp, result status or
observer-generated substitute never establishes it. Provider truth remains a trusted
observer assertion, not a property proved by signatures.

An `ALLOCATION` observation needs an explicit ALLOCATION kind grant, independent of RESULT
or BINDING. It identifies the selected creator, its one successful invocation, the frozen
output/path and the completion timestamp. Identical assertions are idempotent in assessment;
conflicting addresses or instance IDs are violations. The address and instance must agree
across **all** claims for that logical resource. Two logical resources cannot alias an
allocated address or the same scoped, typed instance ID.

Allocation does not replace these separate observations:

- **BINDING:** the actual producer output available to each consuming invocation, including
  failed attempts and subsequent retries. A later binding cannot repair a missing earlier one.
- **CREATION:** successful evidence that the resource was created inside the creator interval.
- **OWNERSHIP:** the requested owner observed at or after creation and no later than creator
  completion. Any later changing operation additionally needs ownership before it starts.
- **RESULT:** each actual operation's completion and status.
- **STATE:** independently observed declared facts after explicit run closure, within freshness limits.

A failed creator remains unresolved, including when partial creation is captured. It cannot
publish a successful allocation. Missing claims, missing instance evidence, untrusted grants,
context/signature mismatches and incomplete traces remain unresolved. Once authentication
and a valid closed trace are established, conflicting allocations, wrong declared scope/type,
key constraint failures, wrong creator slots, instance substitutions, ownership mismatches and
failed postconditions are violations. No result authorizes execution or cleanup.

Reviews report version 3, the original requirements digest, the resolved resource and
`instanceId` for each operation and its lineage. An unresolved allocation has `resource: null`;
no placeholder address is invented. Exit codes remain 0 satisfied, 3 unresolved, 4 violated,
and 2 invalid input. Each CLI publication identifies the version 3 review schema.

## Signatures and compatibility

Requirements, observation bundles, trust, operation payloads, run payloads and reviews must
all use `formatVersion: 3`. Ed25519 signs the original strict JSON bytes with these distinct
UTF-8 domains, followed by the existing four-byte big-endian key-ID length, key-ID bytes and
payload bytes:

- `Horistum operation observation\0v3\0`
- `Horistum operation run observation\0v3\0`

Versions 1 and 2 keep their exact-address semantics and signing domains. New fields are
rejected in old versions, including explicit null allocation/instance fields. A v2 signature
cannot authenticate a v3 payload, and older evaluators reject version 3. This is an opt-in
contract migration, not a reinterpretation of existing evidence.

To migrate, first author and review the unknown-address request, freeze its v3 requirements
and original source, then grant the required observation kinds and capture work. Do not edit
old requirements or relabel old signed bundles. The complete
[conformance request](../conformance/runtime-allocation/database.proposal.json) and
[requirements](../conformance/runtime-allocation/database.requirements.json) are syntactic
examples; their placeholder graph digest must be bound to the compiled source **before work**.

## Durable capture

`observe.py init` and `export` accept v2 and v3 requirements. V3 sessions require event
`formatVersion: 2` with `resourceId` and optional `instanceId`; legacy sessions retain event
version 1. Build a v3-session event with `event_for(..., format_version=2)`. The event schema is
[protocol-v2.schema.json](../tools/observation_host/protocol-v2.schema.json).
Session IDs and checkpoint/service protocol versions remain 1: the original requirements and
compiler review digests pin the event version. There is no automatic conversion of a journal.

The journal stores captures even when instance evidence is unavailable; export asks the
compiler for the semantic assessment. Allocation requires a successful captured producer
completion. A second allocation slot assertion is rejected by the journal; an identical
**event and capture** redelivery returns its original receipt. Externally supplied signed
bundles with contradictory assertions are assessed as violations. Collector/witness recovery,
retained prefix checks and witness-confirmed export apply unchanged to version 2 events.
Neither process allocates a resource or retries the external operation.

## Acceptance and limits

`tools/verify_runtime_identities.py` creates a local HTTP listener on a kernel-allocated port
and a database at a newly allocated filename after requirements capture. The provider exposes
a lifetime service instance ID or an immutable database instance record; every resolution
reads it. A real first HTTP/SQLite read failure precedes successful verification. The proof
reuses installed collector/witness processes, kills a collector after durable creation-result
capture, redelivers without recreating the resource, restarts both services, and rejects a
restored journal prefix. Signed mutations falsify scope, type, slots, grants, context, earlier
attempt lineage and generation. It also replaces the actual temporary database at the same
address and requires an instance mismatch. Requirements bytes remain unchanged throughout.

`--direct` runs the allocation/evidence proof without the socket services for environments
that prohibit Unix sockets; that is not deployed-observer evidence. The default full proof
runs in both full CI checkouts. These controlled local providers establish neither cloud
provider truth nor production deployment readiness. Live AI evaluation, held-out rendering,
AR-06/AR-07 and F-16/F-20 remain open; EF-09 remains paused.
