# A0.4 Control Requirement Materialization

## Purpose

A0.4 certifies whether a composed target provider can enforce control semantics that are already present in Flow intent, AST, execution-plan, failure and trigger contracts.

It does not add new universal Core meaning. Core v0.9.7 remains closed, and its pre-closure conformance inventory remains frozen at 91 checks.

The adapter layer answers a narrower question:

> Given an exact target, provider and Flow plan, which required approval, retry, timeout, compensation and scheduling semantics are implemented faithfully, which are unsupported, and which remain unknown?

## Why coarse support flags are insufficient

A target registry entry such as `retry: supported` or `approvals: supported` cannot prove concrete enforcement.

A useful control claim must identify at least:

- the exact semantic property being claimed;
- the scope at which it applies;
- the component that owns the mechanism;
- the provider path that emits or configures it;
- the failure and cancellation behavior;
- prerequisites outside the generated artifact;
- independent implementation and behavioral evidence in the repository;
- explicit unsupported and unknown subsets.

The same platform can support a feature generally while the current Flow provider does not materialize it. A0.4 treats this difference as a first-class contract rather than a documentation footnote.

## Existing contracts consumed by A0.4

A0.4 deliberately does not expand the frozen `ControlRequirementKind` enum to make every execution modifier look identical.

Instead it consumes the existing owners:

| Requirement family | Existing Flow source |
| --- | --- |
| Approval | `ApprovalNode` and canonical planning-control evidence |
| Retry | `RetryGroupNode` and preserved `RETRY` policy metadata |
| Timeout | preserved `TIMEOUT` policy metadata until a typed timeout node exists |
| Compensation | `TryPlanNode`, error-handler paths and preserved failure rollback metadata |
| Scheduling | `PlanTrigger` and `PlanSchedule` |

This preserves the distinction between safety authorization, execution modifiers, failure handling and trigger ownership.

## Closed adapter semantic contracts

Each target declares exactly one claim for every family. Every claim partitions the entire known family contract into `supported`, `unsupported` and `unknown` semantics.

### Approval

- `approval.manual.inline`
- `approval.environment.resource`
- `approval.external`

### Retry

- `retry.attempt-limit`
- `retry.delay.fixed`
- `retry.backoff.variable`
- `retry.failure-filter`
- `retry.cancellation`

### Timeout

- `timeout.step`
- `timeout.task`
- `timeout.workflow`
- `timeout.per-attempt`
- `timeout.cumulative`

### Compensation

- `compensation.error-handler`
- `compensation.finally`
- `compensation.rollback`
- `compensation.always-run`

### Scheduling

- `scheduling.cron`
- `scheduling.interval`
- `scheduling.calendar`
- `scheduling.timezone`
- `scheduling.concurrency`
- `scheduling.catch-up`

Missing semantics, overlapping partitions and status/partition contradictions fail closed. Unknown authored variants such as an unrecognized approval mode or schedule kind become explicit unknown requirements and cannot match a known claim accidentally.

## Distribution evidence authority

`adapters/controls/builtin-control-materialization.yaml` is the distribution-owned evidence document. Its supported contract version is exactly `1.0`.

For each target and family it declares:

- closed status: `SUPPORTED`, `PARTIAL`, `UNSUPPORTED` or `UNKNOWN`;
- concrete mechanism;
- mechanism ownership;
- supported or applicable scopes;
- complete semantic partition;
- repository implementation evidence;
- repository behavioral evidence for every supported semantic;
- official platform references kept separate from implementation evidence;
- prerequisites;
- limitations.

Supported semantics require both:

1. at least one independent `src/main` implementation reference; and
2. at least one independent `src/test` behavioral reference.

A source reference that declares `#symbolOrTest` must resolve that anchor in the cited file. File-level references remain valid for negative absence evidence. Evidence paths must be repository-relative and cannot escape the repository root. Empty, duplicate, self-referential, unresolved and registry-only evidence fails closed.

Repository evidence proves what this distribution implements. Official platform documentation supplies external context and cannot satisfy an implementation claim by itself.

The same integrity checks apply to YAML-loaded and typed documents. Programmatic construction is not a bypass around the strict source contract.

## Internal authority split

The production boundary remains one `AdapterControlMaterializationAuthority`, but its internal responsibilities are separated:

- `AdapterControlEvidenceIntegrityAuthority` validates inventory, partitions, ownership, provider composition and evidence integrity;
- `AdapterControlRequirementAuthority` derives exact target-neutral requirements from `ExecutionPlan` without consulting targets or providers;
- `AdapterControlMaterializationAuthority` matches certified evidence to derived requirements and reconciles the decision into target manifest evidence.

This prevents one large class from simultaneously parsing evidence, reinterpreting plans and mutating manifests while retaining one production decision path.

Runtime composition may intentionally expose only a subset of distribution targets. The runtime authority certifies every active target exactly. The full distribution conformance path still requires the complete built-in target inventory.

## Current honest target matrix

