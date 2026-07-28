# Flow Portfolio Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed Core roadmap identity: `0.9.7.10 Bounded Semantic Closure Gate`
Core roadmap item status: `completed`
Completed correction item: `0.9.7.10.2 Closure Evidence Boundary Integrity Correction`
Completed Core closure item: `0.9.7.10 Bounded Semantic Closure Gate` (`completed`)
Completed adapter roadmap item: `A0.3 Capability Binding Migration` (`completed`)
Active adapter roadmap item: `A0.4 Control Requirement Materialization` (`next`, work package `active`)

## Core boundary

PR #95 merged the final bounded Core closure correction as `e25a81b9c7e7802556a0d5b34cf34185b19ed498`. Core v0.9.7 remains CLOSED. Its exact 91-check pre-closure inventory remains frozen. Adapter checks continue only after `v0.9.7.10.bounded-semantic-closure`.

The implementation package remains `0.9.5`, the public standard remains `0.8.0`, and the artifact contract remains `2.0`.

A0.4 consumes existing Core, intent, AST, execution-plan, failure and trigger contracts. It does not extend the frozen `ControlRequirementKind` enum and does not make Core depend on adapter evidence.

## Completed adapter baseline

PR #96 merged A0.1 as `dbd1529cd9da1b21d13bd45d3af1a84361b9abf1`. PR #97 merged A0.2 as `964a9c4f8bf9ce9dc8771c99a68393c9edc35807`. PR #98 merged A0.3 as `9c09a03b3fa6a5b7b2114ff1d7aa7f533bacf930`.

The adapter portfolio remains:

| Target | Role | Support class |
| --- | --- | --- |
| `local` | semantic reference | `PROFILE_ONLY` |
| `jenkins` | target adapter | `EXECUTABLE_REFERENCE` |
| `github-actions` | target adapter | `NATIVE_LEAF_ONLY` |
| `tekton` | target adapter | `NATIVE_LEAF_ONLY` |
| `argo-workflows` | target adapter | `PROFILE_ONLY` |
| `azure-devops` | target adapter | `PROFILE_ONLY` |

A0.4 does not promote any target support class. Jenkins remains the only committed end-to-end executable reference.

## A0.4 defect boundary

Before A0.4, target registry fields such as `retry: supported`, `approvals: supported` or `scheduling: partial` were broad compatibility summaries. They did not prove that the composed Flow provider preserved the exact required semantics.

Repository audit found concrete mismatches:

- the Jenkins renderer traversed `RetryGroupNode.body` but discarded `max`, `delay` and `backoff`;
- GitHub Actions and Tekton flattened retry groups into ordinary jobs/tasks;
- the GitHub Actions provider had no approval payload even though environment protection exists as external resource configuration;
- Tekton did not compose an approval CustomRun/controller, PipelineRun timeout, retries, `finally` tasks or trigger scheduling;
- Jenkins rendered `try/catch`, but not guaranteed `finally`, always-run behavior or native domain rollback;
- GitHub Actions and Tekton error-handler lowering did not certify failure/always/finally execution semantics;
- Jenkins and GitHub Actions emitted CRON expressions but did not preserve the separate Flow timezone, scheduler concurrency or missed-run contract;
- preserved RETRY and TIMEOUT policy metadata retained type and name but not enough scope/value detail for exact provider certification.

A platform feature list therefore could not remain a materialization authority.

## Adapter-owned control evidence

`adapters/controls/builtin-control-materialization.yaml` declares exactly one claim per target for each family:

- approval;
- retry;
- timeout;
- compensation;
- scheduling.

Each family has a closed semantic contract and a complete partition into `supported`, `unsupported` and `unknown` entries. Every claim also records mechanism, ownership, scope, repository evidence, official platform context, prerequisites and limitations.

The current honest matrix is:

| Target | Approval | Retry | Timeout | Compensation | Scheduling |
| --- | --- | --- | --- | --- | --- |
| `local` | unknown | unknown | unknown | unknown | unknown |
| `jenkins` | partial: inline manual approval | unsupported | unsupported | partial: error handler | partial: CRON |
| `github-actions` | unsupported | unsupported | unsupported | unsupported | partial: CRON |
| `tekton` | unsupported | unsupported | unsupported | unsupported | unsupported |
| `argo-workflows` | unknown | unknown | unknown | unknown | unknown |
| `azure-devops` | unknown | unknown | unknown | unknown | unknown |

Supported semantics require at least one independent `src/main` or `src/test` implementation evidence reference. Target registry evidence may corroborate an unsupported projection state, but it cannot be the only evidence and cannot certify support. Official platform URLs are stored separately and never satisfy repository implementation evidence.

## Runtime control assessment

`AdapterControlMaterializationAuthority` derives requirements from the actual plan:

