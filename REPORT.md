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
Next adapter roadmap item: `A0.4 Control Requirement Materialization` (`next`)

## Core boundary

PR #95 merged the final bounded Core closure correction as `e25a81b9c7e7802556a0d5b34cf34185b19ed498`. Core v0.9.7 remains CLOSED. Its exact 91-check pre-closure inventory remains frozen. Adapter checks continue only after `v0.9.7.10.bounded-semantic-closure`.

The implementation package remains `0.9.5`, the public standard remains `0.8.0`, and the artifact contract remains `2.0`.

## Completed adapter baseline

PR #96 merged A0.1 as `dbd1529cd9da1b21d13bd45d3af1a84361b9abf1`. PR #97 merged A0.2 as `964a9c4f8bf9ce9dc8771c99a68393c9edc35807`.

The adapter portfolio classes remain:

| Target | Role | Support class |
| --- | --- | --- |
| `local` | semantic reference | `PROFILE_ONLY` |
| `jenkins` | target adapter | `EXECUTABLE_REFERENCE` |
| `github-actions` | target adapter | `NATIVE_LEAF_ONLY` |
| `tekton` | target adapter | `NATIVE_LEAF_ONLY` |
| `argo-workflows` | target adapter | `PROFILE_ONLY` |
| `azure-devops` | target adapter | `PROFILE_ONLY` |

A0.2 provides strict adapter-owned topology evidence and leaves profile-only target topology claims unknown. A0.3 consumes the closed Core meaning/effect contracts and does not promote target support.

## A0.3 defect boundary

Before A0.3, explicit binding validation and AST lowering both interpreted the module action contract independently.

The validation boundary checked the requested action, selected system and supplied parameters. `IntentToAstPlanner` then looked up the action again, filtered parameters again and reapplied descriptor defaults. These two decision paths could diverge while still appearing to represent one explicit binding.

Built-in `implements` declarations also had no independent distribution-owned evidence describing:

- which canonical parameters an action represents;
- which canonical parameters it cannot represent;
- which inputs are binding-only;
- whether canonical semantic effects remain authoritative;
- whether an implementation claim is concrete or only a semantic fallback.

## Single runtime binding contract

`IntentBindingContractAuthority` derives one target-neutral contract for an explicitly selected capability/module/action combination.

It partitions canonical parameters into mapped and unsupported semantics and identifies binding-only action inputs. `CanonicalIntentMeaningAuthority` resolves authored values and descriptor defaults once and records each value source as `SEMANTIC`, `BINDING` or `DEFAULT`.

`IntentToAstPlanner` consumes `IntentBindingEvidence.resolvedParameters`. It no longer re-reads the action descriptor or reapplies defaults. Selection remains explicit through `uses` and `params.system`; no registry order or apparent compatibility chooses an implementation.

Canonical semantic effects are derived before binding. The only accepted binding effect policy is `PRESERVE_CANONICAL`.

## Adapter binding certification

`adapters/bindings/builtin-capability-bindings.yaml` is the strict distribution evidence authority for built-in implementation claims.

Every record contains exact capability, module, action, binding class, system types, semantic parameter partition, binding-only parameters, effect policy, repository evidence and limitations.

`AdapterCapabilityBindingAuthority` requires exact agreement between:

- module `implements` declarations;
- canonical capability parameter contracts;
- derived runtime binding contracts;
- adapter-owned evidence records.

Missing, duplicate, unknown, unresolved, self-referential and incomplete evidence fails closed. Universally required canonical parameters cannot be declared unsupported.

## Honest implementation classifications

Concrete implementation records currently cover:

- `git.checkout#CHECKOUT`;
- `docker.build#BUILD_IMAGE`;
- `docker.push#PUSH_IMAGE`;
- `argocd.sync#DEPLOY`;
- `helm.upgrade#DEPLOY`;
- `kubernetes.deploy#DEPLOY`;
- `notify.send#NOTIFY`;
- `notify.email#NOTIFY`;
- `rest.call#CALL_API`.

`standard.rollback#ROLLBACK` is retained as `SEMANTIC_FALLBACK`; it does not certify native target rollback materialization.

A0.3 intentionally removes `argocd.sync → SYNC`. Canonical SYNC requires source and destination, while the action represents an Argo CD application plus sync options. The old claim could not preserve required meaning and is demoted instead of being approximated.

## Behavior and conformance

A0.3 tests prove:

- built-in evidence exactly matches implementation claims;
- descriptor defaults are resolved once before AST lowering;
- unsupported canonical parameters fail before AST creation;
- the removed Argo CD SYNC claim cannot bind;
- canonical meaning and effects survive explicit bindings;
- source step, module, action and selected system provenance survive into ExecutionPlan;
- missing `uses` remains unbound semantic work;
- lifecycle completion requires independent implementation evidence and adjacent roadmap progress.

Adapter inventory `1.2` adds five A0.3 checks after the frozen Core closure:

- lifecycle integrity;
- binding evidence integrity;
- runtime binding authority;
- semantic effect and provenance preservation;
- unresolved and unsupported polarity.

The existing `AdapterStreamConformanceRunner` remains the single composition authority for A0.1, A0.2 and A0.3. A duplicate complete-stream runner found during final review was removed before the authoritative implementation boundary.

## Validation history

Flow CI #2293 correctly rejected the first implementation because two binding-key sort expressions were not type-safe and nullable action/binding inputs did not smart-cast across the computed validity boundary.

Flow CI #2298 passed compilation and most binding tests, then rejected two remaining issues:

- a new test compared typed target provenance with the quoted legacy presentation string in `bindingMetadata`;
- release metadata did not use the exact external-candidate evidence wording required by release honesty policy.

The test was corrected to compare `ExecutionPlan` selection fields against typed `IntentBindingEvidence`, and release metadata was corrected without weakening the policy.

Flow CI #2300 passed exact-head and merge-candidate tests and conformance before final review found two competing classes claiming to compose the complete adapter stream. The new duplicate runner was removed and the existing canonical `AdapterStreamConformanceRunner` was extended to own inventory `1.2`.

Flow CI #2303, run `30354834937`, passed the final implementation on:

- exact head `f475ae8317122b986dec23a3d36bb8df05a31db3`;
- synthetic merge candidate `afdfa505e96ba00eecf42a94d9b2e1ebe7059ec8`;
- Flow Agent tooling, structure validation and context generation;
- complete compilation and tests;
- frozen Core closure followed by adapter inventory `1.2`;
- all A0.1, A0.2 and A0.3 checks.

Flow CI #2310 correctly rejected the first completion head because the repository integration test asserted that the live metadata must remain in the `IMPLEMENTING` phase. Separate tests already proved the exact IMPLEMENTING and COMPLETED contracts, missing and premature evidence, adjacent later progress and skipped-item rejection. The repository test was corrected to require one valid supported phase rather than permanently freezing the item before completion.

A0.3 remains `completed` and A0.4 remains `next`. The corrected completion-metadata head and its synthetic merge candidate must independently pass before PR #98 becomes ready for review. No A0.4 work package or implementation belongs to this PR.

## Architecture boundary

A0.3 did not add a renderer, provider, runtime executor, target-specific public DSL, automatic implementation selection, new canonical capability or new Core conformance check. It changed the explicit binding boundary and independent adapter certification while preserving the frozen Core model.
