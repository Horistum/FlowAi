# Attempt-aware run and recovery evidence (AI-A3-S2.4)

Outcome contracts **version 2** connect authenticated invocation history to the
accepted canonical graph, per-attempt resource results and post-recovery state.
The `outcomes` command assesses captured work. It does not dispatch operations,
schedule retries, choose recovery actions or authorize continuation.

## Version selection

Use `formatVersion: 2` consistently for requirements, bundles, trust and observation
payloads. Existing version 1 schemas, signing bytes and operation-local historical
assessment remain supported. Version 1 does not accept version 2 extension fields,
even when empty or null. Intent 3.1, request requirements 4, the authoring prompt,
AST and execution plan versions do not change in this slice.

The published version 2 schemas are:

- [Outcome requirements](../schemas/operation-outcome-requirements-v2.schema.json)
- [Observation bundle](../schemas/operation-observation-bundle-v2.schema.json)
- [Caller-owned trust](../schemas/operation-observation-trust-v2.schema.json)
- [Resource observation](../schemas/operation-observation-v2.schema.json)
- [Run observation](../schemas/operation-run-observation-v2.schema.json)
- [Outcome review](../schemas/operation-outcome-review-v2.schema.json)

The existing CLI is unchanged:

```sh
flow-core outcomes proposal.json --source-kind proposal \
  --requirements outcomes-v2.json --observations observations-v2.json \
  --trust trust-v2.json --out review
```

Missing evidence exits 3 (`UNRESOLVED`), a demonstrated requirement violation exits
4 (`VIOLATED`), satisfied declared expectations exit 0, and invalid inputs exit 2.
A demonstrated violation takes precedence over unresolved evidence. The report
retains both. A satisfied expectation can explicitly describe a failed operation or
a propagated failure: inspect `observedStatus`, the execution status and recovery
path rather than treating exit 0 as workflow success.

## Independent requirements

Version 2 retains exact scoped resource identities, semantic operation selectors,
resource operand mapping, ownership/creation obligations and scalar postconditions.
Every operation additionally declares an `evidence` policy:

| Field | Values and meaning |
| --- | --- |
| `status` | `SUCCEEDED`, `FAILED`, or `COMPLETED` (either known terminal result) for the last invocation |
| `presence` | `REQUIRED` by default; `WHEN_REACHED` admits absence only after a closed, valid history proves the operation was not reached |
| `postconditions` | May be empty when only the recorded result is required; nonempty facts require an authenticated final state capture |

A retry retains **every** invocation and its failed or successful result. The last
invocation is selected only after checking the whole bounded history; no arbitrary
newest-record selection occurs. Every task in the graph must be covered by at least
one resource declaration. This does not independently establish complete operand
coverage of a task that changes several resources. Approval events are recorded but
do not authenticate a human approval or grant execution permission.

`execution` declares one workflow, its expected terminal `status` (`SUCCEEDED`, `FAILED`, or `COMPLETED` for either known result), and optional
`recoveries`. Each recovery uses independently authored ordered `protected` and
`handler` semantic selectors, `disposition`, `expected` path and
`verificationOperations` (outcome requirement IDs). The existing failure-region
fidelity authority checks exact canonical region membership and disposition.

| Expected path | Required evidence |
| --- | --- |
| `NOT_NEEDED` | Protected work succeeded and the handler was not invoked |
| `PROPAGATED` | Protected work failed, the handler completed, and the original correlated error remained the run failure |
| `RECOVERED` | Protected work failed, the handler completed, and required read-only handler verification has matching independently declared postconditions |
| `RECOVER_IF_NEEDED` | Either normal success without a handler, or the same verified recovery obligation as `RECOVERED` |
| `PROPAGATE_IF_NEEDED` | Either normal success without a handler, or handling followed by propagation of the original error |

`RECOVERED` and `RECOVER_IF_NEEDED` require at least one verification operation with
expected success and nonempty postconditions. For one contract covering both healthy
and recovered runs, use `COMPLETED` for protected operation status and `WHEN_REACHED`
for handler operations; recovery verification is mandatory whenever the handler runs.
An unreached handler is reported as not needed, never as successful recovery. Verification must occur after changing handler
work. Merely entering the handler, returning zero or reporting that a restore command
finished does not establish recovery. A handler failure is reported as `FAILED`, with
both the original triggering error and the handler error retained.

All nonempty operation postconditions in version 2 require a STATE observation **at
or after run completion**. Earlier state, including an earlier successful attempt,
cannot prove the state after recovery or later mutations. This conservative boundary
avoids implicit compensation inference; it requires a fresh final capture even when
a producer believes later work was unrelated. Multiple final captures still do not
form an atomic external snapshot and do not exclude changes after capture.

## Observer protocol

The bundle contains resource `observations` and an optional signed `execution`
envelope. An absent execution record is unresolved. Envelopes retain the existing
`keyId`, `payload`, `signature`, `source` base64 contract. Captures contain response or
log bytes; the signed payload binds their SHA-256 and exact size. No raw captures or
secret values are copied into the review.

