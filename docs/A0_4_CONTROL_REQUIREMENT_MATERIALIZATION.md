# A0.4 Control Requirement Materialization

## Status

A0.4 is completed. Flow CI #2376 passed the implementation boundary on exact head `a5040767698fed38d6efd0118de40312c77c913e` and synthetic merge candidate `8c3b837cb9416acb2de3333a81b1c7eb811d492d` with compile, test and conformance validation successful on both revisions.

The completion metadata head must pass a distinct exact-head and synthetic merge-candidate boundary before PR readiness. A0.5 is selected as next without including an A0.5 work package or implementation.

Core v0.9.7 remains closed and its exact 91-check pre-closure inventory remains frozen.

## Purpose

A0.4 certifies whether a composed target provider can enforce control semantics already present in Flow intent, AST, execution-plan, failure and trigger contracts.

It answers one bounded question:

> Given an exact target, provider and Flow plan, which required approval, retry, timeout, compensation and scheduling semantics are implemented faithfully, which are unsupported, and which remain unknown?

A0.4 does not add universal meaning. Implementation evidence may disprove a support assumption, but it cannot redefine Core semantics.

## Why capability flags are insufficient

A registry entry such as `retry: supported`, `approvals: supported` or `cron.schedule: supported` describes broad compatibility. It does not prove that the current Flow provider materializes the exact required semantics.

A concrete control claim must identify:

- the exact semantic property;
- the scope at which it applies;
- the component that owns enforcement;
- the composed provider implementation path;
- independent implementation and behavioral evidence;
- prerequisites and limitations;
- explicit unsupported and unknown subsets.

Official platform documentation remains external context. Provider identity, feature recognition and target registry flags are never implementation proof.

## Existing Flow contracts

A0.4 consumes existing target-neutral contracts rather than expanding the frozen `ControlRequirementKind` enum.

| Family | Existing Flow source |
| --- | --- |
| Approval | `ApprovalNode` and canonical planning-control evidence |
| Retry | `RetryGroupNode` and preserved RETRY policy metadata |
| Timeout | preserved TIMEOUT policy metadata until a typed timeout node exists |
| Compensation | `TryPlanNode`, error-handler paths and preserved rollback metadata |
| Scheduling | `PlanTrigger` and `PlanSchedule` |

This preserves distinctions between safety authorization, execution modifiers, failure handling and trigger ownership.

## Closed semantic contracts

Every target has exactly one claim for each family. Every claim partitions the complete known family contract into `supported`, `unsupported` and `unknown`.

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

Missing semantics, overlapping partitions and status/partition contradictions fail closed. Unknown authored variants become explicit unknown requirements and cannot match a known claim accidentally.

## Evidence authority

`adapters/controls/builtin-control-materialization.yaml` is the distribution-owned evidence document. Supported contract version is exactly `1.0`.

Each claim records:

- closed status;
- concrete mechanism and owner;
- applicable scopes;
- complete semantic partition;
- repository implementation evidence;
- repository behavioral evidence;
- official platform references kept separate from implementation proof;
- prerequisites and limitations.

Every supported semantic requires at least one independent `src/main` implementation reference and one independent `src/test` behavioral reference.

A source reference with `#symbolOrTest` must resolve that anchor. File-level references remain available for negative absence evidence. Paths must be repository-relative and cannot escape through `..` or symlinks. Empty, duplicate, unresolved, self-referential and registry-only evidence fails closed.

YAML-loaded and typed evidence documents use the same integrity authority. Programmatic construction is not a validation bypass.

## Scope contract

Scope participates in certification rather than serving as descriptive prose.

The target-neutral contract maps each supported semantic to its valid scope:

- manual and external approval: `STEP`;
- environment approval: `ENVIRONMENT`;
- retry: `TASK`;
- step timeout: `STEP`;
- task and per-attempt timeout: `TASK`;
- workflow and cumulative timeout: `WORKFLOW`;
- compensation: `WORKFLOW`;
- scheduling: `TRIGGER`.

A supported claim must declare exactly the union required by its supported semantics. Too broad, too narrow or contradictory scope declarations fail evidence certification.

Unknown future supported semantics produce a structured fail-closed report instead of an unhandled map lookup exception.

## Internal authority split

One production entry point remains: `AdapterControlMaterializationAuthority`.

Its responsibilities are separated internally:

- `AdapterControlEvidenceIntegrityAuthority` validates inventory, partitions, scope, ownership, provider composition, paths and anchors;
- `AdapterControlRequirementAuthority` derives exact requirements from `ExecutionPlan` without consulting targets;
- `AdapterControlMaterializationAuthority` matches certified evidence and reconciles decisions into manifest evidence.

Runtime compositions may expose a subset of distribution targets. Runtime certification validates every active target exactly, while full adapter conformance validates the complete built-in inventory.

## Honest target matrix

| Target | Approval | Retry | Timeout | Compensation | Scheduling |
| --- | --- | --- | --- | --- | --- |
| `local` | unknown | unknown | unknown | unknown | unknown |
| `jenkins` | partial: inline manual approval | unsupported | unsupported | partial: protected error handler | partial: CRON |
| `github-actions` | unsupported | unsupported | unsupported | unsupported | partial: CRON |
| `tekton` | unsupported | unsupported | unsupported | unsupported | unsupported |
| `argo-workflows` | unknown | unknown | unknown | unknown | unknown |
| `azure-devops` | unknown | unknown | unknown | unknown | unknown |

