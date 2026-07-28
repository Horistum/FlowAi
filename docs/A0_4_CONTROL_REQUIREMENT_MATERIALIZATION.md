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
- behavioral evidence in the repository;
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

Each target declares exactly one claim for every family. Every claim partitions the entire family contract into `supported`, `unsupported` and `unknown` semantics.

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

Missing semantics, overlapping partitions and status/partition contradictions fail closed.

## Distribution evidence authority

`adapters/controls/builtin-control-materialization.yaml` is the distribution-owned evidence authority.

For each target and family it declares:

- closed status: `SUPPORTED`, `PARTIAL`, `UNSUPPORTED` or `UNKNOWN`;
- concrete mechanism;
- mechanism ownership;
- supported scopes;
- complete semantic partition;
- repository implementation evidence;
- official platform references kept separate from implementation evidence;
- prerequisites;
- limitations.

Repository evidence proves what this distribution implements. Official platform documentation supplies external context and cannot satisfy an implementation claim by itself.

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

The composed Jenkins provider owns a native `input` approval payload and the renderer emits it in the dependency path. This certifies `approval.manual.inline` only.

The Jenkins renderer currently renders a `RetryGroupNode` by rendering its children. It does not emit attempt count, delay or backoff. Retry is therefore unsupported despite Jenkins itself having retry primitives.

`TryPlanNode` becomes `try/catch`, which certifies an error-handler path but not `finally`, guaranteed always-run behavior or domain rollback.

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

## Runtime assessment

`AdapterControlMaterializationAuthority` derives exact requirements from the plan rather than from the target profile.

Examples:

- `ApprovalNode` requires `approval.manual.inline`;
- `RetryGroupNode(max=3, delay=10s, backoff=fixed)` requires attempt-limit and fixed-delay semantics;
- a non-fixed backoff adds `retry.backoff.variable`;
- a non-empty `TryPlanNode.errorHandler` requires `compensation.error-handler`;
- a rollback task or preserved `failure.rollback=true` requires `compensation.rollback`;
- a CRON trigger requires `scheduling.cron`;
- an authored timezone adds `scheduling.timezone`;
- preserved `TIMEOUT` policy metadata requires explicit timeout evidence rather than disappearing during lowering.

An assessment is `MATCHED` only when every exact requirement is supported by the target claim.

## Production materialization boundary

`CliTargetEvidenceAuthority` evaluates adapter control evidence before executable target generation.

When every requirement is matched:

- normal materialization continues;
- the target manifest records the matched decision and requirement count.

When any requirement is unsupported or unknown:

- executable generation is not authorized;
- diagnostic target evidence is still generated;
- compatibility receives exact adapter control blockers;
- the manifest is non-executable and review-only;
- target syntax is not emitted.

The diagnostic path preserves facts. It does not provide a second route to executable output.

## External ecosystem assessment

The architecture was compared with the official implementations and documentation for Jenkins, GitHub Actions, Tekton, Argo Workflows, GitLab CI/CD and Azure Pipelines.

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

The source URLs are retained per target claim in the adapter evidence manifest so the distinction between external capability context and repository implementation evidence remains auditable.

## Conformance

Adapter inventory `1.3` adds five A0.4 checks after the frozen Core closure:

- lifecycle integrity;
- control evidence integrity;
- runtime control authority;
- unsupported control demotion;
- platform capability separation.

Positive and negative behavior proves:

- Jenkins provider-backed inline approval matches;
- Jenkins and GitHub Actions CRON subsets match;
- authored timezone is not discarded;
- retry flattening is blocked for Jenkins, GitHub Actions and Tekton;
- preserved timeout policy cannot disappear into executable output;
- profile-only platform features remain unknown;
- official platform documentation cannot impersonate implementation evidence;
- completion requires a distinct passed implementation boundary.

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
