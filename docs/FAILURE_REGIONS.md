# Failure regions and bounded observation retries

Intent **3.1** adds explicit failure regions and bounded retries to structured
authoring. Requirements **4** independently check their exact canonical membership
and policy. This is S2.3 of the [system automation direction](SYSTEM_AUTOMATION_DIRECTION.md).
It reuses the existing AST `Try`/`Retry`, canonical graph, availability analysis and
target admission. There is no additional executor, recovery scheduler or graph.

## Version selection and migration

| Surface | Contract |
| --- | --- |
| Intent 2.0 | Existing flat `steps` and defaults remain unchanged |
| Intent 3.0 | Existing branches, values and merges remain unchanged; new kinds are rejected |
| Intent 3.1 | The same ordered body plus `attempt` and `retry`; explicit version required |
| Requirements 1–3 | Existing meanings and admission remain unchanged |
| Requirements 4 | Existing checks plus `failure-region` and `retry-region` |
| Proposal envelope 1 | Admits Intent 2.0, 3.0 and 3.1 |
| Authoring prompt 4 | Captures all three public schemas; saved sessions keep their captured contracts |
| AST / graph / plan | No new execution meaning or version; lowering evidence retains source version 3.1 and checks the complete actual structure |

To migrate an Intent 3.0 document, explicitly select `intentVersion: "3.1"` and
wrap only the operations whose failure belongs to a common handler. Declare the
handler and disposition; the compiler does not infer either from a capability name.
Select requirements version 4 only when adding the new independent checks. Existing
`failure.notify` and `failure.rollback` flags remain unsupported in structured Intent.

Schemas: [Intent 3.1](../schemas/intent-v3.1.schema.json) and
[requirements 4](../schemas/request-requirements-v4.schema.json). Schemas constrain
interchange shape. The compiler owns effects, identity, scope, types and availability.

## Authored constructs

Every statement retains a document-unique ID. Bodies are ordered, not dependency-sorted.

| Kind | Required fields | Meaning |
| --- | --- | --- |
| `attempt` | `id`, nonempty `body`, nonempty `onFailure`, `disposition` | Run the handler only when protected work fails |
| `retry` | `id`, nonempty `body`, `maxAttempts`, `delaySeconds` | Repeat the entire read-only body on failure within the explicit fixed bound |

`disposition: propagate` appends canonical `fail error` after the authored handler.
The failure therefore remains a failure even if handling succeeds. `recover` allows
normal continuation when the handler completes successfully. A handler failure still
propagates outward. Neither form claims that external state was restored correctly.

The handler receives the original `error` binding and values available **before**
the attempt. Results produced inside the failed body are unavailable in the handler:
the failure may have occurred before their production. A recovered continuation
cannot read a success-only value without the existing canonical availability proof.
Bindings named `error`, including declared outputs, cannot replace the original error
inside a handler. Nested attempts retain the existing lexical error binding semantics.

`maxAttempts` is an integer from 1 to 100, counting the initial attempt.
`delaySeconds` is an integer from 0 to 3600 and lowers to the existing fixed-delay
policy. Each operation in a retry body must have known read-only semantic effects.
Changing or unknown effects, nested retries and attempts inside retries are rejected.
No idempotence, deduplication or replay safety is inferred. A retry responds to an
operation **failure**; a successful operation returning `false` does not implicitly
fail, and this construct is not an until-condition polling loop. The selected external
implementation must faithfully implement the operation's success/failure contract.

```yaml
intentVersion: "3.1"
kind: FlowIntentDocument
name: inspect-with-failure-report
workflows:
  - name: main
    kind: CUSTOM
    body:
      - kind: attempt
        id: inspection
        disposition: propagate
        body:
          - kind: retry
            id: readiness
            maxAttempts: 3
            delaySeconds: 5
            body:
              - kind: operation
                id: inspect
                capability: VERIFY
                params: {resource: test-worker, criteria: ready}
        onFailure:
          - kind: operation
            id: notify
            capability: NOTIFY
            params: {subject: inspection-failed, body: "Operator review required"}
```

