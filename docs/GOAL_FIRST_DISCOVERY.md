# Goal-first discovery

The existing authoring host can start from a goal, permitted environment, actual
observation tools and public constraints. It no longer requires the agent to invent
a binding table or enumerate decision cases before proposing a complete plan.
The agent chooses observations, extracts facts, proposes their meaning and authors
the ordinary Intent, including value dependencies, control and recovery. Flow Core
remains the sole semantic evaluator and does not execute the workflow.

## Input and authority

Opt in by adding `investigation.task` to a version-4 configuration:

```json
{
  "formatVersion": 4,
  "roots": [{
    "id": "environment",
    "path": "/absolute/selected/export",
    "scope": "Isolated recovery environment",
    "observations": ["list", "read"]
  }],
  "task": {
    "constraints": ["Preserve production data and retained backups."],
    "successCriteria": [{
      "id": "isolated-recovery",
      "description": "Recovery serves the requested objective without exposing unverified data."
    }]
  },
  "maxSteps": 16,
  "maxSourceBytes": 65536,
  "maxTotalSourceBytes": 262144,
  "maxRecords": 128,
  "maxEntries": 256
}
```

The goal stays in the original `request.json`. The caller separately supplies
confirmed, **reviewer-authored requirements v5 before the first model call**.
These frozen checks may express forbidden effects or outcome constraints; they must
not prescribe a source-to-plan solution map. Public natural-language constraints and
success criteria enter `input.task`; private compiler check expectations do not.
As with existing authoring, the operator must ensure their checks represent their
public request. No model authenticates that relationship.

Every root explicitly grants `list`, `read`, or both. The host advertises only those
grants and enforces them before filesystem access. `input.task` includes the original
goal, environment scope, tools, constraints, criteria and remaining call/step limits.
It omits absolute host paths, credentials, expected plans and source selectors.
The public capability catalog describes canonical plan operations; it is **not** a
claim that an Azure/database execution tool is installed or authorized. The actual
authoring tools in this slice are bounded filesystem observations and JSON extraction.
Read-only observations of an export do not establish current remote state.

Internal verification of an authenticated inventory still checks the entire export,
including files the model has not read. It does not expose ungranted content. Failed
lookups outside that export carry no collector attestation.

## Discovery and proposal

Use the existing `investigate` actions. The host retains ordered requests, observations,
exact extracted values, inferred findings and unknowns. There is no expected tool
sequence. Missing or inaccessible authorized paths return bounded `failure` values
(`not-found`, `not-accessible`), allowing a later investigation to follow another lead.
They are evidence about a lookup only, not proof of absence in the managed system.
They cannot be extracted as JSON facts or selected as binding records. Unauthorized
tools, invalid paths, symlinks, integrity failures and exceeded limits fail closed.
An observed failure becoming success, or any other source change, invalidates the run
and its review. No old approval is reused.

The proposal's `interpretation` has `summary`, `evidenceRefs`, `bindings` and
`decisions`. Both arrays may be empty. `evidenceRefs` must identify current observations,
facts or findings; the complete Intent supplies the plan. This reference check proves
traceability, not that a conclusion follows logically. Findings retain their
`model-inferred` attribution. Optional bindings and decisions retain the existing
[observed-decision contract](OBSERVED_DISCOVERY_DECISIONS.md); they add consistency
checks and cannot become independent acceptance evidence.

Blocking technical unknowns prevent proposal review; blocking decisions pause for
caller answers using `resume`. Further investigation can address technical unknowns
before proposing. Clarification does not approve the resulting plan. The existing
`review-discovery` command binds a separate caller decision to the exact evidence,
interpretation and complete candidate, then invokes the compiler without another
model call or an unreviewed repair.

## What an accepted result means

Goal mode records host version 7 and adds `goalEvidence` to the review packet,
compiler binding receipt, final state and CLI summary:

| Field | Authority and meaning |
| --- | --- |
| `independentConstraints` | Compiler results for constraints frozen by the caller before generation; reviewer identity is attribution, not authentication. |
| `proposedConsistencyChecks` | Compiler results for additive model-proposed bindings/cases; separate review does not make them an independent oracle. |
| `successCriteria` / `goalSatisfaction` | `not-assessed`; a passing subset of formal checks does not establish the natural-language outcome. |
| `runtimeOutcome` | `not-observed`; no resource operation or recovery was executed. |

Receipt validation requires every original and derived check exactly once. Missing,
duplicated or falsely satisfied checks fail the host. The host does not inspect or
reimplement the Intent graph to produce this partition.

`status: accepted` and exit 0 retain their existing meaning: the reviewed candidate
passed compiler admission and the declared requirements. Consumers must not promote
that status to completed goal, execution authorization or recovered infrastructure.
There is deliberately no automatic goal-passed score. Independent goal assessment
still needs a reviewer or independently sourced outcome evidence; this slice does
not implement an independent semantic judge.

## Reproduce without answer fixtures

The [input-only portfolio](../conformance/goal-discovery/) covers infrastructure and
data recovery with changed goals, layouts, state, conflicting evidence and tool
availability. It contains no provider responses, candidate plans, case-selection
tables, binding maps, review decisions or expected observation order.

Use an owner-installed data-only `command-reasoning/v2` adapter and an installed
compiler. Configure provider/model/credential targets and explicit token, monetary,
call and time limits in a base host config with **no investigation section**. The
existing command adapters in `Horistum/gitops-agent-control-plane` implement this
transport; no second model SDK or implicit credential fallback is added here.
Allow sufficient input reservation for the public Intent and requirements schemas
(the deterministic installed proof uses 240000 as its conservative byte/token cap).

```sh
python3 tools/author_proposal.py prepare-goal \
  --config /absolute/provider-config.json \
  --case conformance/goal-discovery/infrastructure-isolated.json \
  --out /absolute/infra-trial

python3 tools/author_proposal.py run \
  --config /absolute/infra-trial/config.json \
  --request /absolute/infra-trial/request.json \
  --requirements /absolute/infra-trial/requirements.json \
  --out /absolute/infra-trial/run
```

Preparation only materializes portable inputs and preserves the selected provider
and budget. `run` invokes that provider. Review the retained packet, then submit a
review document through the existing `review-discovery` command. Repeat with a data
case and the variants, using the same provider, prompt and production path. Do not
edit criteria after seeing a candidate. Keep every failed, blocked and incomplete
run alongside successful proposals. A held-out evaluation must add environments
and goals not used to tune the implementation; these public examples alone cannot
establish generalization.

## Compatibility and validation boundary

Investigation versions 1–3 and v4 without `task` retain their existing contracts.
Goal mode is explicit: roots require grants, proposals require evidence references,
empty maps are allowed, caller-authored constraints are mandatory and lookup failures
are delivered observations. Existing retained runs are not silently migrated.

Protocol tests cover grants, failed-read continuation, source changes, conflicts,
clarification, rejected self-approval, independent receipt completeness and all eight
portable cases. Installed-compiler tests include four map-free plans and four plans
contradicting frozen caller constraints, in addition to the 22 existing decision and
recovery regressions. These are deliberately scripted tests of host/compiler behavior.
They are not evidence that a live model discovered a correct solution. No live provider
was configured for this change; the live reasoning, held-out goal and runtime gates
remain open.
