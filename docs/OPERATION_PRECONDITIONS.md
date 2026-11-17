# Fresh operation preconditions

AI-A3-S3.6 adds `preconditions` to the installed Horistum compiler. It checks
independently declared expectations about **existing resources** against signed
snapshots. A result is bound to the compiled graph, exact requirements bytes,
assessment challenge, selected operation and native resource instance.

This is a point-in-time review. An external executor must reassess at its own
dispatch boundary and enforce its own authorization and concurrency controls.
No result dispatches work, grants permission, acquires a lock or guarantees that
state will remain unchanged. `executionAuthorized` is always `false`.

## Operator sequence

1. Compile the reviewed proposal with `flow-core proposal proposal.json --out plan`.
   Pin its `graphDigest` in an independently reviewed precondition document.
2. Declare the exact existing addresses, native instance identities, ownership
   and scalar facts needed by the selected operations. Do not derive expectations
   from the observations that will be checked.
3. Freeze those bytes with a new assessment ID and unpredictable challenge. Set
   `issuedAt` and `expiresAt` using the trusted operator clock; the window must be
   positive and at most one hour. Keep observer trust outside model output.
4. Collect fresh observations after `issuedAt`. The independent observer reads
   actual provider state, retains its source bytes and signs the snapshot.
5. Recompile and assess using the current clock immediately before external use:

```sh
flow-core preconditions proposal.json --source-kind proposal \
  --requirements preconditions.json \
  --observations snapshots.json --trust precondition-trust.json \
  --out precondition-review
```

`--source-kind intent` accepts an Intent document through the same canonical
compiler. `--target` and `--render` are not supported. `--out` must be a new
directory; the review and canonical planning artifacts are published atomically
and inventoried with their schemas. Without observations, the command publishes
an unresolved review, including selected node IDs useful to an observer.

The default assessment clock is taken **after compilation**. Explicit
`--evaluated-at <ISO-8601 instant>` is for reproducible or historical review. An
external consumer must compare both `evaluatedAt` and `validUntil` with its own
current clock; accepting a historical review as current would be a caller error.

## Requirements version 1

See [the requirements schema](../schemas/operation-precondition-requirements.schema.json)
and the three [conformance examples](../conformance/operation-preconditions).
Their graph digests and challenges are test placeholders, not deployment values.

| Field | Meaning |
| --- | --- |
| `graphDigest` | Exact canonical graph to reassess |
| `assessmentId`, `challenge` | Caller-owned context; challenge has 32–128 URL-safe characters |
| `issuedAt`, `expiresAt` | Admissible snapshot and review window, at most one hour |
| `reviewerId` | Caller attribution, not authenticated human approval |
| `resources` | 1–64 unique IDs, addresses and scoped native instance identities |
| `operations` | 1–64 unique requirements selecting canonical operations |

A resource contains `id`, literal `identity: {scope, type, key}`, `instanceId` and
`disposition`. `MANAGED` requires an exact `owner`; `SHARED` forbids declaring an
owner and only permits read-only operations. A native instance pin detects a
replacement at the same address. The contract does not create, discover or infer
these identities. All declared resources must be referenced.

An operation contains `id`, the existing capability/workflow/parameter `selector`,
`resource`, resource operand `parameter`, optional nested map `path`, `facts`, and
`maxAgeSeconds` (1–3600, default 300). Facts are at most 32 named scalar values;
strings, booleans and exact decimal numbers are compared without coercion. An
empty fact map still requires the identity and applicable ownership checks.
Missing facts remain unresolved. The selector must identify exactly one canonical
task and its operand must be the declared literal resource address. Dynamic
inputs and output references remain unresolved in this version.

Every changing canonical task must have a declaration. This detects an omitted
operation; it does **not** infer every affected resource of a multi-resource
operation. The reviewer remains responsible for declaring all necessary operands
and facts. Read-only tasks need declarations only when their state is relevant to
the precondition contract. Shared resources cannot be used with changing effects.

