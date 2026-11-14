# System automation development direction

Status: maintainer-selected direction, 2026-11-07. Baseline: merged PR #258.
The first implementation slice is [AI-A3-S1](../.flow-agent/work-packages/system-automation-direction.yaml).
This direction supersedes treating application delivery or pinned-file investigation
as the product's primary outcome. Historical milestone evidence remains historical.

## Product outcome

A person states a desired change to an environment. Horistum helps define the scope,
understand the existing system, identify decisions, compose operations, check their
meaning and obtain an artifact for an explicitly selected external executor.
Infrastructure foundations, DevOps setup, identity, data lifecycle, maintenance,
recovery and retirement are first-class use cases. Software delivery is one family.
Neither an application nor a source repository is required to start.

Three identities must stay distinct:

| Identity | Example | Responsibility |
| --- | --- | --- |
| Managed system/resource | Azure environment, database, team identity, shared network | What the requested change affects |
| Operation implementation | Declarative infrastructure plan, API operation, existing automation | How a semantic operation is implemented |
| Orchestration target | Jenkins, GitHub Actions, another supported executor | Where the composed control/data flow runs |

An Azure environment is not an orchestration target. A successful pipeline is not
evidence that all requested environmental postconditions hold. The compiler remains
target-neutral; external systems own execution and collection of runtime observations.

## What the current code actually supports

The vocabulary already includes PROVISION, DEPROVISION, DATABASE_MIGRATE, BACKUP,
RESTORE, SECRET_ROTATE and CLUSTER_MAINTENANCE. Adding more names or scenario packs
does not resolve the following limitations.

| Boundary | Observed implementation | Development consequence |
| --- | --- | --- |
| AI context | `AiIntentContext` has optional application/repository defaults and known systems | Stop treating delivery defaults as required context; define goals and managed scope independently |
| AI response | `IntentDocument` workflows expose a flat list of `IntentStep` values | Structured authoring must reuse canonical control semantics rather than flatten conditions or invent another graph |
| Independent requirements | Version 1 selects a capability and optional workflow | Repeated operations on different resources need semantic selectors; implemented in S1 |
| Data continuity | The canonical graph already distinguishes VALUE from ORDERING edges | A requirement must check the actual producer of a consumed value; implemented in S1 |
| Effects | `CanonicalIntentEffectAuthority` currently labels VERIFY as reading `deployment.state`, and many effect resources are semantic categories | Domain-neutral resource/outcome semantics need explicit revision; these labels do not identify a concrete Azure resource |
| Workflow composition | Cross-workflow step dependencies are rejected; native multi-workflow materialization is blocked | Workflow lifecycle orchestration needs a typed contract, not dependency strings or implicit execution |
| Conditions and failures | Flow Source/canonical graph have structure; Intent authoring and fidelity cannot certify general conditional requirements | Promote only represented control scopes with preservation and negative tests |
| Discovery | PR #260 reads a manually selected, digest-pinned file inventory | This is evidence retention, not autonomous environment understanding |
| Implementation binding | Explicit module actions and target preservation evidence are required | Discovering an API/schema does not establish implementation correctness or permission to execute |

Relevant owners: [Intent model](../src/main/kotlin/org/flowlang/intent/IntentModel.kt),
[capability contracts](../src/main/kotlin/org/flowlang/standard/StandardCapabilityContracts.kt),
[effects](../src/main/kotlin/org/flowlang/effects/CanonicalIntentEffectAuthority.kt),
[fidelity evaluator](../src/main/kotlin/org/flowlang/frontend/ai/RequestFidelityEvaluator.kt)
and [current architecture](ARCHITECTURE.md).

## Definition of a complex request

The next authoring contract must preserve the following information. This is a design
requirement, not a claim that new wire fields already exist.

| Concern | Required meaning |
| --- | --- |
| Goal | Desired externally observable outcome and its scope |
| Existing state | Observed resources, ownership, identity, source revision and observation time |
| Desired state | Which properties/resources must exist, change or remain unchanged |
| Operations | Typed inputs/outputs, effects, preconditions and available implementations |
| Relationships | Resource dependencies and explicit values/state passed between operations |
| Control | Conditions, approval boundaries, bounded repetition, failure response and recovery |
| Lifecycle | Create, import/reuse, update, inspect, recover and retire with ownership preserved |
| Constraints | Prohibited effects, environment boundaries, retention, access and cost decisions |
| Unknowns | Discoverable facts, user decisions, capability gaps and conflicting observations |
| Acceptance | Checks of the generated plan plus separately identified runtime postconditions |

Ordering must not masquerade as a value dependency. A verification operation must
not masquerade as a successful observation. Retrying an operation must not imply
idempotence. Cleanup must distinguish newly owned resources from shared resources.
Compensation must not be invented for an irreversible operation.