| Target | Approval | Retry | Timeout | Compensation | Scheduling |
| --- | --- | --- | --- | --- | --- |
| `local` | unknown | unknown | unknown | unknown | unknown |
| `jenkins` | partial: inline manual approval | unsupported | unsupported | partial: error handler | partial: CRON |
| `github-actions` | unsupported | unsupported | unsupported | unsupported | partial: CRON |
| `tekton` | unsupported | unsupported | unsupported | unsupported | unsupported |
| `argo-workflows` | unknown | unknown | unknown | unknown | unknown |
| `azure-devops` | unknown | unknown | unknown | unknown | unknown |

The matrix is intentionally narrower than general platform feature lists.

### Jenkins

The composed Jenkins provider owns a native `input` approval payload and the renderer emits it in the dependency path. This certifies `approval.manual.inline` at `STEP` scope only.

`ApprovalNode(mode=environment)` remains an environment-resource requirement and is blocked. `ApprovalNode(mode=external)` remains external approval. Unknown modes become explicit unknown requirements. They are never reinterpreted as manual approval.

The Jenkins renderer currently renders a `RetryGroupNode` by rendering its children. It does not emit attempt count, delay or backoff. Retry is therefore unsupported despite Jenkins itself having retry primitives.

A normal `TryPlanNode` becomes `try/catch`. The canonical flow-level handler shape is composed around all prior flow nodes by the Jenkins generator. A detached empty-body handler without protected work is not certified. Error-handler support does not imply `finally`, guaranteed always-run behavior or domain rollback.

The renderer emits CRON expressions. It does not materialize the separate Flow timezone or scheduler concurrency/catch-up semantics.

### GitHub Actions

Environment protection is resource-owned configuration. The current Flow GitHub Actions provider has no approval payload definition and therefore cannot certify environment approval merely because a job could reference an environment.

Retry groups are flattened into ordinary jobs. Workflow rerun is not task-scoped retry evidence.

Error-handler jobs are not emitted with a certified `failure()` or `always()` guard, so dependency metadata does not prove compensation.

CRON expressions are emitted. Authored timezone, catch-up and scheduler concurrency semantics are not.

### Tekton

The current provider emits Pipeline structure but does not compose an approval CustomRun/controller, retries, PipelineRun timeout contracts, `finally` tasks or trigger scheduling.

General Tekton support for those concepts is future implementation context, not present evidence.

### Argo Workflows and Azure DevOps

Both remain profile-only in the current adapter portfolio. All A0.4 claims remain `UNKNOWN` until a provider is composed.

Argo `suspend` is not automatically a permissioned approval mechanism. Azure approvals and checks are resource-owner configuration outside pipeline YAML. Neither platform is promoted by feature recognition alone.

## Runtime requirement derivation

`AdapterControlRequirementAuthority` derives exact requirements from the plan rather than from the target profile.

Examples:

- manual `ApprovalNode` requires `approval.manual.inline` at `STEP` scope;
- environment approval requires `approval.environment.resource` at `ENVIRONMENT` scope;
- unknown approval modes remain unknown and use `UNSPECIFIED` scope;
- `RetryGroupNode(max=3, delay=10s, backoff=fixed)` requires attempt-limit and fixed-delay semantics at `TASK` scope;
- a non-fixed backoff adds `retry.backoff.variable`;
- a protected `TryPlanNode.errorHandler` requires `compensation.error-handler` at `WORKFLOW` scope;
- a detached error handler without protected work remains unknown and blocking;
- a rollback task or preserved `failure.rollback=true` requires `compensation.rollback`;
- a CRON trigger requires `scheduling.cron` at `TRIGGER` scope;
- an authored timezone adds `scheduling.timezone`;
- preserved RETRY or TIMEOUT metadata without exact value and scope becomes `PRESERVED_UNSPECIFIED` and remains unknown.

Requirement identity includes the full semantic and subject identity. Conflicting collisions fail instead of being discarded by deduplication.

An assessment is `MATCHED` only when every complete requirement is supported at the exact required scope.

## Production materialization boundary

`CliTargetEvidenceAuthority` evaluates adapter control evidence before executable target generation.

Both successful and diagnostic materialization paths pass through the same control reconciliation method. Every target manifest therefore records:

- adapter control evidence version;
- decision;
- requirement count;
- blocker count.

When every requirement is matched:

- normal materialization continues;
- the target manifest records the matched evidence;
- rendering still requires the existing target readiness and explicit render request.

When any requirement is unsupported or unknown:

- executable generation is not authorized;
- diagnostic target evidence is still generated;
- compatibility receives exact adapter control blockers;
- execution readiness becomes `BLOCKED`;
- target render mode remains `REVIEW_ONLY`, allowing inspection while forbidding target syntax emission;
- an explicit render request returns the review-required process status.

The diagnostic path preserves facts. It does not provide a second route to executable output.

## Stable diagnostic normalization

A0.4 exposed a pre-existing defect in `TargetCompatibilityReadinessAnalyzer`: internal renderer status was converted into public diagnostic codes using string concatenation.

`TargetReadinessDiagnosticCodeAuthority` now maps every known internal readiness finding to an existing stable public diagnostic code. Unknown internal statuses fail closed instead of creating accidental public API identifiers.

