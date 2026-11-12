# Resource producer and creator invocation lineage (AI-A3-S2.6)

Outcome version 2 now admits resource handles supplied by canonical task outputs.
Every reached consumer must use an authenticated binding from the **specific producer
invocation available at that point in the verified run**. Equal resource addresses,
equal attempt numbers and a successful final attempt do not establish that relationship.
The compiler still assesses captured work; it does not dispatch or authorize it.

## Evidence and selection

This activates the existing version 2 `BINDING` observation shape. Requirements, run
observations and signing domains retain their versions and byte protocols. Version 1
retains its historical operation-local binding semantics. No new graph or provider
adapter is introduced.

A binding records:

| Field | Required meaning |
| --- | --- |
| `nodeId` | Exact canonical task that declares the output |
| `invocationId` | That task's actual successful invocation |
| `binding` | Declared output name, also published in `execution.invocationTargets[].outputs` |
| `path` | Optional structured path inside the output; separate from the requirement's operand path |
| `resource` | One exact independently declared scoped identity |
| `status`, `observedAt` | `SUCCEEDED`, at the producer's recorded completion time |
| Capture, signature and context | Existing exact source bytes, caller-owned grant and run/contract/graph binding |

For example, an operand `{target: ref:create.handle}` with requirement path `[target]`
uses the `create` output binding at observation path `[handle]`. The evaluator follows
the canonical VALUE edge and the verified invocation history. It never searches for a
convenient observation with the expected address. Two different addresses for one
producer invocation/output/path are conflicting evidence, even if one matches the goal.

The existing sequential trace reviewer retains which successful invocation supplied
each incoming VALUE edge. A retry starts each iteration from its entry environment;
failed-iteration outputs do not survive into the next attempt. Successful final-iteration
outputs can supply subsequent work where the compiler admits that dependency. A producer
outside a retry can supply every iteration. Entering a failure handler restores the
pre-region environment, so partially completed protected work cannot supply its inputs.
These are checks on an observed trace, not an executable environment or dispatch plan.

Every consumer invocation, including a failed retry, needs its corresponding binding.
Bindings from failed producers, stale observations, unknown invocations, wrong output
paths and unavailable producers remain unresolved. An authenticated binding that selects
a different declared resource is a violation. The final successful attempt cannot hide
missing evidence from earlier attempts.

## Creation is an independent obligation

`CREATED_IN_RUN` still requires an independently declared creator operation, its own
covered resource operand and a `CREATION` claim from its exact actual invocation.
A binding proves an attributed output value; it does not prove that a resource was
newly created. `created: false` violates that requirement. Every other consumer,
including a read and every failed retry, must start no earlier than the creation capture.

A failed creation command may have created a resource. Such a capture remains admissible
and retains its creator invocation; the failed command cannot publish an output binding.
Unknown completion still establishes neither successful output publication nor safe cleanup.
An unreached `WHEN_REACHED` operation needs no fabricated binding or creator invocation.

The version 2 review adds optional fields:

- `results[].resourceLineage`: consumer invocation, actual producer invocation/node,
  output name/path, binding observation ID and observed resource identity, in consumer order.
- `results[].creatorInvocationId`: the independently confirmed creation invocation.
- `execution.invocationTargets[].outputs`: digest-bound correlation metadata from the graph.

Empty lineage and absent creator fields are omitted, preserving version 1 report shape.
Lineage can remain visible in a violated result to explain the actual contradictory value.
It does not change `executionAuthorized: false` or claim global workflow correctness.

## Durable observer integration

The [external observer](DURABLE_RUN_OBSERVER.md) records `BINDING` claims through the
same checkpointed `OBSERVATION` command. It validates the declared output, successful
producer completion, exact timestamp, bounded path and unique output slot. `BINDING`
must be independently included in the signing grant's `kinds`. Run/RESULT trust alone
does not grant output-binding authority.

Late binding and creation captures can be appended after `CLOSE` under their original
invocation and timestamps. Export evaluates the selected journal checkpoint through the
installed compiler. The observer does not reimplement VALUE-edge selection, substitute
an older handle, redispatch work or infer creation from a returned handle.

## Verification and remaining boundaries

```sh
./gradlew :flow-frontends:test --tests '*ResourceInvocationLineageTests' --tests '*RunEvidenceTests'
./gradlew :flow-cli:installDist
python3 -m unittest discover -s tools/tests -p 'test_observation_host.py'
python3 tools/verify_resource_lineage.py --cli /absolute/path/to/flow-core
```

The installed-product proof creates an actual loopback HTTP service, SQLite database
and credential file. Read retries encounter a real HTTP 503, SQLite locking failure or
invalid HMAC presentation injected by the fixture. Each consumer addresses the resource
through the worker's returned handle. Fresh collector processes retain every attempt;
late captures complete the proof without repeating creation. Six variants per domain
cover an older matching handle, wrong path/invocation, missing or false creation, and
successful late reconciliation. Negative snapshots reuse captured work in isolated
journal copies; they do not claim that signed forks are prevented. This suite runs in
both full CI jobs. Conformance also checks output correlation and the published review schema.

Identities remain exact, independently declared and frozen before capture. This does not
admit unknown-at-planning-time resource addresses, arbitrary resource discovery or trust
in native provider responses without an authorized observer. The admitted subset still
excludes version 2 workflow-input bindings, aliases, branches, parallelism and nested
failure/retry regions. Existing expression/type and run-shape boundaries remain enforced.
Signatures attribute assertions; they do not establish observation truth or atomic external
state. Resource generation/replacement under an unchanged address is not modeled.

Next: independently deployed observers, external durable checkpoint retention, and a
bounded contract for runtime-allocated identities. Production cloud execution and live-model
evaluation remain unproven. AI-A3, AR-06/AR-07 and F-16/F-20 stay open; EF-09 stays paused.

The [S2.7 deployed-observer continuation](DEPLOYED_RUN_OBSERVER.md) now provides bounded
local service deployment and independent checkpoint retention. Its runtime identity
[proposal](RUNTIME_RESOURCE_IDENTITY_PROPOSAL.md) remains design only; the exact predeclared
resource requirement in this contract is unchanged.

For addresses allocated only during creation, see [runtime resource identities](RUNTIME_RESOURCE_IDENTITIES.md).
Outcome version 3 and observer event version 2 are explicit opt-ins; the historical contracts above retain their semantics.
