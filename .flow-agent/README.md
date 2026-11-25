# Horistum Roadmap Development Agent

<a id="flow-roadmap-development-agent"></a>

This directory defines the repository-bound development agent for Horistum. Its technical Core retains the name Flow Core.

The agent is not a runtime executor, SDK, plugin framework, or target-specific workflow engine.
It is a controlled development mechanism for evolving Flow Core according to the approved roadmap,
architecture constitution, quality gates, and release state.

## Purpose

The agent exists to keep Horistum development aligned with the original project purpose, quoted here with its retained technical name:

> Flow is an AI-first standardization layer for DevOps and IT automation. It separates human intent,
> rules, risks, and safety boundaries from target-specific workflow syntax and platform lifecycle details.

## Product identity

Use Horistum as the product name in current presentation material. Retain `FlowAi`, `Flow`, `flow` and `flowlang` where they identify existing code, tooling, contracts or historical evidence. This is intentional compatibility, not unfinished cleanup. See [Product identity and retained technical names](../docs/PRODUCT_IDENTITY.md).

The `.flow-agent/` path, architecture constitution, roadmap, release state and validation rules remain unchanged. The naming decision does not authorize a namespace migration, a contract version change, or a new roadmap stream.

## Active roadmap route

Use this navigation route to select work; it introduces no new roadmap authority:

1. Read the constitution and [release state](release-state.yaml), then the [root roadmap index](roadmap.yaml). The root's completed stream fields and `primaryRoadmapStream` preserve the earlier handoff; they do not select today's recovery work.
2. Follow the existing `postToolchainRoadmap` pointer to the [post-toolchain roadmap](roadmap-post-toolchain.yaml). Its `currentDecision.nextItemRoadmap` selects the active recovery roadmap.
3. Read the [recovery roadmap](roadmap-architecture-recovery.yaml): `currentDecision` owns milestone selection, and `correctiveWorkPackage` / `systemAuditFollowup` identify the current bounded correction. Read that work package and its report before editing.
4. Cross-check the release state's recovery fields and the actual merged revision. Completed items and historical `decisionBasis` receipts are evidence of their recorded boundaries, not new activation decisions.

Recovery selects AI-A3 as a bounded authoring slice under AR-07, following merged AI-A2 PR #256. Live evaluation and held-out rendering remain explicit open gates. AR-06 remains open; full AR-07 closure still depends on AR-06, and EF-09 remains paused. The selected work package is [bounded external authoring host](work-packages/bounded-authoring-host.yaml). This activation does not close F-16/F-20 or authorize migration cleanup.

The existing Python context generator reports the completed root-stream lifecycle and embeds its indexed documents; it does not independently select the post-toolchain recovery correction. Follow the route above even when that generated summary says `completed`. Keep historical selectors and acceptance receipts intact.

For implemented module and compiler boundaries, see the [current architecture](../docs/ARCHITECTURE.md).

For the maintainer-requested continuation proposal, see [AI-assisted automation authoring](../docs/AI_ASSISTED_AUTHORING_DEVELOPMENT_PROPOSAL.md). It defines AI-A1 through AI-A4. AI-A3 is now selected; its host preparation does not establish real-model completion. AI-A4 still needs separately authorized implementation evidence. See [bounded authoring](../docs/BOUNDED_AUTHORING_HOST.md) and [request-fidelity review](../docs/REQUEST_FIDELITY_REVIEW.md).

After merged PR #257, the [target-readiness followup](work-packages/authoring-target-readiness.yaml)
preserves canonical feedback for unavailable target implementations and stops model
repair on immutable caller input errors. Both AI-A3 live gates remain open.

The maintainer-authorized [investigation followup](work-packages/evidence-driven-authoring.yaml)
adds evidence-linked findings, unknown revisions and bounded reads to the external
host. See [its contract](../docs/AUTHORING_INVESTIGATION.md). This extends host
preparation without closing the live-model or held-out-render gates or changing
target implementation authority.

Within AI-A3, the selected continuation is now
[system automation direction and resource fidelity](work-packages/system-automation-direction.yaml).
Read the [direction and remaining slices](../docs/SYSTEM_AUTOMATION_DIRECTION.md)
before selecting new implementation work. S1 checks multiple managed resources and
their value dependencies; S2 must address structured authoring and resource/outcome
semantics. Delivery-only demonstrations and pinned-file ledger tests do not satisfy
this broader product outcome. Existing recovery and live-evaluation gates remain open.

## Mandatory Process

Every development step must follow this cycle:

1. Read the agent contract.
2. Read the architecture constitution.
3. Read the current release state.
4. Follow the active-roadmap route above and select the authorized work item.
5. Create a work package.
6. Run architecture and forbidden-direction checks.
7. Plan the smallest safe patch.
8. Implement code, tests, conformance vectors, and documentation.
9. Run offline validation.
10. Produce a release report.
11. Update release state only after validation.

