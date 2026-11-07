# Structured authoring and conditional requirements

Intent **3.0** admits ordered workflow bodies containing operations, branches, local
values and explicit merges. Requirements **3** independently check conditional
operations and joined value origins over the authorized canonical graph. These are
planning contracts, not observations of runtime completion.

This implements the bounded authoring part of [S2.1](STRUCTURED_AUTHORING_PROPOSAL.md).
The four [independent fixtures](../conformance/structured-authoring/corpus.json)
cover infrastructure choice, maintenance gating, nested recovery source selection
and a composition with serial and nested choices and an empty alternative.
No production dispatch depends on those cases or their resource names.

## Version selection and migration

| Surface | Compatibility |
| --- | --- |
| Intent 2.0 | Existing `steps`, defaults and dependency sorting remain unchanged; omitted `intentVersion` still selects 2.0 |
| Intent 3.0 | Explicit version, at least one workflow with `body`; cannot mix `body` and `steps` |
| Requirements 1 and 2 | Existing checks retain their flat-workflow limitations; `value-from` still means a direct producer read |
| Requirements 3 | Adds `only-under`, `on-path`, `joined-value-from`; existing checks retain their earlier meanings |
| Proposal envelope 1 | `normalizedIntent` admits either Intent version; the normalization report is unchanged |
| Authoring prompt 3 | Advertises both Intent versions; captured sessions retain their saved instructions and contracts |
| AST, plan and graph | Existing control/value constructs and versioned contracts; no new executor or alternate graph |
| Lowering evidence 2.1 | Additive source `intentVersion` provenance and `structured-body` entries bind the complete actual plan structure to the authored body |

Schemas are [Intent 3](../schemas/intent-v3.schema.json) and
[requirements 3](../schemas/request-requirements-v3.schema.json). They constrain
interchange shape; the compiler remains authoritative for Boolean types, identity,
ordering, availability, merge coverage and analysis budgets.

To migrate, explicitly select `intentVersion: "3.0"`, replace each workflow's
`steps` with `body`, add `kind: operation` to existing operations, and place them in
the intended execution order. Dependency sorting is **not** performed on a body.
Put conditional approvals inside explicit branches. Legacy global failure flags
`notify`/`rollback` and conditional approval policy expressions are rejected in
3.0; explicit failure-region authoring remains future work.

## Body contract

Every statement has a document-unique `id`. Operation IDs use their existing
checked result names. Value and merge IDs use the same hyphen-to-underscore
binding convention; collisions with inputs, systems and other bindings fail.

| `kind` | Fields | Meaning |
| --- | --- | --- |
| `operation` | Existing `capability`, `params`, `uses`, `requires`, `produces`, `description` | Existing validated operation |
| `branch` | `when`, `then`, optional `otherwise` | Conditional bodies; omitted alternative is empty |
| `value` | `value` | Local binding, including a reference alias |
| `merge` | `sources` | At least two simple local binding references; exactly one contributing source on each incoming path |

```yaml
intentVersion: "3.0"
kind: FlowIntentDocument
name: choose-resource
inputs:
  - {name: reuse, type: boolean, required: true}
  - {name: existingHandle, type: text, required: true}
  - {name: alternateHandle, type: text, required: true}
workflows:
  - name: main
    kind: CUSTOM
    body:
      - kind: branch
        id: choice
        when: "ref:reuse"
        then:
          - {kind: value, id: existing, value: "ref:existingHandle"}
        otherwise:
          - {kind: value, id: alternate, value: "ref:alternateHandle"}
      - {kind: merge, id: selected, sources: ["ref:existing", "ref:alternate"]}
      - kind: operation
        id: verify
        capability: VERIFY
        params: {resource: selected-resource, target: "ref:selected"}
```

Predicates admit Boolean literals, required Boolean inputs, local bindings with an
established Boolean expression, `not`, `and` and `or`. A resolved operation's result
field is admitted only when its module output contract marks it required Boolean.
Unknown, nullable, string-valued or arbitrary function predicates are rejected.
Availability checks still reject a typed value when its producer is not available
on every path to the read. Type admission never establishes an external fact.

