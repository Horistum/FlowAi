# Observed decisions in authoring

For goal-first authoring without mandatory tables, use the opt-in
[goal context](GOAL_FIRST_DISCOVERY.md). Decisions described here are optional in that
mode; their checks remain model-derived consistency checks, not independent evidence.

Investigation version 4 connects typed observed state to obligations on a candidate
plan. For example, `exists: true` plus `ownership: "shared"` selects reuse constraints;
`exists: false` plus `ownership: "unallocated"` selects creation and recovery constraints.
The same mechanism can select migration versus inspection from a database revision.

This is authoring-time selection. It does not add a workflow graph, runtime predicate
engine or provider adapter. The host matches exact source values; the existing
canonical compiler evaluates selected requirements, dependencies and control regions.

## Protocol

Select `investigation.formatVersion: 4` in the existing host configuration. Independent
requirements must explicitly be confirmed version 5. Public requirements-v5 check
schemas are included in the provider output contract, without caller expectations.
Version 3 remains available with its original wire shape and meaning. The additional
public schema counts toward the existing conservative input reservation. Configure
an adequate explicit budget; the host never raises it automatically.

`interpretation` contains `summary`, `bindings` and `decisions`. Bindings use the v3
contract and may be empty when decisions are present. Each decision contains:

- `id`, `sourceQuote`, `meaning` and `rationale`: the proposed interpretation, citing
  the original request. It remains model-proposed until separately reviewed.
- `inputs`: 1–8 named facts. Each has `id`, `factId` and the existing source selector
  (`root`, `recordPath`, `where`, `valuePath`). The host verifies the exact extraction
  and a unique matching delivered record, including source provenance when available.
- `cases`: 2–8 alternatives, each with `id`, `when` and `obligations`. `when` must name
  every input and gives its exact expected scalar. An obligation has `id` and an
  existing requirements-v5 `check`. The compiler owns check semantics and validation.

Example fragment, with input source selectors omitted for brevity:

```json
{
  "id": "reuse",
  "when": {"exists": true, "ownership": "shared"},
  "obligations": [
    {"id": "no-create", "check": {"kind": "forbid-capability", "capability": "PROVISION"}},
    {"id": "no-delete", "check": {"kind": "forbid-capability", "capability": "DEPROVISION"}}
  ]
}
```

Complete examples are in [the conformance portfolio](../conformance/observed-decisions/).
The provider cannot send `selectedCase` or approval. Exactly one alternative must
match all observed inputs. No match, multiple matches, missing/invalidated facts or
multiple qualifying records produce a blocked assessment. Missing records never
mean absence. Equality distinguishes booleans, integers, strings and null; floats,
arrays and objects are unsupported decision values. Integers are bounded to the
exact JSON interoperability range; strings to 1000 characters. There are at most
8 decisions, 32 obligations per case, 256 obligations across all alternatives and
256 total derived compiler requirements.

## Review, continuation and receipts

The existing `run`, `resume` and `review-discovery` commands are unchanged. V4 records
`authoring-investigation/4`, `bounded-authoring-host/6` and review packet/assessment
version 2. The separate caller review document remains version 1 and identifies the
exact run and packet digest. Existing captured runs are not migrated.

Before review, the host records every alternative, the selected case, exact input
values, source paths and digests, and derived requirement IDs. The complete Intent
is reviewed together with the interpretation. Acceptance appends only the selected
case's checks to unchanged independent requirements and compiles that exact plan.
The final receipt ties each selected obligation to its canonical graph assessment.
Original evaluation scopes are preserved; the host does not change a check to make
an unsupported control structure pass. Selected-operation value checks establish
parameter/producer continuity; failure and retry checks establish separate structure.

A blocking caller decision pauses for an explicit answer via `resume`, then still
requires a separate candidate review. Changed observations invalidate the run before
or after compilation. A fresh run may select another alternative, but needs a new
review; approval never transfers to it. Review spends no model call and cannot
silently repair a rejected candidate.

## Evidence boundary

The model proposes the input selection and conditional policy. Separate review must
assess missing alternatives, over-narrow predicates, ownership and the goal itself.
Matching a source field cannot prove its meaning, source completeness or permission.
Compiler acceptance proves only the retained obligations on the accepted graph.

A network observed absent may appear before execution. A successful `PROVISION`
operation may update an existing resource unless a provider enforces create-only
preconditions. Cleanup therefore needs runtime ownership/reconciliation evidence;
a returned value edge alone is not an ownership proof. This slice deliberately makes
no deployment, partial-allocation recovery or live-model reasoning claim. S3 and
AI-A3 remain open. Next work is live discovery trials and runtime precondition
revalidation, using the existing execution/outcome evidence boundary.

Validation commands:

```sh
python3 -m unittest discover -s tools/tests -p 'test_discovery_decisions.py'
python3 tools/verify_discovery_decisions.py --cli flow-cli/build/install/flow-core/bin/flow-core
```

The installed proof includes 30 cases: eight matching plans across two domains and
two layouts, four stale alternatives, four wrong observed values, four recovery faults
and two unsupported states, plus four map-free goal plans and four independent-constraint
violations. These are scripted protocol/compiler proofs, not an AI
benchmark. Existing compiler, conformance and runtime evidence tests remain mandatory.