## Independent snapshots and trust

Each observation includes `formatVersion: 1`, a unique `id`, `contractSha256`,
`graphDigest`, `assessmentId`, `challenge`, `requirementId`, `nodeId`, `resource`,
`status`, `observedAt`, `sourceSha256`, `sourceSizeBytes`, optional `instanceId`
and `owner`, and `facts`. `contractSha256` is the hash of the exact frozen input
bytes. `source` retains the exact provider capture used by that observer.

The observation bundle has `formatVersion: 1` and `observations`. Each envelope
has `keyId`, Base64 `payload`, Base64 Ed25519 `signature`, and Base64 `source`.
The signed message is the concatenation of:

1. UTF-8 `Horistum operation precondition`, zero byte, `v1`, zero byte;
2. the four-byte unsigned big-endian length of the UTF-8 key ID;
3. the key ID bytes;
4. the exact payload bytes.

The separate signing domain prevents post-run outcome signatures from being
reused as preconditions. Existing outcome versions 1–3 are unchanged.

Trust contains `formatVersion: 1` and unique `grants`. Each grant provides
`keyId`, Base64 X.509 DER Ed25519 `publicKey`, exact `scopes`, and exact
`resourceTypes`. It authorizes only this snapshot protocol. There are no wildcard
grants, model-supplied permissions or implicit post-run observation privileges.

The complete supplied bundle must authenticate. Unknown keys, malformed signed
claims, duplicate IDs or requirement claims, foreign contexts, substituted
sources, out-of-window timestamps and ungranted scopes invalidate its use. A
valid-looking subset cannot rescue an invalid bundle. Observations must be at or
after `issuedAt` and no later than either `expiresAt` or `evaluatedAt`.

Signatures establish the authorized observer's assertion and retained byte
integrity. They do not independently prove remote truth, truthful observation time
or an atomic snapshot across resources. The compiler does not parse arbitrary
provider bytes to rederive their claims. Providers and extractors must be trusted
and reviewed independently. Retained sources should exclude secret values.

## Decisions and freshness

| Exit | State | Meaning |
| --- | --- | --- |
| 0 | `SATISFIED` | All declared obligations pass at `evaluatedAt` |
| 3 | `UNRESOLVED` | Evidence, selection, freshness or changing-task coverage is insufficient |
| 4 | `VIOLATED` | An authenticated value or declared operation contradicts a requirement |
| 2 | CLI rejection | Malformed contract, changed graph or publication failure |

A well-formed source rejected by the compiler exits 4 without publishing a
precondition review. Invalid source syntax or input shape exits 2. Neither result
constitutes an assessment of observed resource state.

Only a satisfied review has a non-null `validUntil`. It is the earliest of the
assessment expiry and every required snapshot time plus its age allowance. The
boundary is inclusive; one instant after it is stale. This bounds reuse of
evidence, not the lifetime of the observed state. Failed/unknown observer status
means evidence is unavailable, not that the resource itself was proved bad.

Reports explicitly retain `scope: declared-existing-resource-preconditions`,
`observationTruth: trusted-observer-assertion`, `reviewerAttribution: caller-declared`
and `snapshotAtomicity: not-established`. Schemas describe syntactic interchange;
the strict reader and canonical reviewer own cross-field and semantic validity.

## Evidence and remaining acceptance

`tools/verify_operation_preconditions.py` exercises the installed CLI across
infrastructure, data-copy and credential-rotation fixtures. Independent local
JSON/SQLite reads provide facts; real stored owner, instance and state changes
invalidate unchanged requirements. Missing, stale, replayed and substituted
evidence are also checked. This runs in both full CI revisions through the existing
authoring-host verification entry point.

These are local observer integration tests. Live-model portfolio acceptance,
deployment-specific trusted observers and external dispatch-time enforcement
remain separate work. S3 and AI-A3 are not closed by this slice.