Forward and opposite-alternative `requires` dependencies fail. An earlier
conditional producer can support an ordering edge where the existing compiler
permits it; that does not make its result available. Approvals and backup controls
must cover the protected operation's branch context as well as its dependency path.

Merges retain the existing checks for missing, overlapping, duplicate, ambiguous
and concurrent producers and known incompatible types. An unknown output type
remains unknown. Aliases of operation results now retain explicit canonical VALUE
edges, including in Flow Source and legacy Intent lowering. Aliases of public
inputs retain the declared input reference in their canonical local binding.

Structured topology comes from the existing `PlanningTopologyAuthority` inspecting
the actual AST-derived nodes and edges. It does not add the legacy flat Intent
workflow scope on top. This preserves the same canonical semantic digest as Flow
Source when identities and semantic structures agree; topology obligations still
include workflow lifetime, approval suspension and value propagation.

## Independent requirements

Requirements remain reviewer-owned and outside the provider prompt. A selector uses
capability, optional workflow and literal semantic parameters, as in version 2.
A scope is a nonempty list such as `[{"predicate":"reuse","positive":false}]`.
Predicates are parsed and canonically rendered; branch IDs are never selectors.

| Check | Required evidence |
| --- | --- |
| `only-under` with `selector`, `scope` | Every matching operation is lexically inside that scope; absence can satisfy this restriction |
| `on-path` with `scope`, `check` | Every normally progressing path in scope satisfies one `capability-count`, `literal-parameter`, `require-effect` or `before` check; no matching path is a violation |
| `joined-value-from` with `consumer`, `parameter`, `origins` | A direct consumer read from a canonical merge, resolved VALUE edges and exactly accounted-for source origins |

Each joined origin has `scope` and exactly one of `input` (a public input name) or
`producer` (a unique semantic operation selector). Nested merges and simple local
reference aliases are followed. Literal substitution does not establish origin.
Ordering alone cannot satisfy the requirement. The operation result itself can be
selected; arbitrary field projections and transforms are not transparent aliases.

`on-path` distinguishes required work from a narrower added guard that could skip
it. Use `only-under` alongside it to prohibit an extra unguarded occurrence. Global
capability/effect prohibitions still inspect the complete graph. With an explicit
workflow selector, `on-path` applies to that workflow; otherwise it applies to every
workflow containing the requested context.

Conditional fidelity currently certifies predicates over required public Boolean
inputs. Operation-result or local-alias predicates may compile but require review
here. Repeated predicates within a workflow, unsupported control forms, dynamic
selectors, ambiguous producers and general logical equivalence require review.
There is no SAT solver. These limits are explicit extensions for subsequent work,
not permission to certify a convenient subset of a candidate.

Input bodies have a 64-level and 2,000-statement document bound. Existing compiler
availability budgets remain authoritative. Fidelity uses the same 4,096-path and
1,000,000-work ceilings, with a cumulative ledger across workflows and obligations.
Exhaustion returns `review-required`; compiler exhaustion yields no authorized plan.
A positive path check proves represented work under normal progression, not liveness
or successful completion after preceding failures.

## Verification and operational scope

```sh
flow-core proposal conformance/structured-authoring/infrastructure-choice.proposal.json \
  --requirements conformance/structured-authoring/infrastructure-choice.requirements.json \
  --out review
python3 tools/verify_structured_authoring.py --cli flow-cli/build/install/flow-core/bin/flow-core
```

The installed proof runs all four cases, five adverse mutations and two target
assessments that refuse executable output. GitHub Actions produces diagnostic
review evidence; Azure DevOps remains blocked. The ordinary host verification
invokes this proof too. Unit tests additionally check merges, aliases,
approval coverage, immutable reviewed snapshots, version compatibility, identity
renaming and Flow Source convergence. The original twelve-case authoring corpus
remains frozen.

Target assessment is separate. A faithful plan can remain blocked on a target that
cannot preserve it. This change does not execute cloud work, discover resources,
establish ownership or freshness, implement retries/recovery, certify an adapter,
or evaluate a live model. AI-A3, AR-06/AR-07 and the existing live/render gates remain
open; EF-09 remains paused.
