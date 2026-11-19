# Independently observed outcomes of a planning dispatch

An operator can declare the facts an independent observer should be able to verify
after an external dispatch. For example, a data copy can require the destination
to contain a specific snapshot even if the runtime reports success. The operator
retains two receipts. The dispatch receipt records what the runtime reported. The
outcome receipt records the compiler's assessment of the declared postconditions
against signed observations.

## Before dispatch

Add this optional member to the existing private [dispatch settings](PLANNING_DISPATCH_PRECONDITIONS.md).

```json
"outcomeReview": {
  "policy": "/private/reviewer/copy-policy.json",
  "trust": "/private/reviewer/outcome-trust.json"
}
```

The policy contains the four reviewer-owned members of an [operation outcome v1](OPERATION_OUTCOMES.md)
contract. The installed data-copy example uses this [reviewer-owned vector](../conformance/planning-dispatch-outcomes/data-copy.policy.json).

```json
{
  "formatVersion": 1,
  "reviewerId": "independent-data-reviewer",
  "resources": [
    {"id": "copy", "identity": {"scope": "storage-test", "type": "dataset", "key": "isolated-copy"},
     "disposition": "MANAGED", "owner": "data-team"}
  ],
  "operations": [
    {"id": "copied", "selector": {"capability": "DATA_SYNC"}, "resource": "copy",
     "parameter": "destination", "postconditions": {"payload": "retained-data"}}
  ]
}
```

The trust file uses the existing operation-observation trust schema. Provision it
and its private signing keys independently of the model and the runtime. For the
example, grant RESULT, OWNERSHIP and STATE for the relevant scope and type. All
grants on this path use canonical Ed25519 SubjectPublicKeyInfo DER. A key present
in the dispatch precondition trust cannot also be an outcome key, even under a
different key ID. Separate key custody and truthful access to the actual system
are deployment responsibilities.

Run the normal dispatch command. Before the runtime starts, the host captures the
policy and trust, and asks the installed compiler to assess the selected proposal
with the empty observation bundle. Each declared target must resolve to a concrete
operation and resource waiting for evidence. Invalid input, ambiguous selectors or
an already violated boundary stop the invocation. For a completed compiler review
that blocks observation, the dispatch CLI retains an `outcome-policy-blocked`
receipt without reserving or starting the runtime. It returns 3 for unresolved
obligations and 4 for a violation. Configure these obligations
before the dispatch. A past dispatch without them cannot acquire a retrospective
outcome policy. Worker commissioning and recovery are tied to the exact adapter
revision as described in [brokered planning](BROKERED_AGENT_PLANNING.md).

## After a completed dispatch

Use the same planning environment, configuration and original evidence paths.
The following Bash array identifies one existing dispatch.

```sh
review=(/absolute/worker-venv/bin/python /absolute/FlowAi/tools/review_dispatch_outcomes.py
  --config /private/planning/config.json --run /private/planning/run-001
  --review-requirements /private/review/requirements.json --review-out /private/review/completed
  --dispatch-config /private/change/dispatch-settings.json --dispatch-out /private/change/dispatch-001)

"${review[@]}" request > /private/audit/request.json
# Supply the request to the independently operated observer. Retain its exact signed bundle.
"${review[@]}" assess --observations /private/audit/observations.json --out /private/audit/review-001
```

`request` verifies the completed exchange and prints its dispatch ID, runtime ID,
receipt ID, request and decision digests, selected node/resource pairs, graph,
run ID, challenge, lower time bound and exact bound contract digest. The runtime
and observer requests omit expected fact values, expected owners and outcome trust.
The host computes the run ID from the external request digest and derives the
challenge from the completed result, including the decision digest. On this opt-in
path the decision nonce commits to a fresh random salt and the exact bytes of both
the policy and the trust file. Recovery recomputes the commitment. The external
wire format and the runtime's decision-digest check are unchanged.
It uses the sent assessment time as the lower bound. Binding the four run fields
preserves the original policy's numeric literals.

The observer must correlate the dispatch and decision with an independent record
of the operation, then read the affected resource or its recorded effect. A
matching resource name or the runtime's completion message alone cannot establish
what happened in that invocation. Where the system cannot provide correlation,
omit the unverified records. Produce the existing signed outcome v1 bundle; its
claims include the exact contract digest, graph, run ID and challenge from the
request. The collector runs under operator control and receives no signing keys
from Horistum. The SQLite collector in the test suite is an example for that
fixture's audit schema.

`assess` accepts a captured bundle in a private file outside the planning,
dispatch and review directories and discovery grants. It verifies the original
dispatch, calls the installed `outcomes` command, and compares the selected graph,
typed source, published workflow plan set, all three input hashes, and compiler
exit status. The receipt in `outcome.json` pins the verified dispatch and compiler
artifacts. The detailed per-operation codes and observation IDs are in
`compiler/operation-outcome-review.json`.

| Exit | Meaning |
| --- | --- |
| 0 | Authenticated observations satisfy all declared postconditions at the assessment time. |
| 3 | Evidence is missing, unauthenticated, stale or otherwise unresolved. |
| 4 | The compiler found a violated postcondition or boundary. |
| 2 | The inputs, retained evidence or compiler process could not support an assessment. |

Repeating the assessment using a complete review directory verifies its historical
receipt without starting a process. A partial directory cannot start another
compiler. A new review directory can assess a different capture for the same
completed dispatch. Neither command can start a model, observer or runtime,
reconcile an uncertain dispatch, or grant execution authority. A satisfied result
covers the declared facts, resource bindings and observation interval. Provider
commissioning, live portfolio acceptance, and overall workflow execution
correctness require their own evidence.

For an executable example using Kubernetes-native audit evidence and a separately
authorized GET, follow [ConfigMap provider commissioning](KUBERNETES_CONFIGMAP_DISPATCH.md).
