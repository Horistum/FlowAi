# Flow Portfolio Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed Core roadmap identity: `0.9.7.10 Bounded Semantic Closure Gate`
Core roadmap item status: `completed`
Completed correction item: `0.9.7.10.2 Closure Evidence Boundary Integrity Correction`
Completed Core closure item: `0.9.7.10 Bounded Semantic Closure Gate` (`completed`)
Completed adapter roadmap item: `A1.0 GitHub Actions Artifact and Workspace Continuity` (`completed`)
Completed conformance roadmap item: `C1.0 Operational Domain Adequacy` (`completed`)
Completed architecture roadmap item: `AR0.1 Authority Responsibility Consolidation` (`completed`)
Completed semantic-integrity item: `SI-07 Align Public Schemas with Production Acceptance` (`completed`, Flow CI #2928)
Active semantic-integrity item: `SI-08 Typed Policy and State-Lifetime Semantics` (`next`, active work package)

## Active semantic-integrity correction

SI-08 owns project-direction section 1.8: canonical policy meaning must come from explicit typed standard forms rather than substring folklore, and mutable-state propagation must remain distinct from durable persistence lifetime.

The correction has two bounded production targets. First, arbitrary predicates such as `environment != prod` or incidental prose containing `retention` remain non-authoritative custom policy text; only explicit closed safety requirements or `retention:<value>`, `ttl:<value>` and `olderThan:<value>` forms may acquire standard policy meaning, while malformed recognized retention forms fail closed to clarification. Second, STATE continuity now carries explicit target-neutral `WORKFLOW` or `DURABLE` lifetime: workflow-local state requires propagation evidence without inventing durable storage, while durable state additionally requires persistence evidence.

SI-08 advances only the public contracts whose wire meaning changed: ExecutionPlan moves from `2.3` to `2.4` and the module descriptor schema moves from `1.2` to `1.3`. Intent remains `2.0`, AST `2.2`, ExecutionPlan lowering evidence `2.1`, TargetManifest `3.0`, TargetRegistry `3.2`, implementation package `0.9.5` and public standard `0.8.0`. This implementation does not promote target support or complete the SI-08 lifecycle.

SI-07 is completed evidence. Flow CI #2928 / run `31773901231` passed exact implementation/repair head `f27bf53afffb555fcef99b2db32770d9b241beba` and synthetic merge candidate `303fa34f0e3147a8482acf2c4905fca4cadaa3b0` before PR #135 merged as `fc905efa20c255846c098ccf058f560d61ee7378`. The distinct SI-07 completion transition passed Flow CI #2930 / run `31775736837` and merged as `9125c327a671c3c4f58cf306103a0fb21fdf3190`, activating SI-08 without borrowing predecessor implementation evidence.

## Core boundary

PR #95 merged the final bounded Core closure correction as `e25a81b9c7e7802556a0d5b34cf34185b19ed498`. Core v0.9.7 remains CLOSED and its exact 91-check pre-closure inventory remains frozen.

The Core closure remains historical and closed. Post-C1.0 SI-01 changed scoped control evidence and advanced the AST, ExecutionPlan and execution-plan lowering-evidence contracts to `2.1`; that later migration does not rewrite the versions certified by the historical `0.9.7.10` closure.

The live package remains `0.9.5` and the public standard remains `0.8.0`. Live artifact contracts are tracked independently: Intent `2.0`, AST `2.2`, ExecutionPlan `2.4`, execution-plan lowering evidence `2.1`, TargetManifest `3.0` and TargetRegistry `3.2`.

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

A0.4 does not promote any support class. Jenkins remains the only committed end-to-end executable reference.

## Problem corrected by A0.4

Registry summaries such as `retry: supported`, `approvals: supported` or `cron.schedule: supported` did not prove that the composed provider preserved an exact Flow control requirement.

The audit found concrete mismatches:

- Jenkins, GitHub Actions and Tekton could traverse or flatten retry bodies while discarding attempt, delay or backoff semantics;
- GitHub environment protection and Azure approvals are resource-owned configuration, not proof that the current provider composes them;
- Tekton did not compose approval controllers, retries, PipelineRun timeouts, `finally` tasks or scheduling;
- error handlers, `finally`, always-run behavior and domain rollback were being treated too loosely;
- CRON expression support did not prove timezone, concurrency or catch-up semantics;
- preserved RETRY and TIMEOUT policy metadata lacked enough value and scope detail for exact certification.

A platform feature list therefore remains context, not materialization authority.

## Adapter-owned control evidence

`adapters/controls/builtin-control-materialization.yaml` declares exactly one claim per built-in target for approval, retry, timeout, compensation and scheduling. Evidence contract version `1.0` is enforced.

Every family has a closed semantic contract partitioned into `supported`, `unsupported` and `unknown`. Each claim records mechanism, owner, exact scope, repository evidence, external platform context, prerequisites and limitations.

The completed honest matrix is:

| Target | Approval | Retry | Timeout | Compensation | Scheduling |
| --- | --- | --- | --- | --- | --- |
| `local` | unknown | unknown | unknown | unknown | unknown |
| `jenkins` | partial: inline manual approval | unsupported | unsupported | partial: protected error handler | partial: CRON |
| `github-actions` | unsupported | unsupported | unsupported | unsupported | partial: CRON |
| `tekton` | unsupported | unsupported | unsupported | unsupported | unsupported |
| `argo-workflows` | unknown | unknown | unknown | unknown | unknown |
| `azure-devops` | unknown | unknown | unknown | unknown | unknown |

Supported semantics require both independent `src/main` implementation evidence and independent `src/test` behavioral evidence. Registry references and official documentation cannot certify implementation support.

Symbolic `#anchors` must resolve. Evidence paths are repository-relative, cannot escape through `..` or symlinks, and are validated identically for YAML-loaded and typed documents.

Supported claim scopes must equal the union required by their supported target-neutral semantics. Unknown future semantics produce a fail-closed report rather than an unhandled lookup exception.

## Runtime authority

Production responsibilities are separated while retaining one orchestration path:

- `AdapterControlEvidenceIntegrityAuthority` validates inventory, semantic partitions, scopes, provider composition and evidence integrity;
- `AdapterControlRequirementAuthority` derives target-neutral requirements from the actual `ExecutionPlan`;
- `AdapterControlMaterializationAuthority` matches certified evidence and reconciles the decision into manifest evidence.

Requirement derivation preserves exact semantic, subject, completeness and scope:

- manual approval becomes `approval.manual.inline` at `STEP` scope;
- environment approval remains `approval.environment.resource` at `ENVIRONMENT` scope;
- external and unknown modes cannot impersonate manual approval;
- retry requirements remain at `TASK` scope;
- timeout variants retain step, task, workflow, per-attempt or cumulative scope;
- canonical flow-level error handling is recognized only from planner provenance (`onError_<n>` plus `errorHandlers.finally` capability) and actual preceding protected work;
- detached empty-body handlers remain blocking;
- scheduling derives CRON, interval, calendar, timezone, concurrency and catch-up requirements at `TRIGGER` scope;
- incomplete preserved RETRY/TIMEOUT metadata remains `PRESERVED_UNSPECIFIED` and blocking.

Requirement identifiers contain full semantic and canonical subject identity. Conflicting collisions fail rather than being silently deduplicated.

## Production materialization boundary

`CliTargetEvidenceAuthority` evaluates control evidence before executable materialization. Successful and diagnostic paths use the same reconciliation method and record evidence version, decision, requirement count and blocker count.

Any unsupported or unknown requirement:

- blocks executable readiness;
- produces exact compatibility diagnostics;
- retains a `REVIEW_ONLY` manifest for inspection;
- prevents target syntax emission;
- returns a distinct non-success CLI outcome when rendering was requested.

Stable readiness diagnostics are produced by `TargetReadinessDiagnosticCodeAuthority`. Unknown internal statuses fail closed instead of inventing public diagnostic codes.

## Behavioral proof

Tests cover positive and negative polarity for:

- approval mode and scope preservation;
- Jenkins provider-owned inline approval;
- canonical and nested Jenkins protected `try/catch` boundaries;
- detached-handler rejection;
- Jenkins and GitHub Actions CRON through parser, planner, explicit target selection, control assessment, production manifest generation, readiness reconciliation and concrete rendering;
- planner-derived `trigger.schedule.cron` provenance;
- authored timezone rejection;
- retry flattening rejection;
- incomplete preserved timeout remaining unknown;
- profile-only targets remaining unknown;
- independent implementation and behavior evidence;
- anchor resolution and repository-path containment;
- typed evidence integrity;
- stable diagnostic mapping;
- blocked readiness with review-only evidence and no target artifact;
- lifecycle rejection of premature evidence, skipped progress, missing required files and evidence-free completion.

Adapter inventory `1.3` adds five A0.4 checks after the frozen Core closure: lifecycle integrity, control evidence integrity, runtime authority, unsupported-control demotion and platform-capability separation.

## Validation history

Earlier CI runs correctly rejected incomplete fixtures, imprecise evidence polarity, dynamic diagnostic codes, optimistic readiness assumptions, invalid runtime target-subset certification and schedule tests that bypassed production generation.

The final review then exposed additional test-construction defects instead of hiding them:

- Flow CI #2371 rejected an invalid scope expectation and CRON fixtures that lacked executable provider work;
- Flow CI #2373 rejected manually authored tasks without canonical effect evidence;
- Flow CI #2374 proved Jenkins rendering and exposed missing GitHub schedule capability provenance;
- Flow CI #2375 isolated the remaining GitHub failure to a trigger attached after planning;
- the final fixture now injects the trigger into canonical AST and lets `FlowPlanner` derive `trigger.schedule.cron` before materialization.

Flow CI #2376, run `30420527005`, passed exact implementation head `a5040767698fed38d6efd0118de40312c77c913e` and synthetic merge candidate `8c3b837cb9416acb2de3333a81b1c7eb811d492d`. Both compile/test and conformance jobs passed independently with adapter inventory `1.3` active.

That passed implementation boundary is recorded in the A0.4 work package. The completion metadata commit must pass a separate exact-head and synthetic merge-candidate CI boundary before the PR is marked ready for review.

## Architecture boundary

A0.4 adds no renderer, provider, runtime executor, automatic target selection, target-specific public DSL, new canonical capability, new Core control kind or Core conformance check. Missing provider behavior remains explicit future adapter work.