The matrix is intentionally narrower than general platform feature lists.

## Requirement derivation

`AdapterControlRequirementAuthority` derives exact requirements from the actual plan:

- manual approval becomes `approval.manual.inline` at `STEP` scope;
- environment approval remains `approval.environment.resource` at `ENVIRONMENT` scope;
- external and unknown modes cannot impersonate manual approval;
- retry preserves attempt, delay and backoff requirements at `TASK` scope;
- preserved timeout variants retain their exact target-neutral scope;
- protected error handling requires `compensation.error-handler` at `WORKFLOW` scope;
- rollback intent requires `compensation.rollback`;
- a CRON trigger requires `scheduling.cron` at `TRIGGER` scope;
- authored timezone, concurrency and catch-up properties become separate requirements;
- incomplete RETRY/TIMEOUT metadata becomes `PRESERVED_UNSPECIFIED` and remains blocking.

Requirement identity contains full semantic and canonical subject identity. Conflicting collisions fail instead of being silently dropped.

## Error-handler provenance

A normal nested `TryPlanNode` protects its own body.

The canonical flow-level error handler is recognized only when all of these hold:

- the node is the final plan node;
- its body is empty and its handler is non-empty;
- preceding flow nodes exist and are therefore protected;
- the planner-generated identifier matches `onError_<n>`;
- plan capability evidence includes `errorHandlers.finally`.

A manually constructed final empty-body handler is not accepted merely because it resembles the shape. Detached handlers remain unknown and blocking.

## Scheduling provenance

Provider behavior tests do not append a `PlanTrigger` after planning. They inject a `TriggerNode` and `ScheduleNode` into canonical AST and let `FlowPlanner` derive `trigger.schedule.cron`.

The tested path is:

`Flow source → FlowParser → canonical AST trigger → FlowPlanner → explicit target selection → control assessment → manifest generation → readiness reconciliation → provider rendering`

The workload is a real native `git.checkout`, so schedule tests also satisfy planning-effect and renderer-workload integrity instead of using an empty synthetic manifest.

## Production materialization

`CliTargetEvidenceAuthority` evaluates adapter controls before executable target generation.

Successful and diagnostic paths use the same reconciliation method. Every manifest records:

- evidence version;
- control decision;
- requirement count;
- blocker count.

When all requirements match, normal materialization may continue, subject to existing readiness and explicit render authorization.

When any requirement is unsupported or unknown:

- executable generation is not authorized;
- exact compatibility findings are preserved;
- execution readiness becomes `BLOCKED`;
- render mode remains `REVIEW_ONLY` for inspection;
- target syntax is not emitted;
- an explicit render request returns a distinct non-success outcome.

Diagnostic materialization preserves facts. It is not a second executable path.

## Stable diagnostics

`TargetReadinessDiagnosticCodeAuthority` maps known internal readiness statuses to stable catalog codes. Unknown statuses fail closed instead of creating accidental public API identifiers through string concatenation.

## Behavioral and conformance proof

Tests prove positive and negative polarity for:

- approval mode and scope preservation;
- Jenkins inline approval;
- canonical and nested Jenkins protected `try/catch` rendering;
- detached-handler rejection;
- Jenkins and GitHub Actions CRON through the full production boundary;
- planner-derived CRON capability provenance;
- authored timezone rejection;
- retry flattening rejection for Jenkins, GitHub Actions and Tekton;
- incomplete timeout policy remaining unknown;
- profile-only targets remaining unknown;
- separate implementation and behavior evidence;
- symbolic anchor resolution;
- typed evidence shape and path containment;
- stable public diagnostic mapping;
- blocked readiness with review-only evidence and no target artifact;
- lifecycle rejection of premature evidence, skipped progress, missing required files and evidence-free completion.

Adapter inventory `1.3` adds five A0.4 checks after frozen Core closure:

- lifecycle integrity;
- control evidence integrity;
- runtime control authority;
- unsupported-control demotion;
- platform-capability separation.

## Validation boundary

Flow CI #2376, run `30420527005`, passed:

- exact head `a5040767698fed38d6efd0118de40312c77c913e`;
- synthetic merge candidate `8c3b837cb9416acb2de3333a81b1c7eb811d492d`;
- Flow Agent tooling and structure checks;
- offline cache-aware tests and conformance;
- full clean compile/test;
- full adapter and Core conformance.

This is the implementation evidence recorded by the work package. The completion metadata commit is validated separately before the PR is marked ready.

## Non-goals

A0.4 does not:

- add new Core control kinds;
- implement missing provider syntax;
- configure external environments, approver groups or resource checks;
- add a runtime executor;
- add target-specific public syntax;
- claim continuity satisfaction;
- complete general trigger materialization coverage;
- modify the frozen Core pre-closure inventory;
- change package, public standard or artifact contract versions.

Missing provider behavior remains explicit future adapter work rather than being smuggled into A0.4 to preserve a support label.
