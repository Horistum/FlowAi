# Precondition assessment at an external runtime boundary

AI-A3-S3.7 connects the selected planning candidate and its independent goal review
to an explicitly configured external runtime. The runtime retains its own
authorization and concurrency protection while the installed compiler assesses
fresh signed observations. A saved successful preflight is not used as permission.

The Python integration performs one bounded request/observation/assessment/result
exchange. It contains no operation implementation, scheduler, retry policy, lock
manager or target adapter. The runtime must independently approve and implement the
exact selected plan. This integration does not certify that implementation or make
an unsupported target renderable. The compiler remains the only precondition
evaluator and always reports `executionAuthorized: false`.

## Prerequisites and explicit invocation

Prepare a completed [planning selection and independent review](BROKERED_AGENT_PLANNING.md).
Prepare fresh independent [preconditions and observer trust](OPERATION_PRECONDITIONS.md).
The compiler first checks complete canonical effect-operand coverage without
launching the runtime. Its published workflow plan set must exactly match the
independent review's bytes; graph and typed source must also match. The request
pins the canonical artifact retained by that review. This entrypoint requires a
single canonical artifact; multi-workflow dispatch is not implemented.

Install `tools/requirements-planning-worker.txt` in the existing dedicated
environment. Put the following settings outside every worker-readable root, the
planning run and the independent review. All input and output paths are absolute.

```json
{
  "formatVersion": 1,
  "requirements": "/private/change/preconditions.json",
  "trust": "/private/change/observer-trust.json",
  "dispatch": {
    "id": "change-42-attempt-1",
    "runtimeId": "approved-runtime",
    "authorizationRef": "change-control/42"
  },
  "runtime": {
    "argv": ["/absolute/trusted-runtime", "--configuration", "/private/runtime.json"],
    "credentialEnv": ["RUNTIME_TOKEN"],
    "timeoutSeconds": 90
  }
}
```

The three dispatch identifiers contain 1–128 ASCII letters, digits or `._:/-`,
starting with a letter or digit. `authorizationRef` is an opaque reference to an
external authorization, not proof that authorization exists. The runtime must
validate it independently. An operator selects the executable; a model cannot
choose it or invoke this entrypoint through the planning tools.

```sh
JAVA_HOME=/absolute/jdk-25 /absolute/worker-venv/bin/python \
  /absolute/FlowAi/tools/dispatch_planning_worker.py \
  --config /private/planning/config.json \
  --run /private/planning/run-001 \
  --review-requirements /private/review/requirements.json \
  --review-out /private/review/completed \
  --dispatch-config /private/change/dispatch-settings.json \
  --out /private/change/dispatch-001
```

Use this command only with a trusted, commissioned runtime that implements the
protocol below and is independently authorized for the selected plan. The example
does not designate a built-in production runtime. The SQLite consumer under
`tools/tests_worker/` is test-only and must not be deployed as a provider adapter.

## External runtime obligations

Before publishing observations, the runtime must:

1. Match the request to independently retained authorization for the exact
   dispatch ID, runtime, proposal, canonical artifact, goal review, compiler and
   precondition contract. It must establish that its approved implementation
   actually implements that plan; accepting hashes alone does not establish this.
2. Reserve the dispatch ID durably across concurrent callers and processes. A new
   host output directory must not allow the same operation to execute twice.
3. Acquire the runtime's protection for **all** relevant resources and predicates,
   including shared read inputs. Keep it through observation and use of the
   assessment. Use an actual transaction, lock or provider conditional operation;
   an observation timestamp alone does not prevent concurrent replacement.
4. Read actual state and sign it using the existing precondition protocol. Do not
   derive observed values from expectations. The request supplies addresses and
   assessment pins, never expected owners, instance IDs or facts.

After receiving an assessment, the runtime must verify every identity and digest,
require `currentState: SATISFIED`, compare its trusted clock to `checkedAt` and
`validUntil`, recheck its authorization and commit only while its protection still
holds. If protection was lost or cannot cover the operation, reject it. The
assessment is a necessary condition, not an execution grant. Current authorization,
locks, retry behavior and actual effects belong to the runtime.

EOF, timeout, a missing assessment, invalid framing or a non-satisfied assessment
must abort uncommitted work. The runtime must enforce its own finite deadline and
release resources on failure. The host also terminates its process group. Once a
remote system commits, terminating a process cannot undo the operation; an absent
completion response is therefore indeterminate.

## Exchange version 1

Messages are UTF-8 JSON objects terminated by one newline, with exact fields and
no duplicate keys. Every frame is at most 2,000,000 bytes. The combined sent bytes
and received stdout/stderr are at most 12,000,000 bytes. Stderr is bounded and
discarded; stdout is exclusively the protocol. Trailing output, nonzero process
exit and an incomplete final frame invalidate completion.

`timeoutSeconds` is an integer from 1 through 120. One monotonic deadline covers
the whole runtime exchange, including compilation and identity checks between
messages. Compilation uses the smaller remaining runtime and compiler timeout.
No shell is used. The runtime gets an empty working directory, a temporary HOME,
system PATH, UTF-8 locale and only explicitly selected credential variables.
Loader/launcher variables cannot be credential overrides. This limits accidental
inheritance; it is not a sandbox for an untrusted executable.

