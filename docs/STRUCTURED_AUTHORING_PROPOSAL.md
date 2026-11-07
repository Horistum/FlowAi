# Structured authoring and conditional fidelity

Status: proposed design, 2026-11-07. No new syntax or contract is implemented by this document.
Baseline: merged PR #260, `81ecc2c91220a6a6121142cbb2d307f8ee90b277`.
Work package: [AI-A3-S2.1-DESIGN](../.flow-agent/work-packages/structured-authoring-design.yaml).
Parent direction: [system automation](SYSTEM_AUTOMATION_DIRECTION.md).

The maintainer requested the first next step with evidence that it generalizes beyond
one scenario. The existing S1 already delivered resource-specific requirements in
PR #260. This proposal names the next implementation slice **S2.1** and leaves the
selected roadmap, release versions and implementation status unchanged.

## Outcome and invariant

A reviewed AI proposal can express conditional work, preserve values available on
each alternative, join those values explicitly and explain why the resulting plan
satisfies independently captured conditional obligations. The same production logic
must handle infrastructure choice, maintenance gating and nested data recovery.

**Invariant:** every accepted operation retains its required control context, and
every accepted value use has the producer coverage established by the canonical
availability and merge contracts. Preserving operation names without their guards
or replacing values with ordering dependencies does not satisfy this invariant.

Acceptance concerns the represented plan. It does not establish that external work
completed, an observed resource exists, a caller owns that resource or an observation
is still current when execution starts.

## Evidence from the current implementation

| Boundary | Current evidence | Consequence |
| --- | --- | --- |
| Intent authoring | `IntentWorkflow.steps` contains only `IntentStep` in [IntentModel](../src/main/kotlin/org/flowlang/intent/IntentModel.kt); [the reader](../src/main/kotlin/org/flowlang/intent/IntentYamlLoader.kt) rejects unknown fields | Structured authoring requires an explicit versioned input change |
| Lowering | [IntentToAstPlanner](../src/main/kotlin/org/flowlang/intent/IntentToAstPlanner.kt) sorts a flat step inventory and emits operations | Recursively flattening new blocks would discard meaning; structural ordering must survive lowering |
| Branches | [AST](../src/main/kotlin/org/flowlang/ast/AstNodes.kt) already has `IfNode` with two bodies | Reuse the existing control construct |
| Joins | [FlowAvailabilityAnalyzer](../src/main/kotlin/org/flowlang/core/FlowAvailabilityAnalyzer.kt) checks explicit merges; [canonical graph](../src/main/kotlin/org/flowlang/compiler/CanonicalExecutionGraph.kt) retains their path and producer evidence | Reuse the authoritative join contract, including incomplete and overlapping path rejection |
| Fidelity | [RequestFidelityEvaluator](../src/main/kotlin/org/flowlang/frontend/ai/RequestFidelityEvaluator.kt) requires flat workflows for several positive checks | Syntax support alone cannot establish conditional request fidelity |
| Source preservation | [IntentLoweringAuthority](../src/main/kotlin/org/flowlang/lowering/IntentLoweringContracts.kt) compares captured fields with the actual projected plan | Extend preservation evidence to predicates, branch membership and merge sources |
| Analysis limits | [Availability limits](AVAILABILITY_ANALYSIS_LIMITS.md) bound paths, work, generated text and depth | Reuse cumulative budgets; recursive input processing also needs bounded admission |

Existing [merge semantics tests](../src/test/kotlin/ExplicitMergeSemanticsTests.kt)
cover explicit source syntax and canonical merge evidence. Existing
[conditional ordering tests](../src/test/kotlin/ConditionalOrderingDependencyTests.kt)
distinguish ordering from permission to read a conditionally produced value.
These are reusable compiler mechanisms, not evidence that structured Intent already works.

## Proposed input contract

Propose Intent **3.0** with a `body` in each workflow. Each body is an ordered list
of tagged statements. Intent 2.0 retains its existing `steps` reader, defaults and
ordering behavior. Reject mixed `steps`/`body` representations; do not infer a version
from payload shape. The exact version and schema remain proposals until implemented.
An omitted `intentVersion` keeps the existing 2.0 interpretation; new structured
documents must request 3.0 explicitly. Bumping a global default must not reinterpret
old files or captured sessions. Readers and advertised schemas must agree on both.

| Statement kind | Fields in addition to stable `id` | Lowering |
| --- | --- | --- |
| `operation` | Existing capability, parameters, binding, dependencies and output declarations | Existing validated operation lowering |
| `branch` | `when`, `then`, `otherwise` | `IfNode` using the existing expression boundary |
| `value` | `value` | Local `SetNode` |
| `merge` | `sources` containing at least two simple value references | `SetNode` whose complete value is existing `merge(...)` |

