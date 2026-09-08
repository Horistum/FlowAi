# SI-05 Operational Effect Model Re-evaluation

## AST 2.1 to 2.2 and ExecutionPlan 2.2 to 2.3

SI-05 changes the public semantic-effect contract for recovery operations. The authored Intent contract remains `2.0`: `BACKUP` already owns `subject`, optional `destination` and optional `retention`, while `RESTORE` already owns `subject` and optional `recoveryPoint`. The defect was not missing authoring syntax. It was semantic information loss after canonicalization.

The AST contract advances from `2.1` to `2.2` and the ExecutionPlan contract advances from `2.2` to `2.3` because both artifacts serialize `SemanticEffect`. Execution-plan lowering evidence remains `2.1`: its preservation/provenance structure is unchanged and already carries authored recovery parameters to their canonical parameter targets.

## Recovery effect model

`BACKUP` and `RESTORE` no longer impersonate generic `DATA_TRANSFORMATION` effects. Operational DP01/DP02 evidence forces a distinct target-neutral `STATE_RECOVERY` domain.

The generic effect operation remains a state-transition verb:

- `BACKUP` reads protected state and creates a recovery point (`ABSENT -> PRESENT`).
- `RESTORE` reads a recovery point and upserts protected state (`UNKNOWN -> PRESENT`).

The model deliberately does **not** add `BACKUP` or `RESTORE` to `EffectOperation`. Those names would restate the capability while leaving the missing relationship semantics unresolved.

Recovery-specific meaning is carried by an optional typed `recovery` facet on `SemanticEffect`:

```json
{
  "domain": "STATE_RECOVERY",
  "operation": "CREATE",
  "resource": "recovery.point",
  "transition": { "from": "ABSENT", "to": "PRESENT" },
  "external": true,
  "sourceCapability": "BACKUP",
  "recovery": {
    "kind": "RECOVERY_POINT_CAPTURE",
    "source": {
      "kind": "PROTECTED_STATE",
      "identity": "application-state"
    },
    "target": {
      "kind": "BACKUP_DESTINATION",
      "identity": "durable-backup-store"
    },
    "retention": "30d"
  }
}
```

For restore, `kind` is `STATE_RESTORE`, the optional source endpoint is a `RECOVERY_POINT`, and the required target endpoint is the authored `PROTECTED_STATE`.

## Semantic ownership decisions

SI-05 makes the following evidence-driven decisions instead of filling missing knowledge with attractive fiction:

| Recovery property | Ownership decision |
| --- | --- |
| Protected source/target identity | Typed recovery endpoint on `SemanticEffect`. |
| Backup destination | Typed `BACKUP_DESTINATION` endpoint when authored. |
| Recovery-point identity | Typed `RECOVERY_POINT` endpoint when explicitly authored by `RESTORE`. Current `BACKUP` syntax does not name the recovery point it creates, so no identity is invented. |
| Retention/lifetime | Narrow `retention` facet on recovery-point capture when authored. This does not redefine general durable-state lifetime semantics. |
| Consistency boundary | Not currently authorable; therefore not fabricated by the effect model. A future authored contract must introduce it explicitly if evidence requires it. |
| Recoverability | Remains evidence/conformance, not a guarantee implied by a `BACKUP` effect. Creating a recovery point does not prove that a restore was tested or is viable. |
| Create versus replace on restore | Remains unresolved. `RESTORE` is `UPSERT UNKNOWN -> PRESENT` until authored semantics distinguish creation from replacement. |

## Semantic equivalence and materialization

`SemanticObservationAuthority` consumes the complete canonical effect identity. Recovery endpoint identities and retention therefore participate in semantic-equivalence observations: changing an authored destination, retention or recovery point changes observable canonical meaning.

Implementation metadata remains irrelevant to that meaning. Renaming a module, action or target cannot change the recovery facet.

Materialization re-derives canonical effects from the canonical capability and preserved authored parameters. A plan that forges or drops a recovery relationship therefore fails the existing `planning.effect.evidence.invalid` boundary rather than reaching a provider with weaker semantics.

## Compatibility

Non-recovery effects retain their previous canonical observation representation byte-for-byte. The new wire vocabulary is present only when recovery semantics exist.

Consumers of AST `2.2` or ExecutionPlan `2.3` must accept:

- the `STATE_RECOVERY` effect domain;
- optional `SemanticEffect.recovery`;
- recovery kinds `RECOVERY_POINT_CAPTURE` and `STATE_RESTORE`;
- recovery endpoint kinds `PROTECTED_STATE`, `BACKUP_DESTINATION` and `RECOVERY_POINT`.

Consumers that only understand AST `2.1` or ExecutionPlan `2.2` must not claim to understand the richer recovery semantics merely because the surrounding JSON object can still be parsed.
