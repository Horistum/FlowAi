# SI-04 ExecutionPlan 2.1 to 2.2 Migration

## Purpose

SI-04 removes implementation-label heuristics from the public canonical `CanonicalPlanNode.kind` contract. The JSON object shape is unchanged, but the meaning and derivation of task `kind` values changes. That is an interpretation-level public contract change, so the ExecutionPlan artifact advances from `2.1` to `2.2`.

The implementation package remains `0.9.5`, the public standard remains `0.8.0`, Intent remains `2.0`, AST remains `2.1`, and ExecutionPlan lowering evidence remains `2.1`. Those version axes do not change because SI-04 does not alter their serialized or proof contracts.

## Contract change

ExecutionPlan 2.1 classified task nodes using implementation-facing strings:

- `module == "standard" && action == "rollback"` produced `rollback`;
- `module == "notify"` produced `notification`;
- a required capability whose string started with `secret.` produced `secret`;
- any semantic-effect resource containing the substring `artifact` produced `artifact`.

Those rules allowed implementation labels and free-form resource text to define target-neutral public semantics.

ExecutionPlan 2.2 derives task specialization only from the retained canonical `StandardCapability` identity:

| Canonical capability | Public node kind |
| --- | --- |
| `ROLLBACK` | `rollback` |
| `NOTIFY` | `notification` |
| `PACKAGE` | `artifact` |
| `SECRET_ROTATE` | `secret` |
| all other standard capabilities, missing capability, unknown capability | `task` |

Structural node kinds continue to derive from typed planner node structure. Legacy combined data/control planner nodes are mapped through a closed allow-list and fail on unknown internal kinds rather than publishing arbitrary lowercased strings.

ExecutionPlan 2.2 also removes the legacy uppercase/internal node-name aliases from the JSON Schema. The schema `kind` enum now equals the closed lowercase `CanonicalPlanNodeKind` wire vocabulary exactly.

## Compatibility consequence

Consumers that treated `kind` as an implementation-label echo must migrate. In particular:

- renaming a module or action no longer changes canonical `kind`;
- declaring an adapter capability such as `secret.read` no longer turns an unrelated task into a `secret` semantic operation;
- putting the word `artifact` in an effect resource no longer turns an unrelated task into an `artifact` operation;
- explicit canonical `NOTIFY`, `ROLLBACK`, `PACKAGE`, and `SECRET_ROTATE` meaning produces stable specialized kinds regardless of binding labels.

Consumers should use `kind` only as the canonical semantic classification. `module`, `action`, `target`, binding metadata and effect details remain separately available when implementation evidence is required.

## Migration evidence

SI-04 requires all of the following before completion:

1. typed classification tests covering the four specialized canonical capabilities;
2. negative invariance tests proving module/action/target/adapter-capability/effect-resource changes cannot change `kind`;
3. exact schema version `2.2`;
4. regenerated committed reference snapshots through `ReferenceSnapshotBundleGenerator`;
5. full compile, full tests and standalone conformance over the exact candidate tree;
6. independent GitHub exact-head and synthetic merge-candidate validation before lifecycle completion.