An omitted `otherwise` is an explicit empty alternative, not an omitted path in
availability analysis. `value` is local to execution flow; it does not enable the
currently contained global initializer feature. A branch does not implicitly export
a value. Explicit bindings and merges expose that choice for inspection.

Illustrative proposed workflow body, **not accepted by the current Intent reader**:

```yaml
body:
  - kind: branch
    id: select-source
    when: "ref:preferPrimary"
    then:
      - kind: value
        id: primaryValue
        value: "ref:primaryInput"
    otherwise:
      - kind: value
        id: secondaryValue
        value: "ref:secondaryInput"
  - kind: merge
    id: selectedValue
    sources: ["ref:primaryValue", "ref:secondaryValue"]
```

The enclosing proposed document would declare `preferPrimary` as a required Boolean
input and both source inputs with compatible types. This illustrates value selection,
not discovery or provisioning. Operation results can replace the source bindings when
their contracts provide suitable outputs. Raw external inputs cannot be passed
directly to the existing merge; the branch-local `value` statements establish the
path-local producer evidence required by that contract.

### Expressions and identities

- Reuse existing value/expression parsing and canonical rendering. Do not create an
  expression language inside the Python host.
- Initial certified predicates cover required Boolean references, Boolean literals,
  negation, and conjunction/disjunction of admitted Boolean operands. An operation
  result used as a predicate must have an established Boolean output contract.
  General function calls, unknown result types or external truth claims do not gain
  certified conditional fidelity by being placed in `when`.
- Missing, null or string-valued conditions must not silently become `false`.
  Missing facts remain questions/blockers; runtime failures remain failures.
- Node IDs are unique across the document, including alternative branches. Value
  binding names use the existing checked identity convention; derived-name collisions
  fail instead of being silently repaired. Display names do not select a producer.
- References retain their existing input/result distinction. Cross-workflow value
  dependencies and ambiguous producer identities remain outside this slice.

### Ordering and availability

Intent 3.0 body order is authored structural order. The planner must not topologically
sort operations out of a branch or across a value join. Intent 2.0 keeps its existing
flat ordering behavior. Forward and sibling-branch dependencies are rejected in the
new body instead of rearranging control to satisfy them.

Earlier conditional producers may back declared ORDERING dependencies where the
existing canonical contract permits that relationship. Such an edge does not prove
that the producer ran or that its value is available. VALUE reads must pass the
existing path-sensitive analysis; values from alternatives require an explicit merge
when they are not otherwise definitely available.

For a merge, every path reaching the join must have exactly one contributing source.
Missing paths, overlapping sources, ambiguous identities and concurrent-branch inputs
retain the existing rejection behavior. Known incompatible types are rejected;
unknown types remain unknown and must not be reported as proven type compatibility.
No implicit null, last-writer rule, string conversion or first-nonempty selection is
introduced. Nesting uses the same rules, within the existing budgets.

## Independent conditional requirements

Propose requirements **version 3**. Keep versions 1 and 2, their flat-graph limitations
and the direct `value-from` meaning unchanged. The independent requirements are
reviewer-owned and remain outside the candidate's provider prompt.

Two different obligations must be expressible:

1. **Only under P:** every occurrence matching an operation selector is confined to
   the required control context. Absence alone may satisfy this restriction.
2. **Required on the P path:** the selected operation must appear on each relevant
   normally progressing path in that context. Absence, an empty branch or an extra
   guard that can skip it violates the obligation.

Neither proves runtime liveness or completion after a preceding failure. Global
prohibitions continue to inspect the entire represented graph. A check on the desired
branch must not overlook an additional prohibited or unguarded matching operation.

The proposed v3 requirement family therefore includes:

| Obligation | Evidence checked |
| --- | --- |
| Conditional restriction | Matching operation scopes and their expected predicate/polarity; all matches must be accounted for |
| Conditional presence/count | Operations on the admitted alternative paths, with explicit lower/upper bounds; unknown structure is not a pass |
| Scoped parameters/effects/order | Existing semantic selectors applied within an independently identified control context |
| Joined value origin | Actual canonical merge inputs, path coverage and the VALUE path from the merge to the selected consumer parameter |

Predicates in requirements identify stable public inputs or uniquely selected semantic
producer outputs. They must not depend on model-invented branch IDs, task names or
descriptions. Canonical predicate comparison is structural within the admitted subset;
arbitrary logical equivalence is not inferred. Ambiguous scope matching or predicates
outside that subset yield `review-required`, not a guessed match.

