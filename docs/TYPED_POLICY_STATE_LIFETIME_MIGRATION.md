# SI-08 Typed Policy and State-Lifetime Semantics Migration

## ExecutionPlan 2.3 to 2.4

SI-08 separates two meanings that ExecutionPlan 2.3 collapsed: mutable state propagation within one workflow execution and durable state persistence beyond that local transfer boundary.

ExecutionPlan 2.4 changes every `STATE` dependency relation as follows:

- `stateLifetime` is mandatory and is a closed value: `WORKFLOW` or `DURABLE`;
- `WORKFLOW` requires mutable-state propagation between the related plan nodes but does not imply durable persistence;
- `DURABLE` requires mutable-state propagation and the stronger durable-state topology/evidence contract;
- non-`STATE` dependency relations cannot declare `stateLifetime`;
- relation identity and semantic-equivalence observations include the state lifetime, so changing `WORKFLOW` to `DURABLE` changes canonical observable meaning.

An ExecutionPlan 2.3 artifact cannot be upgraded by inserting a guessed default. The old `STATE` relation did not preserve the authored lifetime distinction, so migration requires regeneration through the production planner from the authoritative module continuity contract.

## Module schema 1.2 to 1.3

State continuity channels may now declare:

```yaml
continuity:
  requires:
    - kind: state
      name: session
      lifetime: durable
```

The closed authored values are `workflow` and `durable`.

For module descriptors, omission of `lifetime` on a `state` channel means `workflow`. This preserves existing descriptor compatibility while correcting the previous implementation assumption that every state channel required durable persistence. `value` and `workspace` channels cannot declare a state lifetime.

Provider and preserver compatibility is directional:

- a durable state provider may satisfy a workflow-local requirement;
- a workflow-local provider cannot satisfy a durable requirement;
- the ExecutionPlan relation still records the exact lifetime required by the consumer, not the stronger lifetime a provider happens to offer.

This prevents implementation strength from rewriting canonical authored meaning.

## Policy condition behavior

`IntentPolicy.condition` remains a string in Intent 2.0. SI-08 changes the deterministic semantic interpretation, not the serialized field shape.

Closed standard safety requirements continue to use their existing normalized names. Retention meaning is recognized only from explicit standard forms:

- `retention:<value>`;
- `ttl:<value>`;
- `olderThan:<value>`.

The value is preserved as authored text. SI-08 does not invent a new duration standard where the public contract has not defined one.

Unrelated predicates and arbitrary custom conditions remain non-authoritative custom policy meaning. Examples such as `environment != prod`, `onlyIf maintenanceWindow`, or prose that merely contains the word `retention` cannot satisfy a `RETENTION_GUARD`.

A recognized retention prefix with a missing or malformed value fails closed as a clarification requirement instead of falling back to custom/dynamic policy meaning.

## Control authorization impact

Before SI-08, substring classification could turn unrelated text into `RetentionRule`. `CanonicalControlRequirementAuthority` could then use that classification as `SATISFIED / AUTHORED_POLICY` evidence for a cleanup retention guard.

SI-08 removes that authorization path. Cleanup remains blocked unless explicit retention or other valid scoped safety evidence satisfies the canonical requirement.

No new public control kind is introduced. Malformed recognized standard policy forms reuse the existing blocking clarification semantics.

## Topology and adapter evidence impact

For workflow-local state continuity:

- `STATE_PROPAGATION` is required;
- `DURABLE_STATE` is not inferred.

For explicit durable state continuity:

- `STATE_PROPAGATION` is required;
- `DURABLE_STATE` is also required;
- unsupported, unknown or contradictory target evidence remains blocking.

Adapter support is not promoted by this migration. Existing target evidence remains authoritative; the correction only stops requiring durable persistence when the canonical lifetime does not ask for it.

## Version boundary

SI-08 changes the live public contract boundary as follows:

| Contract | Before | After |
|---|---:|---:|
| Implementation package | 0.9.5 | 0.9.5 |
| Public standard | 0.8.0 | 0.8.0 |
| Intent | 2.0 | 2.0 |
| AST | 2.2 | 2.2 |
| ExecutionPlan | 2.3 | 2.4 |
| ExecutionPlan lowering evidence | 2.1 | 2.1 |
| TargetManifest | 3.0 | 3.0 |
| TargetRegistry | 3.2 | 3.2 |
| Module schema | 1.2 | 1.3 |

## Required validation

Completion requires evidence that:

1. environment predicates and incidental text cannot become retention evidence;
2. explicit retention conditions preserve positive control polarity;
3. malformed recognized retention forms fail closed;
4. workflow-local state produces propagation without durable persistence requirements;
5. explicit durable state requires both propagation and durable evidence;
6. a workflow-local provider cannot satisfy a durable requirement;
7. a stronger durable provider does not promote the canonical workflow-local requirement;
8. materialization rejects a forged lifetime that differs from the module requirement;
9. state lifetime changes semantic-equivalence observations;
10. exact-head and synthetic merge-candidate compile, tests, standalone conformance and Flow Agent validation pass.