## External ecosystem assessment

The architecture was compared with official implementations and documentation for Jenkins, GitHub Actions, Tekton, Argo Workflows, GitLab CI/CD and Azure Pipelines.

The common pattern is not a universal control syntax. The common pattern is separation of ownership:

- some controls are workflow-definition primitives;
- some belong to a run object rather than a reusable pipeline definition;
- some are target-resource configuration;
- some require an external controller or application;
- failure, timeout and retry scopes differ significantly;
- schedule expression, timezone, concurrency and missed-run behavior are separate properties.

This supports Flow's direction: preserve target-neutral meaning, derive exact requirements, then certify a concrete provider mechanism. It argues against target feature booleans becoming materialization authority.

Representative official sources used for the assessment:

- Jenkins Pipeline Basic Steps and Pipeline Input Step documentation;
- GitHub Actions workflow syntax and deployments/environments documentation;
- Tekton Pipelines, PipelineRuns and custom task examples;
- Argo Workflows retry, suspend, exit-handler and CronWorkflow documentation;
- GitLab CI/CD YAML, job-control and pipeline-schedule documentation;
- Azure Pipelines approvals/checks, conditions, timeout and scheduled-trigger documentation.

The source URLs are retained per target claim in the adapter evidence document so the distinction between external capability context and repository implementation evidence remains auditable.

## Conformance and behavioral proof

Adapter inventory `1.3` adds five A0.4 checks after the frozen Core closure:

- lifecycle integrity;
- control evidence integrity;
- runtime control authority;
- unsupported control demotion;
- platform capability separation.

Positive and negative behavior proves:

- Jenkins provider-backed inline approval matches only the manual mode and exact scope;
- environment, external and unknown approval modes cannot impersonate manual approval;
- Jenkins canonical flow-level and nested error handlers render protected `try/catch` boundaries;
- detached handlers without protected work remain blocked;
- Jenkins and GitHub Actions CRON serializers emit their native schedule form through the production materialization boundary;
- authored timezone is not discarded;
- retry flattening is blocked for Jenkins, GitHub Actions and Tekton;
- preserved timeout policy cannot disappear or become falsely specific;
- profile-only platform features remain unknown;
- supported claims require separate implementation and behavior evidence;
- symbolic source anchors must resolve;
- typed evidence cannot omit evidence, duplicate references or escape the repository root;
- official platform documentation cannot impersonate implementation evidence;
- completion requires a distinct passed implementation boundary.

## Validation history

Flow CI #2315 rejected the first implementation because one negative test constructed an incomplete `RetryGroupNode` fixture. Production sources compiled; the fixture was corrected without changing runtime behavior.

Flow CI #2316 compiled the implementation and rejected release-honesty wording plus an imprecise evidence-polarity rule. Release wording was corrected and registry-only evidence was separated from independent implementation proof.

Flow CI #2324 rejected persisted reference CLI bundles because the existing readiness reconciler invented `TARGET_COMPATIBILITY_UNSUPPORTED` through string concatenation. A closed mapping now emits only existing stable catalog codes.

Flow CI #2333 then proved that the reference intent correctly becomes blocked by incomplete preserved control metadata; an older test had asserted the previous optimistic `DEGRADED` classification. The test was strengthened to verify exact control blockers, diagnostic evidence and absent rendering.

Flow CI #2338 rejected the first runtime certification because subset test compositions intentionally loaded one target while the evidence document described the complete distribution. Runtime certification now validates every active target exactly; full distribution conformance still requires the complete inventory.

Flow CI #2359 rejected schedule behavior tests that bypassed the production materialization boundary with manually constructed incomplete manifests. The tests now use explicit target selection, control assessment, manifest generation, readiness reconciliation and concrete rendering.

The subsequent senior review also found and corrected:

- approval modes being collapsed into manual inline approval;
- scope declarations not participating in matching;
- runtime use of an unvalidated evidence document;
- a decorative rather than enforced evidence version;
- lossy requirement identifiers;
- divergent metadata construction between successful and diagnostic paths;
- supported claims accepting implementation or behavior evidence instead of requiring both;
- unresolved symbolic evidence anchors;
- typed evidence bypassing source-shape and repository-path integrity;
- one oversized authority owning evidence validation, requirement derivation, matching and manifest reconciliation;
- inaccurate governance wording that described pre-merge A0.3 validation as validation on the merged commit.

The current A0.4 head remains external exact-head CI evidence. No implementation evidence is authored and no A0.5 transition is selected until exact-head and synthetic merge-candidate Flow CI pass independently with adapter inventory `1.3` active.

## Non-goals

A0.4 does not:

- add new Core control kinds;
- implement missing target syntax;
- configure external environments, approver groups or resource checks;
- add a runtime executor;
- add target-specific public syntax;
- claim continuity satisfaction;
- complete general trigger materialization coverage;
- modify the frozen Core pre-closure inventory;
- change package, public standard or artifact contract versions.

Missing provider behavior remains explicit work for later bounded adapter items rather than being smuggled into A0.4 to preserve a support label.