The first implementation should match conditional scopes compositionally, with the
same bounded path analysis used by the compiler. It must distinguish the required
path from a narrower added condition. An unmatched nested control form makes a positive
obligation uncheckable. Do not implement an unrestricted SAT solver or duplicate an
execution graph in the host.

Joined origin checks inspect authorized `valueMerges` and resolved VALUE edges. They
can follow an explicitly supported local reference alias to a public input or unique
semantic producer. Arithmetic, template strings, arbitrary transforms and unknown
projections are not transparent aliases. A replacement literal with the same example
value is not evidence of the requested producer. A missing canonical alias link is
an integration gap to resolve explicitly, not permission to read expected provenance
from candidate prose.

## Implementation ownership

| Area | Required change |
| --- | --- |
| Intent model/reader and public schemas | Version-dispatched structured statements; exact unknown-field and shape rejection; recursion limits before allocation |
| Identity and structural traversal | One reusable traversal carrying workflow and branch context; typed operation/value inventories and deterministic identity checks |
| Capability, policy and decision analysis | Inspect all operations while retaining their contexts; an approval in one branch cannot authorize another branch by appearing in a flattened inventory |
| Intent lowering | Emit existing AST structures; preserve body order, predicates, branch membership, diagnostic source paths and joins |
| Source metadata and lowering evidence | Record authored structure and verify it against the actual plan; stored metadata alone is insufficient proof |
| Fidelity reader/evaluator | Version 3 obligations over the authorized graph, with bounded ambiguity and unsupported results |
| AI schema and host | Advertise the selected Intent/requirements versions; preserve captured prompts and immutable requirements across continuation |
| Distribution and conformance | Ship matching schemas/migration documentation, add registered vectors and installed-CLI proofs |
| Adapters | Continue to assess existing condition/value support independently; no support promotion from frontend acceptance |

A flat operation inventory is useful for global prohibitions or binding lookup. It
must not become the authority for control coverage, approval coverage, value lifetime
or requirement satisfaction. Inspect current `workflow.steps` consumers explicitly;
changing only the parser and planner is incomplete.

## Acceptance portfolio

These are additive design cases, not changes to the frozen twelve-case authoring
corpus. Their operation bindings and reviewed requirements must be prepared independently
of a generated candidate. The examples establish planning behavior; real observation
collection and operation execution remain separate evidence.

| Case | Structure | Required distinction |
| --- | --- | --- |
| A: infrastructure choice | Two alternatives produce a selected resource handle; a common consumer uses the join | Existing handle versus newly produced handle; wrong source cannot pass by sharing a capability name |
| B: maintenance window | Required maintenance in an admitted window, notification in the alternative, no joined value | Restricting an operation to a branch differs from requiring its presence; no fabricated output is necessary |
| C: data recovery selection | Nested choice of recovery sources; value producers inside alternatives; common downstream verification | Correct nested coverage and provenance, including a same-capability operation on another resource |
| D: generic composition | Serial decisions, empty optional alternatives and nested decisions using otherwise unrelated test contracts | No production behavior depends on the three domain labels, example files or project names |

At least one composition must be specified after the implementation design is fixed.
Changing only resource names in an existing example is not independent evidence of
generality. Each operation remains subject to the existing capability and payload
contracts; unsupported data transforms must not be smuggled in as example parameters.

Required falsification and preservation cases:

- Replace the required predicate with a constant; invert polarity; swap branch bodies.
- Remove required work; add a second condition that can skip it; add an unguarded
  duplicate outside the reviewed scope.
- Move an approval or effect across branches while retaining the same flattened list.
- Consume a value from the other branch or a maybe-defined value without a valid join.
- Remove a merge input, overlap contributors, duplicate a source, substitute a producer
  or replace the VALUE relation with ordering only.
- Change a known type incompatibly, use null/string as a Boolean or use an unresolved
  predicate source. Missing input must not select a convenient alternative.
- Rename internal IDs consistently, change descriptions and reorder YAML keys: preserve
  decisions and graph meaning up to the expected identity/source-provenance changes.
- Exceed path/depth/work limits and split branching across workflows: reject without
  an accepted partial graph or per-workflow budget reset.
- Change captured conditional requirements on resume: reject without provider calls,
  evidence writes or additional budget, then accept a corrected continuation.
- Request a target unable to preserve the admitted condition/join: retain semantic
  evidence and report blocked materialization rather than flattening it.

