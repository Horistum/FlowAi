# A0.7 Trigger Materialization Coverage

## Status

Implementation work package for the final declared adapter roadmap item.

A0.7 consumes the existing trigger contracts without changing Core meaning:

`IntentTrigger -> TriggerNode -> PlanTrigger -> TargetTrigger -> adapter trigger assessment -> target artifact`

The adapter layer may prove, reject or leave unknown a concrete materialization mechanism. It may not change trigger type, timing, scope, event identity or delivery semantics.

## Closed trigger families

A0.7 certifies six families:

| Family | Canonical semantic |
|---|---|
| MANUAL | `manual.explicit` |
| CRON | `schedule.cron` |
| INTERVAL | `schedule.interval` |
| CALENDAR | `schedule.calendar` |
| EVENT | `event.named` |
| WEBHOOK | `webhook.external` |

Every active target declares exactly one claim for every family. Each claim partitions the single family semantic into `SUPPORTED`, `UNSUPPORTED` or `UNKNOWN` evidence.

## Evidence boundary

`adapters/triggers/builtin-trigger-materialization.yaml` is a strict index over `adapters/triggers/targets`.

The loader fails before accepting claims when:

1. an indexed file is missing;
2. a YAML target file is present but not indexed;
3. two index entries resolve to the same canonical file;
4. an entry escapes or nests below the dedicated target directory;
5. a file name is not lower-case kebab-case YAML;
6. the file name and declared `target` identity differ;
7. an index, target, claim, semantic or constraint object has unknown or missing fields.

A supported claim requires all of the following:

1. a composed target projection provider;
2. a concrete target-adapter portfolio record;
3. repository production implementation evidence;
4. an independent repository behavior test;
5. explicit workflow-scope constraints;
6. exact expression, timezone, event-name and parameter constraints.

Target registry feature flags and platform documentation are compatibility context. They are not sufficient implementation evidence.

## Runtime matching

`AdapterTriggerRequirementAuthority` derives collision-safe requirements directly from preserved `PlanTrigger` values. It retains:

- trigger type;
- schedule kind;
- expression;
- timezone;
- workflow scopes;
- event identity;
- authored parameters.

Semantic duplicates are rejected rather than assigned arbitrary suffixes.

`AdapterTriggerMaterializationAuthority` matches those requirements against the selected adapter evidence. A requirement is satisfied only when every declared constraint is preserved.

Unsupported or unresolved requirements produce stable compatibility issues and mapping notes. They also set:

- `adapterTriggerDecision=BLOCKED`;
- exact requirement and blocker counts;
- the observed trigger-family inventory.

Matched requirements set the same metadata with `adapterTriggerDecision=MATCHED` and zero blockers.

## Rendering boundary

`AdapterTriggerAuthorizedRenderingAuthority` decorates the single A0.6 artifact rendering authority.

It does not render content and does not choose filenames. It verifies the A0.7 assessment and delegates the unchanged manifest to A0.6.

- `MATCHED` trigger evidence may produce executable or review output depending on all other adapter evidence.
- `BLOCKED` trigger evidence may produce dedicated review evidence only.
- Missing or inconsistent A0.7 metadata fails closed.
- A blocked trigger can never produce provider target syntax.

`GitHubActionsTriggerProjectionPlanner` is the production authority for the GitHub Actions `on` mapping. The complete renderer consumes it after executable readiness succeeds. Conformance may also inspect the same leaf projection on a diagnostic manifest without relabeling the entire workflow as executable.

This preserves diagnostic evidence without weakening executable authorization.

## Current honest support

### Jenkins

Supported:

- explicit manual-only invocation;
- portable five-field POSIX CRON without an explicit timezone.

Unsupported:

- interval;
- calendar;
- named event;
- generic webhook;
- explicit timezone on the current CRON projection;
- Jenkins-specific `H` hashing syntax as portable Flow CRON.

Jenkins exposes additional platform trigger mechanisms such as `pollSCM` and `upstream`, but the current Flow event and webhook contracts do not identify those mechanisms precisely enough for faithful materialization.

Official context: <https://www.jenkins.io/doc/book/pipeline/syntax/#triggers>

### GitHub Actions

Supported:

- `workflow_dispatch` for explicit manual triggers;
- portable five-field POSIX CRON;
- optional IANA timezone on a CRON schedule;
- native leaf projection for the closed no-parameter event vocabulary `push`, `pull_request`, `release`.

Unsupported:

- interval and calendar approximation;
- generic webhook materialization;
- event filters, activity types, path filters, branch filters or payload contracts that are not represented by typed Flow fields.

The bounded event claim proves exact production trigger-planner syntax only. It does not override separate capability, topology, control, continuity or scenario-executability evidence. Provider behavior fixtures are planned through the production `FlowParser` and `FlowPlanner`; no copied compatibility report is used to fabricate executable readiness.

Official context:

- <https://docs.github.com/actions/using-workflows/events-that-trigger-workflows>
- <https://docs.github.com/actions/using-workflows/workflow-syntax-for-github-actions#onschedule>

### Tekton

All six trigger families are unsupported by the current provider artifact.

The provider emits a Tekton `Pipeline`. Tekton Triggers uses separate resources and controllers including `EventListener`, `Trigger`, `TriggerTemplate`, `TriggerBinding` and interceptors. A Pipeline definition is not trigger-delivery evidence.

Official context:

- <https://tekton.dev/docs/triggers/>
- <https://tekton.dev/docs/triggers/triggertemplates/>

### Profile-only and semantic-reference targets

`local`, `argo-workflows` and `azure-devops` remain `UNKNOWN` until a composed provider supplies repository implementation and behavioral evidence.

## No-approximation rules

A0.7 rejects the following substitutions:

- interval duration to CRON;
- calendar recurrence to CRON;
- generic webhook to GitHub `repository_dispatch`;
- generic event to Jenkins polling, upstream or plugin-specific trigger;
- Tekton Pipeline to Tekton Triggers resources;
- target-specific CRON extension to portable Flow CRON.

These substitutions may appear operationally convenient, but they change alignment, catch-up, timezone, payload, authentication, replay or delivery behavior.

## Conformance

Adapter conformance inventory `1.6` adds:

- `adapters.a0.7.lifecycle-integrity`;
- `adapters.a0.7.trigger-evidence-integrity`;
- `adapters.a0.7.runtime-trigger-authority`;
- `adapters.a0.7.no-trigger-approximation`;
- `adapters.a0.7.trigger-review-evidence`;
- `adapters.a0.7.executable-trigger-proof`;
- `adapters.a0.7.bounded-event-projection`.

The frozen Core pre-closure inventory remains unchanged.

## Lifecycle

During implementation:

- A0.6 is completed;
- A0.7 is next;
- implementation evidence is absent.

After a distinct exact-head and synthetic merge-candidate implementation boundary passes:

- A0.7 may become completed;
- the adapter roadmap becomes completed;
- no fabricated A0.8 item is selected;
- completion metadata must pass a second distinct validation boundary before merge readiness.
