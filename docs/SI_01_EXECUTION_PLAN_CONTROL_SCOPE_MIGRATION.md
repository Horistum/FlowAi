# SI-01 ExecutionPlan 2.0 to 2.1 Control Scope Migration

## Why the contract changes

ExecutionPlan 2.0 exposed control requirements without the identity of the operation they protected. That shape was insufficient for fail-closed authorization because two operations with the same capability could collapse into one apparent obligation, and evidence from an unrelated operation could be mistaken for evidence for the protected operation.

SI-01 makes control scope explicit. This is a target-neutral semantic change, not a provider feature and not a new Intent YAML field.

## Version boundary

Only the ExecutionPlan serialized contract advances from **2.0 to 2.1**.

- Intent remains 2.0.
- AST remains 2.0.
- ExecutionPlan becomes 2.1.
- TargetManifest remains 3.0.
- TargetRegistry remains 3.1.
- The implementation package and public standard versions do not move for this contract-only migration.

This follows the independent artifact-version policy: one serialized contract may advance without pretending that every public contract changed with it.

## New control requirement field

Every serialized `controlRequirement` now includes `scope`.

The scope kinds are:

- `INTENT`: the requirement applies to the authored intent as a whole and carries no workflow or subject identifier.
- `OPERATION`: the requirement protects one authored operation and carries both the workflow name and authored step identifier.
- `PLAN_NODE`: the requirement is introduced during planning for one concrete plan node and carries the node identifier but no authored workflow name.

Scope is not ordering. For operation preconditions such as backup or approval, the authored dependency graph separately proves whether a control step is an ancestor of the protected operation. Source list order never supplies that proof.

## Compatibility impact

A consumer that only understands ExecutionPlan 2.0 must not silently accept a 2.1 plan while discarding `scope`. Doing so would erase security-relevant meaning. Consumers must either understand and preserve the 2.1 scope contract or reject the plan as an unsupported contract version.

Requirement IDs remain readable when unambiguous, but scope is part of collision-safe semantic identity. Two otherwise identical obligations protecting different operations therefore remain distinct.

## Behavioral correction

The 2.1 contract records the production behavior introduced by SI-01:

- unrelated backups do not authorize database migrations;
- backups ordered after a migration do not authorize it;
- controls do not cross workflow boundaries;
- evidence for one migration cannot authorize another migration;
- optional input declarations are not control evidence;
- notification is not external-effect review;
- operation-specific approvals must be actual authored ancestors of the protected operation;
- module-owned planning controls are scoped to their concrete plan node.

Intent-level policy controls remain intent-level. They are not reinterpreted as operation-specific authorization.

## Migration rule

When reading an ExecutionPlan:

1. require `planVersion == "2.1"` for the new contract;
2. require every control requirement to contain a structurally valid `scope`;
3. preserve the scope during serialization, review evidence and any later materialization boundary;
4. never synthesize an operation scope from source order, capability name, provider vocabulary or an unrelated evidence record.

The committed reference snapshots and JSON Schema are part of the migration evidence and must match the production generator exactly.