Compare Flow Source, Intent YAML/JSON and reviewed AI proposals using expected normalized
control, operation, effect and data relationships. When semantic identities are the
same, require the same canonical semantic digest; source provenance alone must not
justify a different semantic digest. Source hashes and frontend evidence may differ.
For deliberately renamed identities, use an explicit identity correspondence and
compare the preserved relationships; never erase predicates, effects or producer
coverage to make the comparison pass. Independently specified expected relationships
prevent all frontends from agreeing on the same loss.

Expected result classes must be asserted separately:

| Case | Required outcome |
| --- | --- |
| Represented structure with satisfied reviewed obligations | Semantic acceptance and `requirements-satisfied` |
| Missing producer path, ambiguous source or incompatible known type | Compiler rejection before authorization |
| Compilable plan that removes or misplaces required work | `violated` with the independent requirement identified |
| Ambiguous selector or unsupported predicate equivalence | `review-required`; never a satisfied subset |
| Requirement explicitly outside represented semantics | `unsupported` |
| Analysis budget exhausted | Existing bounded compiler rejection; no partial accepted graph |
| Faithful plan with unsupported target preservation | Semantic/fidelity evidence retained; blocked materialization |
| Missing live provider or unavailable execution environment | Evaluation not performed; never a passing runtime result |

## Delivery sequence and completion

1. Capture baseline rejected structured Intent and accepted reference Flow Source
   compositions. Review independent conditional requirements and expected relationships.
2. Implement the versioned model, structural traversal, lowering and preservation
   evidence. Keep the implementation draft while fidelity support is incomplete.
3. Implement conditional and joined-origin checks; exercise the whole mutation matrix
   through reviewed AI input and the installed CLI.
4. Complete migration and host compatibility checks; run ordinary regression,
   conformance, source-deletion isolation and exact-head/merge-candidate CI. Report
   target disposition separately from successful semantic and fidelity acceptance.

S2.1 is complete only when all three named domains and the independent composition
use the same production path, required mutations fail at the owning boundary, old
version fixtures retain their classifications and installed-product results match
the reviewed expectations. A parser demo, three happy paths or a passing unit suite
alone cannot close the slice.

A small live-model trial may start as soon as the new schema and independent checks
are usable, with an explicitly configured provider and budget. Record blocked plans,
constraint violations, clarification quality and cost. Deterministic integration
tests do not establish live generalization; this proposal authorizes no paid calls.

## Integration questions to close before implementation acceptance

| Question | Required evidence or decision |
| --- | --- |
| Which operation outputs are actually typed Boolean or suitable merge values? | Inspect current output contracts; use supported contracts or an explicit generic contract extension, never infer types from example values |
| Are local alias origins fully visible in the authorized graph? | Trace a reference alias through the existing builder/digest/validator and prove its origin; expose any missing preservation field through the normal contract boundary |
| Can branch-sensitive approval requirements use existing control evidence? | Demonstrate opposite-branch rejection; keep unsupported cases blocked until represented |
| Which public artifacts need version changes for structural preservation evidence? | Audit affected schema readers/writers and package indexes; Intent 3.0 and requirements v3 are proposed, not the complete version audit |
| Can an existing target materialize the chosen compositions faithfully? | Run its current admission gates and record the disposition; frontend completion does not certify a target |

These questions do not change the invariant or permit a scenario-specific fallback.
They can narrow certified coverage or require a separately scoped contract change.

## Alternatives and subsequent work

- **Direct Flow Source from AI:** useful as the baseline experiment and potentially a
  later versioned authoring frontend. It still needs the independent conditional
  requirements and cannot be inserted into today's `normalizedIntent` envelope.
- **A guard field on each flat operation:** smaller superficially, but leaves nested
  bodies, joins and source-preservation obligations implicit. Structured bodies keep
  those obligations inspectable and reuse existing AST semantics.
- **Scenario templates:** useful fixtures or optional user conveniences. They do not
  own compilation behavior or establish reuse across unfamiliar systems.

Resource identity/ownership, observed outcomes, bounded retries and partial-failure
recovery remain subsequent S2 work. Autonomous discovery remains S3; reusable concrete
implementations remain S4. In particular, this slice does not complete the full
environment lifecycle request in the parent direction. Existing AI-A3 live/render
gates, AR-06/AR-07 findings and the EF-09 pause remain unchanged.

## Validation of this design change

This change contains a proposal and work package only. Current production contracts,
schemas, fixtures, release state and roadmap selection are unchanged. Source references
were checked against the merged baseline. All 21 local links across this proposal and
its parent direction resolve; the illustrative YAML and work-package references were
validated. Agent validation and whitespace checks passed. No production tests or
runtime experiments were rerun for this documentation change. Implementation acceptance
above remains future work and is not reported as a test result.