- `ApprovalNode` produces `approval.manual.inline`;
- `RetryGroupNode` produces attempt-limit, delay and backoff requirements according to its authored fields;
- `TryPlanNode.errorHandler` produces an error-handler requirement and a rollback task adds domain rollback;
- `PlanTrigger` and `PlanSchedule` produce exact scheduling requirements, including authored timezone, concurrency and catch-up properties;
- `failure.rollback=true` produces a rollback requirement;
- preserved RETRY/TIMEOUT metadata produces a `PRESERVED_UNSPECIFIED` blocker because the current lowering evidence lacks exact scope or value.

An adapter assessment is `MATCHED` only when every exact and complete requirement is supported. Unknown and unsupported requirements block executable target generation.

## Production boundary

`CliTargetEvidenceAuthority` evaluates adapter control evidence before executable materialization.

Matched requirements permit the normal target pipeline. A blocked assessment enters the existing diagnostic materialization path, adds exact control compatibility findings, marks the manifest non-executable and prevents target syntax emission. Review evidence remains available; the diagnostic path is not an alternate execution path.

A new CLI integration test proves that unsupported Jenkins retry produces persisted review evidence, no `Jenkinsfile`, explicit control blockers and `CLI_RENDER_NOT_AUTHORIZED`.

## Stable diagnostic normalization

A0.4 exposed a pre-existing defect in `TargetCompatibilityReadinessAnalyzer`: internal renderer status was converted into a public diagnostic code using string concatenation, for example `TARGET_COMPATIBILITY_UNSUPPORTED`.

That code was not in the stable diagnostic catalog, so an otherwise correct diagnostic bundle failed public diagnostic coverage. The fix introduces a closed `TargetReadinessDiagnosticCodeAuthority` that maps every known internal materialization/projection status to an existing stable catalog code. Unknown future statuses fail closed instead of inventing public API identifiers.

This is diagnostic integrity maintenance, not a new Core semantic contract or public standard version change.

## External DevOps ecosystem assessment

The architecture was compared against official implementations and documentation for Jenkins, GitHub Actions, Tekton, Argo Workflows, GitLab CI/CD and Azure Pipelines.

The common pattern is separation of ownership rather than a universal control boolean:

- Jenkins manual approval is a workflow `input` step;
- GitHub and Azure approvals are commonly environment/resource-owned configuration;
- Tekton approval requires a composed custom task/controller;
- Argo `suspend` is a pause mechanism and not permissioned approval by itself;
- retry and timeout scope may belong to a step, task, job, run object or whole workflow;
- error handlers, `finally`, exit handlers and domain rollback are different guarantees;
- CRON expression, timezone, concurrency and missed-run behavior are separate schedule semantics.

This supports Flow's current direction: target-neutral meaning, exact derived requirement, then provider-owned evidence. It rejects the opposite direction in which a target feature flag or popular platform pattern becomes universal semantic truth.

The official external references are retained in `docs/A0_4_CONTROL_REQUIREMENT_MATERIALIZATION.md` and in the adapter evidence manifest, separately from repository implementation evidence.

## Behavior and conformance

Adapter inventory `1.3` adds five A0.4 checks after the frozen Core closure:

- lifecycle integrity;
- control evidence integrity;
- runtime control authority;
- unsupported control demotion;
- platform capability separation.

Tests prove positive and negative polarity for:

- Jenkins provider-owned inline approval;
- Jenkins and GitHub Actions CRON;
- authored timezone rejection;
- retry flattening rejection for Jenkins, GitHub Actions and Tekton;
- incomplete preserved timeout remaining UNKNOWN;
- profile-only targets remaining UNKNOWN;
- registry-only evidence rejection;
- independent positive implementation evidence;
- stable public readiness diagnostic mapping;
- CLI review-only behavior with no target syntax;
- forward-stable A0.4 lifecycle progress.

## Validation history

Flow CI #2315 rejected the first implementation because one negative test constructed an incomplete `RetryGroupNode` fixture. Production sources compiled; the fixture was corrected without changing runtime behavior.

Flow CI #2316 compiled the implementation and rejected two honesty defects:

- release metadata did not use the exact `external exact-head CI evidence` wording required by the release policy;
- two negative compensation claims cited target registry evidence under an authority rule that had not yet distinguished support proof from negative corroboration.

Release wording was corrected. The evidence authority was refactored into separate contracts/loader and assessment classes. Registry-only evidence now fails, supported semantics require independent implementation evidence, and registry references can only supplement a negative claim.

Flow CI #2324 rejected persisted reference CLI bundles because the existing readiness reconciler invented `TARGET_COMPATIBILITY_UNSUPPORTED` through string concatenation. A closed mapping now emits only existing stable catalog codes and rejects unknown future internal statuses.

The current A0.4 head remains external exact-head CI evidence. No implementation evidence is authored and no A0.5 transition is selected until exact-head and synthetic merge-candidate Flow CI pass independently with adapter inventory `1.3` active.

## Architecture boundary

A0.4 does not add a renderer, provider, runtime executor, target-specific public DSL, automatic target selection, new canonical capability, new Core control kind or new Core conformance check. It certifies existing control semantics at the adapter boundary, preserves diagnostic evidence and keeps incomplete implementation explicit.
