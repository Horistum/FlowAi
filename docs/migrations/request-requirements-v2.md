# System automation requirements version 2

The `proposal` command and external authoring host now accept requirements documents
with `formatVersion: 2`. Proposal inputs remain version 1, Intent remains version 2.0,
and the canonical graph is unchanged. The [version 1 schema](../../schemas/request-requirements.schema.json)
and its acceptance semantics remain frozen. Version 1 rejects version 2 features.
Use the separate [version 2 schema](../../schemas/request-requirements-v2.schema.json).

## Selecting an operation by its meaning

Any existing selector may add `parameters`, a conjunction of exact string-literal
matches against canonical semantic parameters. Selection never uses provider-selected
step IDs, implementation names, display descriptions or registry position.

```json
{"capability":"PROVISION","parameters":{"resource":"dev-network"}}
```

The optional workflow name still narrows the selection. A missing parameter or a known
literal mismatch excludes a node. If a reference/expression could match at runtime,
the affected requirement needs review (`FIDELITY_DYNAMIC_SELECTION`); evaluation cannot
quietly certify only the statically matching subset. A definite mismatch on another
filter excludes that node irrespective of filter order. Filters are limited to 16
parameter names and 1000 characters per expected value.

Version 2 allows `capability-count` minimum/maximum zero. For example, selecting
`DEPROVISION` with `parameters: {"target":"shared-network"}` and both bounds zero
forbids that specific operation while allowing removal of owned temporary resources.
Dynamic scope remains review-required. Global capability/effect prohibitions continue
to apply independently. Effect resource labels are semantic categories, not resource IDs.

## Checking the actual value producer

```json
{
  "kind":"value-from",
  "producer":{"capability":"PROVISION","parameters":{"resource":"dev-network"}},
  "consumer":{"capability":"PROVISION","parameters":{"resource":"dev-compute"}},
  "parameter":"stack",
  "path":["network"]
}
```

This checks the `network` field of the consumer's authored `stack` object. The selected
value must directly reference an output of the unique selected producer, and the
authorized graph must contain a matching resolved VALUE edge to that consumer in the
same workflow. Ordering alone does not pass. Every selected consumer must satisfy the
check. Step/output renaming preserves the result when actual ownership is preserved.

`path` is optional and has at most eight nonblank object-field components, each at most
128 characters. Paths traverse literal structured configuration; arbitrary expressions,
runtime objects, field/index projections from a result and structural control scopes
need review rather than a claimed proof. Missing fields and literal placeholders fail.
Multiple selected producers require review (`FIDELITY_AMBIGUOUS_PRODUCER`); an empty
producer or consumer selection fails. At most 256 consumers are checked per requirement.

This proves represented direct value ownership. It does not prove the external resource
exists, that an output is a valid cloud identifier, that a policy succeeded at runtime,
or that an adapter can transfer the value. Those obligations remain separate.

## Migration

Keep existing version 1 files unchanged. For a request needing resource filters or value
ownership, copy its reviewed requirements, set version 2, add the relevant checks, and
review their source quotes and exact scope. Preserve the complete original request and
all earlier constraints. No candidate model may confirm its own requirements.

There are no new CLI flags. The host sends the broadened domain-neutral authoring prompt
on new runs (`bounded-authoring/2`). Captured runs retain their original instructions;
resuming an old run does not silently upgrade its prompt. Frozen corpus evaluation is
unchanged. New examples are explicit reviewer-authored compiler tests, not live AI results.
