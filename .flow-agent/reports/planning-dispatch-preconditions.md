# AI-A3-S3.7: external runtime precondition exchange

Baseline: merged PR #274, `c34e5701d484b0a7db234f1a2492b1c31e1df485`.

## Problem and resulting behavior

A successful retained preflight cannot protect the gap between observing a
resource and using it. Dispatching after an observer process exits also loses any
process-scoped protection. A missing response after an external commit must not
be interpreted as permission to execute the same operation again.

The explicit `dispatch_planning_worker.py` entrypoint now connects one independently
reviewed planning selection to an operator-configured external runtime. It does
not run another model turn. The compiler admits complete effect-operand coverage
before the runtime starts. The runtime independently authorizes its operation,
reserves its dispatch identity and holds its own protection across observation,
compiler assessment and conditional use. The integration does not implement
operations, walk the canonical graph, select a provider, schedule or retry work.

The request binds the selected candidate, proposal, canonical artifact retained by
independent review, compiler identity, review receipt and fresh precondition
contract. Expected owners, instances and facts remain private. The installed
compiler authenticates the exact signed snapshot bytes and retains its existing
semantic authority. The host also compares the actually published workflow plan
set byte-for-byte with independent review; the preconditions command does not
publish a separate canonical artifact. Source and graph identities must agree.

Before sending an assessment, the host revalidates the original and captured
inputs, runtime exchange and compiler evidence, then checks current expiry.
The runtime checks its own clock and authorization again within its protected
operation. No compiler result grants execution authority or establishes provider
truth. A random post-assessment nonce binds the final runtime result to the
specific outgoing assessment, preventing a premature/substituted result from
becoming a completed exchange.

## Process and failure boundaries

The duplex transport uses exact JSON-line messages, bounded individual frames,
bounded combined I/O and one monotonic deadline spanning observation, compilation
and the final response. It inherits only selected credentials, uses no shell and
terminates the process group on every exit. A runtime must independently abort
uncommitted work on EOF/timeout. These controls do not sandbox an untrusted binary
or undo a previously committed external effect.

The host writes and synchronizes `dispatch-intent.json` before process creation.
It publishes a completed receipt only after verified messages, clean process exit
and final input checks. A partial directory never starts another process. Complete
recovery verifies historical evidence without calling the model, compiler or
runtime. A runtime-reported completion remains distinct from independently verified
outcomes; old success is not a fresh dispatch authorization.

The existing planning run lock remains fail-fast. Competing local preparations can
be rejected before an external reservation; this is separate from provider-side
concurrency. The runtime must deduplicate the dispatch ID across callers and output
directories. The installed proof exercises that external boundary with separate
processes, rather than mistaking a local planning lock for a provider lock.

## Behavioral validation

All 104 worker tests pass locally, including 21 new dispatch/transport tests.
They cover strict frames, identity and plan substitution, tampered snapshots and
receipts, deadline/output bounds, descendant termination, current expiry and clock
rollback, changed inputs before a positive response, runtime authorization denial,
failure after a response and recovery without any additional process.

The installed proof passes 17 cases using the actual product compiler and public
planning, review and dispatch CLIs. A separate test-only runtime holds a SQLite
transaction over its own authorization, observed resources and actual data copy.
Independent assertions inspect payloads and committed effects after each case:

- A valid copy commits once; complete recovery leaves the effect count unchanged.
- Reusing the dispatch ID from a different host directory is rejected externally.
- A competing writer cannot modify the resource during the protected interval,
  but can write after the transaction ends.
- A second concurrently invoked runtime is rejected while the first retains its
  transaction; exactly one effect commits.
- Owner transfer, instance replacement, state drift, wrong authorization, omitted
  destination coverage and missing/stale observations commit no effect.
- A positive assessment that expires before external use is rejected by the
  runtime's actual clock; the host's saved positive status cannot bypass expiry.
- A crash before assessment leaves no effect. A crash after commit leaves one
  effect and no completed host receipt. Reopening either directory never invokes
  the runtime again.

The worker peer is scripted. The runtime is an isolated local fixture whose
operation, authorization store and SQLite locking are not part of production host
code. These results prove the exchange and its protected local consumer, not live
model reasoning or a commissioned cloud implementation.

The local general tooling suite runs 495 tests: 483 pass and the same 12 existing
deployed-observer service tests encounter the environment's AF_UNIX restriction.
All remain enabled in CI. Flow Agent validation/context generation and diff checks
pass. All 326 installed conformance checks and all nine existing installed planning
preflight cases pass. The semantic corpus and artifact versions remain unchanged.
Final exact-head and merge-candidate full CI plus adapter certification
must pass; final revision evidence is recorded in the PR.

## Scope and next acceptance

This slice implements a bounded external assessment exchange, not a workflow
executor or independent effect-verification service. It requires a single retained
canonical artifact and supported literal existing-resource bindings. Arbitrary
runtime binaries, unsupported plans and deployments without real concurrency
protection cannot be promoted by this protocol.

Next work is commissioning concrete trusted runtime consumers and independently
verified live portfolio/outcome acceptance. AI-A3, AR-06 and AR-07 remain open,
EF-09 stays paused and no target-support status changes.
