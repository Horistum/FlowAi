# SI-01 AST and ExecutionPlan 2.0 to 2.1 Control Scope Migration

## Why the contracts change

AST 2.0 and ExecutionPlan 2.0 exposed control requirements without the identity of the operation they protected. That shape was insufficient for fail-closed authorization because two operations with the same capability could collapse into one apparent obligation, and evidence from an unrelated operation could be mistaken for evidence for the protected operation.

SI-01 makes control scope explicit. This is a target-neutral semantic change, not a provider feature and not a new Intent YAML field.

## Version boundary

The two serialized contracts that actually carry `ControlRequirement` advance from **2.0 to 2.1**.

- Intent remains 2.0 because its authored YAML shape does not change.
- AST becomes 2.1 because canonical control requirements are part of the AST contract.
- ExecutionPlan becomes 2.1 because those requirements continue through planning and module-owned requirements add plan-node scope.
- The nested ExecutionPlan lowering-evidence contract becomes 2.1 because workflow-to-step membership is now independently certified and is security-relevant to operation scope.
- TargetManifest remains 3.0.
- TargetRegistry remains 3.1.
- The implementation package and public standard versions do not move for this bounded contract migration.

This follows the independent artifact-version policy: only serialized contracts whose public shape or interpretation changes advance.

## New control requirement field

Every serialized `controlRequirement` in AST 2.1 and ExecutionPlan 2.1 includes `scope`.

The scope kinds are:

- `INTENT`: the requirement applies to the authored intent as a whole and carries no workflow or subject identifier.
- `OPERATION`: the requirement protects one authored operation and carries both the workflow name and authored step identifier.
- `PLAN_NODE`: the requirement is introduced during planning for one concrete plan node and carries the node identifier but no authored workflow name.

Scope is not ordering. For operation preconditions such as backup or approval, the authored dependency graph separately proves whether a control step is an ancestor of the protected operation. Source list order never supplies that proof.

## Workflow membership integrity

Operation scope contains both an authored workflow name and step identifier. Materialization therefore must not trust `sourceIntent.workflows[].stepIds` merely because the same serialized plan also carries that metadata.

Lowering evidence 2.1 adds one preserved field for every authored workflow-step relationship. Its semantic identity is `workflow/<workflow>/step/<step>` and its target identity resolves back through the preserved workflow membership in the concrete ExecutionPlan. Removing a step from its workflow, moving it to another workflow, or fabricating membership therefore invalidates artifact-derived lowering evidence before the scope can be trusted for materialization.

This relationship is deliberately separate from step identity and dependency evidence. A step can retain the same source id and capability while its workflow membership is corrupted; SI-01 requires that corruption to fail closed.

## Compatibility impact

A consumer that only understands AST 2.0 or ExecutionPlan 2.0 must not silently accept the corresponding 2.1 artifact while discarding `scope`. Doing so would erase security-relevant meaning. Consumers must either understand and preserve the 2.1 scope contract or reject the artifact as an unsupported contract version.

Likewise, an ExecutionPlan consumer that validates lowering evidence must require lowering-evidence contract 2.1 for SI-01 plans. Treating a 2.0 lowering report as equivalent would omit the workflow-step membership proof needed to authenticate operation scope.

Requirement IDs remain readable when unambiguous, but scope is part of collision-safe semantic identity. Two otherwise identical obligations protecting different operations therefore remain distinct.

## Behavioral correction

The 2.1 contracts record the production behavior introduced by SI-01:

- unrelated backups do not authorize database migrations;
- backups ordered after a migration do not authorize it;
- controls do not cross workflow boundaries;
- evidence for one migration cannot authorize another migration;
- optional input declarations are not control evidence;
- notification is not external-effect review;
- operation-specific approvals must be actual authored ancestors of the protected operation;
- module-owned planning controls are scoped to their concrete plan node;
- materialization re-derives canonical requirements from preserved authored workflow, step and policy identity instead of a capability bag;
- workflow-step membership used by that re-derivation is independently covered by lowering evidence 2.1.

Intent-level policy controls remain intent-level. They are not reinterpreted as operation-specific authorization.

## Migration rule

When reading AST or ExecutionPlan:

1. require `astVersion == "2.1"` or `planVersion == "2.1"` for the new contract;
2. require every control requirement to contain a structurally valid `scope`;
3. for intent-derived ExecutionPlans, require lowering evidence 2.1 and verify workflow-step membership before trusting `OPERATION` scope;
4. preserve scope through AST serialization, planning, review evidence and any later materialization boundary;
5. never synthesize an operation scope from source order, capability name, provider vocabulary or an unrelated evidence record.

The committed reference snapshots and JSON Schemas are part of the migration evidence and must match the production generators exactly.
