# Deployed observer and retained checkpoints (AI-A3-S2.7)

The external observation host can run as a collector service and a separate checkpoint
witness. A worker sends the existing [version 1 events](DURABLE_RUN_OBSERVER.md); the
collector acknowledges only after both the journal and the witness have committed.
Neither service dispatches operations. The compiler and outcome version 2 are unchanged.

## Deployment boundary

This implementation supports Linux, Python 3.10+, local POSIX storage and private Unix
stream sockets. Each service runs independently, with its own process and storage lifecycle.
The witness retains only checkpoints, not source, captures, signing keys or resource values.
Its database must live outside the journal's restore/backup domain. Separate directories
alone do not establish independent physical durability. Use independently managed storage
and never restore the witness together with an old collector backup. This slice tests
independent directory restoration and process death; it does not certify power-loss hardware,
network filesystems, cross-host transport, distributed consensus or a production installation.

All peers use one trusted operating-system UID. Private parent directories, owner-only sockets
and Linux peer credentials restrict local access. They are not isolation between mutually
hostile processes with that UID. The witness trusts its local callers to propose checkpoints;
the collector verifies those checkpoints against the full journal. An authorized malicious
caller can deny service or rewrite its own storage. Retention detects collector rollback and
conflicting prefixes relative to an intact witness; signatures still do not prove observation
truth, and simultaneous witness rollback cannot be detected by this mechanism alone.

Install the external host separately from the installed compiler. Its minimal Python files
are `tools/observe.py`, `tools/observation_host/`, and `__init__.py`, `contracts.py`,
`transport.py` from `tools/authoring_host/`, preserving those package directories. No repository
checkout is required at runtime. Keep code operator-owned; use an explicit installed compiler
for initialization/export and keep independent outcome trust and signing keys outside captures.

## Explicit enrollment and startup

Initialize a fresh run using the existing `init` command and retain its genesis checkpoint.
The genesis binds source, requirements, compiler review and executable fingerprint. Enrollment
accepts only sequence zero with `digest == sessionId`, and refuses an existing witness directory.
A missing witness is an error, never permission to enroll the collector's current tip.

```sh
umask 077
python3 /opt/horistum-observer/observe.py init --journal /srv/observer/run-01 \
  --cli /opt/horistum/bin/flow-core --source proposal.json --source-kind proposal \
  --requirements outcomes-v2.json > genesis.json

python3 /opt/horistum-observer/observe.py enroll-witness \
  --store /mnt/independent-receipts/run-01 --genesis genesis.json

# Run these as separate supervised processes. The socket parent must already be 0700.
python3 /opt/horistum-observer/observe.py serve-witness \
  --store /mnt/independent-receipts/run-01 --socket /run/horistum/witness.sock
python3 /opt/horistum-observer/observe.py serve --journal /srv/observer/run-01 \
  --genesis genesis.json --witness-socket /run/horistum/witness.sock \
  --socket /run/horistum/collector.sock
```

Paths above are examples to provision under the service UID, not automatic directory creation
or privileged installation. Supervise each process independently, start the witness first,
and retain the explicit genesis configuration. The collector refuses startup when the
witnessed prefix is missing or different. Private lock files survive process death; their
OS locks do not. Restart removes only a private socket while holding its service lock.
SIGTERM cleans up the listener; SIGKILL recovery tolerates its stale socket.

The caller remains responsible for unique run challenges, actual observation authority,
storage capacity/retention and truthful worker captures. One collector/witness pair covers
one bounded run, including late captures after closure. No automatic deletion or enrollment
of another run is provided.

## Worker delivery and recovery

```sh
python3 /opt/horistum-observer/observe.py send --socket /run/horistum/collector.sock \
  --event event.json --capture captured-response.bin > receipt.json
python3 /opt/horistum-observer/observe.py inspect --socket /run/horistum/collector.sock \
  --genesis genesis.json
```

The worker retains the exact event and capture before sending it. The event uses its
previous checkpoint, unchanged invocation ID, timestamps and capture digest. Empty START
captures omit `--capture`. A lost response is ambiguous until the identical event is
redelivered; it is never a reason to repeat the underlying operation.

| Failure boundary | Durable state | Required action |
| --- | --- | --- |
| Before journal commit | Previous journal and witness | Redeliver the same command |
| After journal commit, before witness commit | Journal one event ahead | Redeliver exactly that event and capture |
| After witness commit, before reply | Both contain the event | Redelivery returns the original receipt |
| Witness unreachable | No new append can start | Restore witness availability; preserve pending commands |
| Restored collector prefix or same-length fork | Witness contradicts journal | Stop; recover the matching journal/evidence, never reset witness |
| Unknown operation completion | START remains pending | Obtain the real result independently; no synthetic success or redispatch |