Digest fields use SHA-256. Host JSON messages are canonicalized by sorted keys,
compact separators, literal UTF-8 and a final newline. `requestSha256` and
`decisionSha256` cover those exact host message bytes, including the newline.

| Direction / kind | Required content in addition to `formatVersion: 1` |
| --- | --- |
| Host → runtime: `observe-for-dispatch` | `dispatchId`, `runtimeId`, `authorizationRef`, `candidateId`, `proposalSha256`, `canonicalPlanSha256`, `fidelityReviewSha256`, `compilerDigest`, `preconditions` |
| Runtime → host: `observations` | `dispatchId`, `requestSha256`, `snapshots` (a string preserving the exact signed bundle JSON bytes) |
| Runtime → host: early `rejected` | `dispatchId`, `requestSha256`, `reason` (`authorization`, `conflict` or `unsupported`); then exit zero |
| Host → runtime: `assessment` | `dispatchId`, `requestSha256`, `assessmentSha256`, `snapshotsSha256`, `currentState`, `code`, `evaluatedAt`, `checkedAt`, `validUntil`, `executionAuthorized: false`, `nonce` |
| Runtime → host: `result` | `dispatchId`, `requestSha256`, `decisionSha256`, `status` (`completed` or `rejected`), `receiptId`; then exit zero |

`preconditions` is the existing observer request: `formatVersion`,
`contractSha256`, `graphDigest`, `assessmentId`, `challenge`, `issuedAt`,
`expiresAt` and `operations`. Each operation has `requirementId`, `nodeId` and
literal `resource`. The runtime must preserve this request's pins in its signed
snapshot claims. See the existing [signing contract](OPERATION_PRECONDITIONS.md).

The compiler receives the exact snapshot string bytes. The host verifies its
source receipt, workflow plan set and assessment against the retained independent
review, then rechecks inputs and current freshness before sending an assessment.
An expired assessment has `currentState: UNRESOLVED` and `validUntil: null`.
`nonce` is a fresh 64-character hex value created after assessment, so a premature
or substituted final result cannot match the outgoing decision digest.
`receiptId` is a runtime-owned identifier of 1–128 letters, digits or `._:-`,
starting with a letter or digit. It is not independent proof of an effect.

## Durable state, recovery and exit status

The host exclusively creates the private output directory, captures all inputs,
performs admission and writes/fsyncs `dispatch-intent.json` **before** process
creation. It retains the request, observations, exact snapshot bundle, compiler
artifacts, outgoing assessment and runtime result. It publishes `dispatch.json`
only after the complete exchange, clean process exit and final input checks.

| CLI exit | Meaning |
| --- | --- |
| `0` | A complete retained exchange reports external completion. Actual outcomes are not independently verified. |
| `3` | Canonical preconditions or optional outcome-policy admission are unresolved, or the runtime rejected authorization, concurrency, expiry or use. |
| `4` | The compiler assessed a precondition violation or a violated boundary during optional outcome-policy admission. |
| `2` | Invalid inputs, changed evidence or incomplete/uncertain process state. Reconcile with the runtime before deciding what to do. |

Reusing the same command and directory verifies the complete historical receipt
without starting a model, compiler or runtime. It does not send a new assessment,
extend validity or repeat work. Unlike preflight recovery, successful dispatch
recovery reports a past operation, not present resource readiness.

Any existing incomplete directory blocks a new invocation, even if process
creation failed or the runtime committed before its reply was lost. Do not remove
it or create another output directory to force a retry. Reconcile the durable
dispatch ID with the runtime's own records. Recovery does not synthesize missing
completion evidence or automatically query/repair the external system.

The runtime and all private input/evidence directories must be trusted and
protected. A receipt with `runtime-reported-completed` records what that runtime
reported. `executionAuthorized` and `outcomeIndependentlyVerified` remain false;
post-run outcome verification is a separate boundary.

## Validation and roadmap scope

The installed proof uses the real planning/review/dispatch CLIs and compiler with
a scripted worker and a separately launched SQLite runtime. It copies actual data
inside a transaction held from observation through commit. Independent assertions
read the database after each scenario, including owner/instance/state drift,
authorization mismatch, missing coverage, stale evidence, expiry at use, competing
writers, duplicate concurrent invocations and crashes before/after commit.
Recovery must not launch another process or create another committed effect.

Host tests additionally exercise input and receipt substitution, strict framing,
byte/time limits, descendant termination and historical recovery. Both full CI
paths run the installed proof through `verify_planning_worker.py`.

This closes the bounded external assessment exchange and its transactional local
proof. Provider-specific runtime commissioning, independently verified live
portfolio acceptance and live outcome evidence remain next work. It does not close
AI-A3, AR-06 or AR-07 or promote target support.

To declare an outcome policy before dispatch and independently check observed
effects afterward, see [independent dispatch outcomes](PLANNING_DISPATCH_OUTCOMES.md).