The existing 64-level / 2,000-statement authoring limits and compiler availability
budgets apply to every new body. Explicit dependencies cannot cross from a protected
body to its failure alternative. Approval and backup coverage follow the same scopes;
an approval inside the failed body cannot authorize handler work.

## Independent region requirements

Requirements remain reviewer-owned, outside the provider prompt. Selectors use
capability, optional workflow and literal semantic parameters, never candidate IDs.
Each selector must identify exactly one operation. Dynamic or ambiguous selection
requires review. Lists contain 1–32 distinct selectors and specify exact operation order.

| Check | Fields | Required evidence |
| --- | --- | --- |
| `failure-region` | `protected`, `handler`, `disposition: PROPAGATE or RECOVER` | One root canonical try region with precisely those protected and handler operations and the declared terminal behavior |
| `retry-region` | `operations`, `maxAttempts`, `delaySeconds` | One canonical retry containing precisely those read-only operations and the exact fixed policy |

Failure checks admit sequential operations and read-only retry wrappers. Retry checks
admit direct operations; they may reside in a root attempt's body or handler. Branches,
local aliases/merges inside checked regions, nested attempts, workflow-level failure
handlers and conditional placement
remain `review-required`. They can be authored where the compiler supports them, but
these checks do not certify a convenient subset of their possible paths. The cumulative
fidelity work budget remains 1,000,000 units. Existing conditional checks also retain
their previous admission limits; version 4 does not widen their proof silently.

The check certifies the selected region's **structure**, not workflow liveness,
successful handling, an atomic transaction, a current ownership claim or completeness
of all effects. Other operations outside that region require their own obligations.
Global capability/effect prohibitions continue to inspect the complete graph.

## Verification and remaining work

The [three independent fixtures](../conformance/failure-regions/corpus.json) cover:

1. Infrastructure provisioning followed by readiness checks; notify and propagate failure.
2. An isolated database migration with a captured pre-attempt backup; restore and verify
   the baseline on failure, then allow normal continuation.
3. Credential rotation followed by consumer checks; collect diagnostics and notify on
   failure, preserving the failure for operator review.

```sh
flow-core proposal conformance/failure-regions/isolated-data-recovery.proposal.json \
  --requirements conformance/failure-regions/isolated-data-recovery.requirements.json \
  --out recovery-review
python3 tools/verify_failure_regions.py --cli flow-cli/build/install/flow-core/bin/flow-core
```

The installed verifier exercises all three cases, altered failure dispositions and
retry bounds, handlers moved onto normal paths, forbidden changing retries, unavailable
partial results and blocked target rendering. It is invoked by the ordinary authoring
host proof. These are manually authored deterministic compiler tests, not live-model
or external infrastructure trials. No provider or scenario name selects production logic.

[Operation outcome assessment](OPERATION_OUTCOMES.md) remains separate. Its version 1
observations do not identify retry attempts or certify execution of a failure region;
contradictory result claims remain unresolved. Actual recovery needs independently
observed state. Automatic cleanup of newly created resources still needs creation and
ownership evidence, including cases where a resource was created before a command
failed without returning its handle. This slice does not infer such handles or cleanup.

The next boundary is attempt-aware observation and recovery evidence: identify the
actual attempts and reached failure region, distinguish partial completion from unknown
state and establish which actions are safe before proposing a subsequent change.
Mutating retries, autonomous continuation, compensation, external atomicity and target
runtime certification remain open. AI-A3, AR-06/AR-07 and live/render gates remain open;
EF-09 remains paused.

[Attempt-aware run evidence](RUN_EVIDENCE.md) adds opt-in outcome contracts version 2
for authenticated invocation history and post-recovery state. Version 1 retains its
previous limits. Production cloud observer integration remains a separate gate.