## Selected development sequence

The sequence below extends AI-A3/AI-A4. It does not erase their live-model or rendered
artifact gates, close AR-06/AR-07, or resume EF-09. Each slice needs its own work package
before implementation; later rows are planned, not implicitly implemented by S1.

| Slice | Deliverable | Acceptance |
| --- | --- | --- |
| S1 — resource-specific requirements | Version 2 semantic selectors, zero-count exclusions and producer/consumer value checks | Infrastructure foundation, recovery rehearsal and retirement plans pass; resource substitution and wrong producers fail through the installed compiler |
| S2 — structured operations and outcomes | Authoring of existing canonical branches/failure regions plus explicit resource/outcome contracts where the current model is insufficient | Preserve conditions, effects, output availability and failure scope through every frontend; no unconditional substitute; any wire change has a migration |
| S3 — evidence-driven discovery | External agent inspects an allowed workspace or managed-system inventory, follows dependencies and chooses the next observation to resolve an unknown | Unfamiliar systems without preselected answer files; facts come from retained observations; changed evidence invalidates dependent conclusions |
| S4 — reusable implementation composition | Select or propose bounded operation implementations from discovered contracts and existing tooling | One implementation mechanism composes multiple independently chosen requests; no project-name switches or arbitrary shell fallback; preview and postcondition checking are separate |
| S5 — integrated system automation | Request, discovery, decisions, plan, artifact, isolated execution and independent outcome assessment | Repeat live-agent trials across creation, operational change and retirement/recovery; report blocked cases and cost; accepted constraint violations block completion |

The first S2 implementation slice, [structured authoring and conditional fidelity
(S2.1)](STRUCTURED_AUTHORING.md), was merged in PR #261. It provides reusable branches,
local values, explicit joins and independent conditional obligations. The original
[design rationale](STRUCTURED_AUTHORING_PROPOSAL.md) remains available.

S2 must start with an authored infrastructure/data example that cannot be represented
today and trace the exact loss or rejection. Reuse the canonical graph and existing
Flow Source structures. Do not introduce a second execution model in the Python host.
An existing agent/tool host can own exploration; building a new general agent framework
is not a prerequisite. Keep a versioned, provider-neutral compiler boundary.

The first S2 acceptance request is concrete: inspect a development environment, reuse
an existing shared network or create an owned network when absent, provision identity
and compute, wait for readiness with a bounded retry policy, configure agents only on
success, and on partial failure retire only resources created by this change while
retaining shared infrastructure and recovery data. The present flat Intent cannot
preserve those branch and recovery obligations. A linearized happy path must remain
blocked. S2 must identify branch-local values, ownership, retry preconditions and
observable completion separately, then validate the same contracts with an isolated
data-restore request. This second composition prevents a one-off Azure scenario rule.

The S2 design must explicitly choose which existing Flow Source control forms the AI
frontend can faithfully author and version any changed Intent wire contract. Resource
identity must include managed scope and distinguish observed, requested and newly owned
state. Postconditions must name the observation needed for acceptance, with failed or
missing observations remaining unresolved. A broad capability name or VERIFY node
cannot satisfy these obligations by itself.

S4 separates connectivity (API/CLI/MCP transport) from operation semantics and from
orchestration projection. A generic transport is not a universal adapter. Resource
schemas describe shape; behavior, effects, retry assumptions and result observations
still require evidence. Add a binding only after a concrete authored request identifies
the missing implementation, and demonstrate reuse outside that first request.

## Evaluation portfolio

Use complete, structurally different lifecycle requests rather than several variants
of one build/deploy pipeline:

1. Infrastructure and DevOps foundation: scope and policy, approval, network and identity,
   compute using their outputs, monitoring, project, service connection, agents, final checks.
2. Data recovery/migration rehearsal: preserve production, capture a recovery point,
   restore into an isolated destination, verify restoration, migrate there, verify schema.
3. Environment retirement: preserve a backup, verify it, establish ownership, approve,
   remove dependent resources in the right order, preserve shared resources, check absence.
4. Credential/maintenance lifecycle: establish affected consumers, prepare a replacement,
   verify transition and retire the old state only under the requested failure conditions.

The first three have [runnable planning examples](../examples/system-automation/README.md).
They establish declared-plan fidelity only. The fourth and conditional/transactional
extensions remain design acceptance cases until their semantic and execution gaps close.
The foundation example names Azure as managed context, but contains no Azure implementation
or credentials. It is not an infrastructure template that can provision an account.

Tests must include missing resources, same-capability resource substitution, wrong value
producers, removed verification, unexpected effects, conditional availability, partial
failure and stale observations at the layer owning each invariant. Deterministic fixtures
measure integration and compiler behavior. Live-agent generalization needs independently
reviewed held-out inputs and must not receive prewritten proposals or hidden expectations.

