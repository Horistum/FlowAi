# Durable external run observer (AI-A3-S2.5)

`python3 tools/observe.py` captures observations from caller-operated workers into a
bounded SQLite journal and exports signed outcome contracts version 2. It is a reusable
external host, separate from the compiler and from the operations being observed.
It contains no provider adapters, operation dispatcher, recovery planner or command runner
for workflow work. Its only child processes are the explicitly supplied installed compiler
and OpenSSL for Ed25519 signing. Python 3.10+, SQLite, OpenSSL and a POSIX local filesystem
are required. The Kotlin product and its public outcome contracts are unchanged.

## Lifecycle

1. **Initialize:** capture exact source and independent requirements bytes. Ask the
   installed compiler for a pending outcome review. Bind its graph, canonical invocation
   targets, requirements digest and installed executable/JAR fingerprint to the journal.
2. **Record START:** persist the invocation identity before an external worker performs
   work. Acknowledgement means a durable record, not proof that the external operation
   started. A crash between recording and dispatch therefore remains ambiguous.
3. **Record FINISH and observations:** the worker supplies an actual completion and raw
   capture. Each result belongs to one invocation, including failed retry attempts.
   Failure identities and handler causes remain explicit.
4. **Record CLOSE:** explicitly attest the run's terminal status. Outstanding starts
   prevent closure. The observer never infers closure from the latest success or an idle
   timeout. No subsequent START or FINISH is admitted.
5. **Reconcile and export:** late result, ownership and creation captures may arrive for
   existing invocations, preserving their actual observation times. Final state captures
   are admitted only after explicit closure, with observation times at or after run completion.
   Export a caller-selected checkpoint and assess it
   using the same installed `outcomes` command.

The journal has three collection states: `OPEN`, `PENDING_INVOCATION` and `CLOSED`.
None means that the declared outcome is satisfied. A closed history can still lack
results or final state and remain `UNRESOLVED`, or fail postconditions and be `VIOLATED`.
An open journal exports its captured resource observations without an execution envelope;
the compiler reports `OUTCOME_RUN_MISSING`. The separate observer snapshot retains
completed invocations and pending identities without inventing a completion timestamp.

After interruption, inspect the persisted invocation and obtain its actual result from
the worker or system of record. Append that result under the **same invocation identity**.
If its outcome cannot be established, leave it pending. Restarting collection never
redispatches work, chooses another retry, cancels external work or grants permission to
continue. This is recovery of evidence collection, not autonomous infrastructure recovery.

## Commands and worker protocol

```sh
python3 tools/observe.py init --journal run-01 \
  --cli /opt/horistum/bin/flow-core --source proposal.json --source-kind proposal \
  --requirements outcomes-v2.json > checkpoint.json

python3 tools/observe.py status --journal run-01 --checkpoint checkpoint.json
python3 tools/observe.py append --journal run-01 \
  --event event.json --capture captured-response.bin > next-checkpoint.json

python3 tools/observe.py export --journal run-01 --checkpoint next-checkpoint.json \
  --cli /opt/horistum/bin/flow-core --trust observer-trust.json \
  --key-id observer --private-key /private/observer.pem --out snapshot-01
```

Initialization requires existing version 2 requirements with a real graph digest, run ID,
fresh caller-established challenge and start time. It does not generate or weaken those
requirements. The installed compiler accepts the source and selects the correlation targets.
`status` publishes those digest-bound `invocationTargets` (including declared task `outputs`) and `operationTargets`, alongside
the journal checkpoint. These are references into the existing graph, not a second plan.
Without `--checkpoint`, status is a discovery/diagnostic read; it does not detect rollback
to an older, internally valid prefix. Export always requires an exact retained checkpoint.

The [protocol schema](../tools/observation_host/protocol.schema.json) describes checkpoints
and events. A checkpoint is `{sessionId, sequence, digest}`. Each event contains:

- `formatVersion: 1`, the same `sessionId`, next `sequence` and `previousDigest`.
- A unique event `id`, `type`, and its typed `data`.
- `sourceSha256` and `sourceSizeBytes` binding the separate exact capture bytes.

| Type | Required data | Meaning |
| --- | --- | --- |
| `START` | `invocationId`, `nodeId`, `attempt`, `startedAt`; optional `causedBy` | Record one pending invocation; only one may be outstanding |
| `FINISH` | `invocationId`, `completedAt`, `status`; `errorId` for failure | Complete that invocation from an actual captured result |
| `OBSERVATION` | `id`, `kind`, `status`, `nodeId`, `invocationId`, `resource`, `observedAt`; kind-specific facts/owner/creation or binding/path | Add an immutable resource claim |
| `CLOSE` | `completedAt`, `status`; `errorId` for failure | Explicit observer assertion that the run terminated |

Use `observation_host.journal.event_for(checkpoint, id, type, data, capture)` and
`authoring_host.contracts.canonical(event)` when integrating a Python worker. Other
workers can emit schema-conforming JSON; the CLI strictly parses it and canonicalizes
the event before persistence. The Python `append` API requires that canonical encoding.
Capture bytes are never normalized. UTC timestamps preserve up to nine fractional digits.
Facts accept bounded strings, Booleans and exact integers. Fractional numeric facts are
rejected rather than rounded through a Python float; a string representation requires a
matching independently declared string postcondition.