## Source Language Rule

All repository text must be English.
Chat communication may use Czech or another user-preferred language.

After merged PR #258, the [clarification-integrity followup](work-packages/authoring-clarification-integrity.yaml)
protects established requirements across explicit continuation. The target-readiness
correction is merged; live evaluation and executable held-out rendering remain open.

The S2 continuation now includes [runtime resource identities](work-packages/runtime-resource-identities.yaml).
Read the [version 3 contract](../docs/RUNTIME_RESOURCE_IDENTITIES.md) before collecting allocated
resources. The next bounded slice is S3 evidence-driven system discovery; broader recovery and
live-evaluation gates remain open.

After merged PR #271, [S3.4 worker integration](work-packages/brokered-planning-observations.yaml)
connects the existing portable worker to planning tools and scoped observations.
The correction merged in PR #272 binds selection to the last observation snapshot and adds
model-free independent review with evidence checks before and after compilation.
See the [setup and handoff contract](../docs/BROKERED_AGENT_PLANNING.md). Live-agent
evaluation and remote execution-precondition revalidation remain open; this does
not automatically activate the reference software-delivery controller.

After merged PR #272, [S3.5 worker evaluation](work-packages/planning-worker-evaluation.yaml)
provides a complete repeated-case runner through the worker and independent review,
with frozen inputs, cross-case expectation isolation, durable turn reservations and
process-free completed recovery. See the [portfolio and result contract](../docs/PLANNING_WORKER_EVALUATION.md).
Live provenance and execution-precondition evidence are separate acceptance work;
the evaluator never self-promotes scripted results to those claims.

After merged PR #273, the selected continuation is
[S3.6 operation preconditions](work-packages/operation-preconditions.yaml).
It binds fresh signed snapshots to existing resource instances and canonical
effect operands. Independent planning preflight connects the exact selected
candidate and goal review to fresh observer snapshots, including expiry on receipt
recovery. See [the operator contract](../docs/OPERATION_PRECONDITIONS.md).
The compiler reports a point-in-time assessment; external dispatch enforcement
and independent live portfolio acceptance remain open.

After merged PR #274, the selected continuation is
[S3.7 external dispatch assessment](work-packages/planning-dispatch-preconditions.yaml).
An explicit bounded exchange lets an independently authorized runtime retain its
concurrency protection across fresh observation and canonical assessment. The host
reserves each invocation durably and never automatically repeats an uncertain one.
See [the runtime contract](../docs/PLANNING_DISPATCH_PRECONDITIONS.md).
Provider commissioning and independent live portfolio/outcome acceptance remain open.

After merged PR #275, [S3.8 independent dispatch outcomes](work-packages/planning-dispatch-outcomes.yaml)
binds a predeclared outcome policy to one completed dispatch. See the [operator
contract](../docs/PLANNING_DISPATCH_OUTCOMES.md) for collecting and assessing
independent evidence. Production commissioning and live portfolio acceptance remain open.

After merged PR #276, [S3.9 Kubernetes commissioning](work-packages/kubernetes-dispatch-commissioning.yaml)
uses the same reviewed SYNC path for operator-declared native and custom resources on a disposable Kubernetes API server.
A separate observer correlates its native audit record and live state with the reviewed dispatch.
See the [commissioning procedure](../docs/KUBERNETES_RESOURCE_DISPATCH.md).

After merged PR #277, S3.10 adds preregistration and independent review of each planning trial. Commissioned live measurements and the original held-out render remain open. [Work package](work-packages/independent-planning-portfolio.yaml) · [Operator procedure](../docs/PLANNING_PORTFOLIO_REVIEW.md)

After merged PR #278, the selected slice is S4.1. An operator enters a goal in
plain text and reviews the compiler-checked plan or answers a missing decision.
The next evaluation uses new goals with a live provider. Earlier corpus, target
render and portfolio measurements remain open. [Product entry point](../docs/GOAL_AUTHORING.md)

After PR #279, S4.2 adds a terminal conversation and evaluates a local instruction
model on three new first-turn goals. See the [first-run guide](../docs/GOAL_AUTHORING.md#first-conversation)
and the [measured outcomes](../docs/GOAL_AUTHORING_LIVE_TRIAL.md). The next slice
tests capability grounding on fresh inputs. The original live portfolio and held-out
render gates remain open.

After PR #280, S4.3 uses the installed compiler's contracts for local operation
authoring. Independent review measures named objects, parameters and direct
dependencies on fresh goals. The next slice addresses missing operations and
incorrect arguments during goal review before target selection.
[Evaluation and reproduction](../docs/GOAL_AUTHORING_GROUNDING.md)