`inspect` includes `retainedCheckpoint` and `retentionState`: `CONFIRMED` or
`REDELIVERY_REQUIRED`. The latter reports the persisted, unconfirmed tip but cannot export
it. Exactly one unconfirmed event is possible through the coordinated service. More than
one indicates an uncoordinated writer and is rejected. New commands cannot bypass that gap.
An old duplicate returns its original receipt even after later events; it does not advance
the caller to a convenient latest checkpoint. Conflicting duplicates and competing extensions
are rejected. A witness commit is an assertion about retained history, not completion of work.

Use the service exclusively for appends to an enrolled journal. Legacy `append` remains
available for non-deployed callers; mixing it with the service bypasses coordination and
can intentionally block the enrolled run. The trusted local storage boundary is unchanged.

## Witness-checked export

```sh
python3 /opt/horistum-observer/observe.py export --journal /srv/observer/run-01 \
  --checkpoint receipt.json --witness-socket /run/horistum/witness.sock \
  --cli /opt/horistum/bin/flow-core --trust observer-trust.json \
  --private-key /private/observer.pem --key-id observer --out snapshot-01
```

This requires the selected checkpoint to equal the current witness and the full journal.
It holds the collector coordination lock throughout existing signed, atomic publication.
An unavailable witness, unconfirmed event, old receipt or rolled-back journal blocks export.
The normal outcome exits remain `0` satisfied, `3` unresolved, `4` violated; input/storage
rejection is `2`. No output publication occurs for a retention mismatch. Retention does
not make missing results or incorrect postconditions pass. Existing commands without
`--witness-socket` preserve their caller-retained-checkpoint contract; they make no witness
claim. A published snapshot describes its selected historical checkpoint, not perpetual
freshness. A later consumer needing the latest run state must query the independent witness.

## Service wire contract

Each connection carries one request and one response: a four-byte unsigned big-endian length,
then UTF-8 JSON, bounded to 512 KiB. Ten seconds bounds each peer's complete frame exchange;
partial frames do not reset the deadline. Processing uses bounded journal replay and SQLite
transactions. One request is processed at a time, with a listen backlog of eight. There is
no TCP listener, shell command, client-selected file path, remote export or key management API.

Requests use `formatVersion: 1` and the following exact fields. Unknown fields, duplicate
JSON keys, invalid base64 and malformed events fail. Captures are capped at 65,536 decoded
bytes. Existing journal event, total byte, invocation and publication limits still apply.

| Service | Operation | Additional fields | Result |
| --- | --- | --- | --- |
| Either | `STATUS` | `sessionId` | Witness checkpoint, or collector snapshot with retention status |
| Collector | `APPEND` | `event`, `capture` (base64) | Original event checkpoint, after external retention |
| Witness | `ADVANCE` | `previous`, `following` | One-step checkpoint, atomically compared and retained |

Responses are `{ok: true, result: ...}` or `{ok: false, error: {error:
"OBSERVER_SERVICE_REJECTED", executionAuthorized: false}}`. A connection failure or rejected
reply never proves that no commit happened. Public errors do not echo captures or internal
exceptions. The schema is [service.schema.json](../tools/observation_host/service.schema.json);
semantic continuity remains implemented by the existing journal and witness, not JSON Schema.

## Evidence and next boundary

Python process tests stop a real collector after journal commit and after witness commit,
exercise concurrent writers, unavailability, restart, foreign sessions, malformed frames,
storage guards, rollback and conflicting history. The installed integration repeats the
existing service/database/credential lineage proof with separately installed collector and
witness processes, kills capture immediately after actual creation, reconciles without a
second creation, and rejects restored journal prefixes while preserving late evidence and
negative outcome cases. Both full CI jobs execute it through `verify_authoring_host.py`.

The [runtime-allocated identity proposal](RUNTIME_RESOURCE_IDENTITY_PROPOSAL.md) defines the
next contract boundary. This PR does not activate it. AI-A3, AR-06/AR-07 and F-16/F-20 remain
open; EF-09 remains paused. Live models, production cloud observers and general control-flow
observation remain separate unproven gates.

For addresses allocated only during creation, see [runtime resource identities](RUNTIME_RESOURCE_IDENTITIES.md).
Outcome version 3 and observer event version 2 are explicit opt-ins; the historical contracts above retain their semantics.