Final `STATE` claims must follow `CLOSE`. Rejecting earlier state prevents a provisional
capture from consuming the immutable final-observation slot. `CREATION` captures belong
within their invocation interval; a later `FINISH` cannot predate an already recorded creation.

[S2.6 output bindings](RESOURCE_INVOCATION_LINEAGE.md) use `kind: BINDING`, a declared
`binding` and optional structured `path`. They must identify a completed successful
producer invocation at its exact completion time. They may arrive after `CLOSE` under
that original timestamp. One invocation/output/path can name only one resource; the
independent trust grant must include `BINDING`. The installed compiler owns selection
of the actual producer for each consumer, including read retries.

Every append supplies the previous checkpoint. SQLite serializes the comparison and
write in one transaction. Competing events cannot both extend one tip. An exact duplicate
event and capture returns its **original** receipt without adding another event. Reusing
an event identity with changed contents fails. An old duplicate receipt does not advance
the caller to a newer tip; concurrent producers must explicitly reconcile their checkpoints.

## Durability, integrity and publication

Transactions use SQLite rollback journaling with `synchronous=FULL`. An acknowledgement
is returned only after commit; creation and publication also synchronize directories.
Process death before commit leaves the preceding state. Process death after commit but
before acknowledgement is resolved by redelivering the identical event. This assumes a
local filesystem and storage stack that honor locking, atomic rename and synchronization.
Network filesystems, distributed consensus and power-loss-resistant hardware are not certified.

Each entry hashes its domain/version, preceding digest, canonical event bytes and exact
capture bytes. Reads replay and validate the bounded history. A caller-retained checkpoint
detects a restored older prefix as well as changes to the captured context. Retain receipts
outside the journal's rollback domain. Hashes do not authenticate a storage owner or prevent
two independently signed forks. Trustworthy observer operation, protected signing keys,
challenge uniqueness, durable external receipt retention and acceptance of one run history
remain caller responsibilities. A backup restored together with its old receipt cannot
prove that later acknowledged events existed.

Storage directories must be caller-owned and private (`0700`); journal and signing-key
files must be caller-owned regular files without group/other permissions (`0600` typical).
Symlink journal/key files are rejected. This is not isolation against another process with
the same operating-system identity. Raw captures can contain sensitive data: control
capture selection, storage access, retention and backups. Public diagnostics never echo
captures, private key contents or external process output. Private keys are not stored in
the journal or published snapshot; signing uses private temporary files removed afterward.

The caller supplies the public-key trust document independently of the signing key.
The key must match its grant, workflow, resource scopes/types and captured claim kinds.
One key signs a snapshot in this slice. The existing version 2 signature domains and
the compiler's independent authentication remain authoritative; the host does not generate
its own trust grant. Signatures attribute assertions, not observation truth.

Export pins one exact checkpoint, rechecks the installed compiler fingerprint, recompiles
the captured source, verifies graph continuity and evaluates the signed bundle. It reserves
a new private output directory, prepares and synchronizes its contents, then publishes
`snapshot-01/evidence/` by rename. Existing output destinations are never overwritten.
A failed or interrupted preparation may leave a reserved directory, but no published
`evidence/`; retry into a new destination. Published files include original source and
requirements, supplied public trust, signed observations, the compiler review, collection
status and a digest inventory. The inventory and receipts are integrity metadata, not a
new semantic authority. Exit codes are 0/3/4 for satisfied/unresolved/violated assessments,
and 2 for rejected input, storage, signing or compiler failures.

## Bounds and verification

The host admits at most 1,024 journal entries, 256 invocations, 256 resource claims,
64 KiB per event/capture and 8 MiB of logical journal contents. Inputs and exported
bundles are bounded to 2,000,000 bytes each; the SQLite file is capped at 32 MiB.
An oversized export is rejected without publishing a partial evidence directory.
The existing evaluator still limits accepted semantics to sequential workflows, root
failure regions and read-only retries. The host records evidence; it does not certify
arbitrary event order against the graph by itself.

```sh
python3 -m unittest discover -s tools/tests -p 'test_observation_host.py'
python3 tools/verify_observation_host.py --cli /opt/horistum/bin/flow-core
```

Unit tests kill actual writer processes before/after commit, race two writers and mutate
stored records/checkpoints. Installed integration uses fresh collector processes around
real local HTTP readiness, SQLite baseline recovery and credential replacement. It
interrupts collection after an actual repair, retains the missing reply, reconciles that
same invocation and verifies the repair executed once. Separate cases cover missing late
results after closure, stale checkpoints, false final state and altered capture bytes.
This proof is included in both full CI jobs through `verify_authoring_host.py`.

Next: independently deployed observers, durable external checkpoint retention and a
bounded contract for runtime-allocated identities. Cloud execution, distributed
observer transport, arbitrary parallel traces and autonomous recovery remain unproven.
AI-A3, AR-06/AR-07 and F-16/F-20 remain open; EF-09 remains paused.

## Optional deployed collection

[S2.7 deployed collection](DEPLOYED_RUN_OBSERVER.md) adds independent collector and
checkpoint-witness services over private Unix sockets. Its acknowledgements require
both durable records, and `export --witness-socket` requires the exact externally retained
checkpoint. Existing direct commands retain the behavior documented above.

For addresses allocated only during creation, see [runtime resource identities](RUNTIME_RESOURCE_IDENTITIES.md).
Outcome version 3 and observer event version 2 are explicit opt-ins; the historical contracts above retain their semantics.