Resource payloads add `invocationId`; the claim slot is now kind, node, resource,
binding/path and invocation. A RESULT must match the signed run invocation's node,
terminal status and completion time. Every attempt needs its own RESULT, including
failed attempts. Changing invocations require matching ownership no later than their
start. Creation is still an explicit claim, may accompany a failed command, and must
belong to the selected actual creator invocation. A failed command never implies that
nothing changed. An unknown result remains unresolved and cannot trigger inferred
recovery or safe cleanup.

A run payload records:

- Exact requirements digest, graph digest, run ID, challenge and workflow.
- Caller-established start time, completion time, explicit `complete`, status and
  optional terminal `errorId`.
- Ordered `invocations`: unique ID, canonical node ID, retry `attempt` (including the
  initial attempt), start/end times, status, failed invocation `errorId`, and handler
  `causedBy` pointing to the actual final triggering invocation.

Error IDs are opaque observer correlations. They do not prove native exception object
identity, stack preservation or exact error-text equivalence. Failed invocation IDs
and error IDs must be unique. Retry-body operations share the iteration number and
the entire body restarts after failure. The original triggering invocation stays the
cause for every handler invocation, including attempts within a handler retry.

Events must be sequential, within the run interval and not in the evaluation future.
Fixed minimum retry delays are checked. No extra events may follow a terminal failure
or successful completion. Incomplete, truncated, conflicting or unknown histories
remain unresolved. A complete assertion comes from the trusted observer; signing
does not establish completeness or truth independently of that observer.

For resource observations sign:

1. UTF-8 `Horistum operation observation`, NUL, `v2`, NUL.
2. Four-byte unsigned big-endian length of the ASCII key ID.
3. Key ID bytes, then exact original strict-UTF-8 payload bytes.

For run observations replace the domain text with `Horistum operation run observation`.
The JVM helpers are `OperationObservationAuthentication.signingBytes(key, bytes, 2)`
and `runSigningBytes(key, bytes)`. Separate domains prevent signature substitution.
The caller supplies Ed25519 public keys independently. Run signing requires a grant
with the exact workflow in `workflows` and scope/type coverage for every declared
resource. Ordinary resource grants do not implicitly authorize run attestations.

The pending review publishes digest-bound `execution.invocationTargets` for correlating
actual canonical task and approval IDs. This list is correlation metadata, not a second
execution graph, executable plan or authorization. Resource results also retain the
selected canonical IDs. Producers must bind their captured events to this reviewed
compilation, never infer node IDs from command names.

## Admission limits

The first version checks a **single sequential workflow** with tasks, approvals,
root try regions and bounded read-only retries at root or inside a try body/handler.
It checks both the reached path and the supported shape of unreached alternatives.
Branches, parallelism, loops, nested tries/retries, local aliases, workflow failure
handlers and multiple workflows remain unresolved. Runtime interpretation does not
expand canonical semantics or target admission.

Version 2 currently requires literal exact resource addresses. Dynamic handles and
bindings need a subsequent producer-invocation lineage proof; node-only version 1
binding evidence cannot be reused across attempts. The public limit remains 64
resources, 64 outcome operations, 256 resource claims and 64 KiB per signed payload
and source. A run contains at most 256 invocations; at most 32 recovery obligations
are admitted. Graph, selector, structure and fidelity work have bounded budgets.

`executionAuthorized` remains false and `workflowExecutionCorrectness` remains
`unassessed`. The separate `execution` result covers the admitted observer trace and
declared recovery postconditions, not global workflow correctness, external atomicity,
complete ownership, trustworthy human approval, universal rollback or cloud support.
Key distribution, challenge uniqueness, observer correctness, revocation, crash-safe
capture and durable run storage belong to the external host.

## Verification and next boundary

```sh
./gradlew :flow-cli:installDist
python3 tools/verify_run_evidence.py --cli flow-cli/build/install/flow-core/bin/flow-core
```

The [independent portfolio](../conformance/run-evidence/corpus.json) uses the same
production evaluator for actual isolated local operations:

1. Failed HTTP readiness probes, starting a loopback service, and fresh readiness checks.
2. Failed SQLite state checks, copying an independent baseline into a database, and
   fresh queries checking the recovered data.
3. Failed HMAC authentication, replacing a local test credential, and fresh authentication.

Only the test harness performs these operations. It captures actual results, uses an
ephemeral observer key and mutates evidence to test omitted attempts, incomplete runs,
wrong failure causes, pre-completion state, invalid signatures and false postconditions.
These tests establish local runtime-to-observer-to-review integration; they do not
certify an Azure/Jenkins provider, production recovery, a live model or an external
observer deployment. The fixture approval explicitly claims no human approval.

[S2.5 durable external observation](DURABLE_RUN_OBSERVER.md) now provides transactional
capture, process-restart reconciliation and signed checkpoint export through this same
evaluator. Producer-invocation lineage for dynamic handles and deployed production
observers remain next boundaries. AI-A3, AR-06/AR-07, F-16/F-20 and live/render gates
remain open; EF-09 remains paused.