## Disposition of the earlier investigation work

The bounded ledger, attribution, immutable captures and budgets remain useful support.
They are not completion evidence for this direction. Counting source reads or passing
ledger-format tests cannot substitute for discovering, composing and checking a system
change. The original 12-case AI-A3 corpus remains frozen; the broader system portfolio
is additive and its independent live exposure policy is still required.

The bounded [S2.1 implementation](STRUCTURED_AUTHORING.md) adds structured authoring
and independent conditional fidelity. [S2.2 operation outcome assessment](OPERATION_OUTCOMES.md)
adds scoped resource identities and fresh authenticated observations over the same
accepted graph. It is a post-run evidence assessment, not an executor or execution
permission. [S2.3 failure regions](FAILURE_REGIONS.md) adds explicit protected and handler bodies,
propagation/recovery disposition, bounded read-only retries and independent region
requirements. Production observer deployment, safe changing retries and autonomous
partial-failure recovery remain open.

S2.4: [Attempt-aware run and recovery evidence](RUN_EVIDENCE.md) connects signed
invocation history to existing canonical regions and final state obligations, with
real isolated local service, data and credential integration proofs.

S2.5: [Durable external observation](DURABLE_RUN_OBSERVER.md) retains actual captures
across collector crashes and late delivery, then signs a caller-selected checkpoint
for the same evaluator. Reconciliation never redispatches work.

S2.6: [Resource invocation lineage](RESOURCE_INVOCATION_LINEAGE.md) checks the actual
producer available to every consumer, including failed read retries, and separately
confirms resource creation. Late captures extend the same durable observer protocol.
Production observer deployment, external receipt retention and runtime-allocated
identity contracts remain open.

S2.7: [Deployed observer and independent checkpoint retention](DEPLOYED_RUN_OBSERVER.md)
adds separate trusted-local collector/witness processes, explicit enrollment and
witness-checked publication. Independent production storage and cloud deployment remain
operator responsibilities. The [runtime identity proposal](RUNTIME_RESOURCE_IDENTITY_PROPOSAL.md)
is design only; unknown runtime identities still require a separately versioned implementation.


S2.8 implements [runtime resource identities](RUNTIME_RESOURCE_IDENTITIES.md): frozen logical
allocation obligations, distinct address/instance evidence, per-attempt consumers and retained
observer capture. Local service/database proofs obtain addresses after planning. This closes
the bounded dynamic-identity slice, not provider truth, production readiness or AI-A3. The next
selected slice is S3 evidence-driven system discovery: choose bounded observations from the
actual automation request and managed scope, preserving independent requirements and trust.

S3.1: [Scoped workspace discovery](SYSTEM_DISCOVERY.md) lets the existing authoring host
follow model-selected bounded directory listings and reads, extract source-attributed
JSON values and invalidate dependent conclusions when observed evidence changes.
Caller-owned source predicates bind those values to declared operation parameters through
existing canonical compiler fidelity checks. Original requirements remain retained unchanged.
The installed proof uses distinct JSON structures and adversarial provider replies; no
expected address is supplied in caller inputs. It remains a deterministic protocol test.
Managed-inventory provenance, upstream freshness and live discovery evaluation remain
open; this slice does not close S3 or certify autonomous system understanding.

S3.2: [Managed inventory provenance](MANAGED_INVENTORY_PROVENANCE.md) adds a separate,
operator-configured collection process, independent signer/scope grants and signed
request-bound exports. Discovery checks exact exported bytes and bounded collection
age through clarification and compilation, retaining the provenance of accepted
parameter bindings. This authenticates a collector's claim, not current remote truth.
The next slice is independently reviewed live discovery evaluation; S3 and AI-A3 remain
open, with no cloud execution or target support promotion.

S3.2 scope correction (PR #270): [reviewed discovery interpretation](REVIEWED_DISCOVERY.md)
removes caller-authored source selectors and operation bindings from v3 configuration.
The external agent proposes them from observations together with a complete structured
plan. Separate review is bound to the exact request, evidence and candidate; existing
compiler constraints remain independent. Provenance is supporting infrastructure.
Deterministic lifecycle transcripts validate this boundary but do not close live-agent
reasoning, unfamiliar-schema generalization or runtime-outcome acceptance.

### S3.3: callable planning feedback

The [agent planning tools](AGENT_PLANNING_TOOLS.md) give an existing agent actual
capability lookup, contract inspection and compiler feedback during plan formation.
The same interface supports infrastructure and data recovery without solution maps.
The agent host owns environment discovery and access control. Canonical draft
acceptance remains separate from original-goal review and observed runtime outcomes.
Host observation integration and live model evaluation remain open S3 work.
