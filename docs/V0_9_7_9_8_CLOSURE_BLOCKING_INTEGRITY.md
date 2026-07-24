# v0.9.7.9.8 Closure-Blocking Safety, Governance and Diagnostic Integrity

## Purpose

This correction closes the remaining evidence-boundary defects that make a bounded semantic closure decision unreliable. It does not add execution, an SDK, a plugin system or new target semantics. It makes existing safety, governance, CLI, topology and adapter claims falsifiable.

## Authored control evidence

Authored text is not confirmation merely because it is non-empty.

`AuthoredControlEvidenceTextAuthority` returns one of three states:

| State | Meaning |
|---|---|
| `CONFIRMED` | Explicit positive confirmation or concrete control-specific evidence is present. |
| `DENIED` | The author explicitly states that the evidence is false, absent, disabled or unavailable. |
| `UNKNOWN` | The value is missing, ambiguous, a placeholder or unrelated prose. |

Examples:

| Parameter | Value | State |
|---|---|---|
| `backup` | `s3://recovery/db-before-migration-42` | `CONFIRMED` |
| `rollbackPlan` | `Restore the database snapshot and redeploy the previous image` | `CONFIRMED` |
| `changeTicket` | `OPS-1842` | `CONFIRMED` |
| `retention` | `retain for 30 days` | `CONFIRMED` |
| `backup` | `not available` | `DENIED` |
| `backup` | `unknown` | `UNKNOWN` |
| `rollbackPlan` | `TODO` | `UNKNOWN` |
| `retention` | `n/a` | `UNKNOWN` |

Unknown evidence blocks authorization. It is not silently converted to unsatisfied or satisfied evidence.

## CI/CD bias inventory

The bias inventory is a governance signal, not a grep report.

Kotlin source is classified into lexical contexts:

- `CODE_IDENTIFIER`;
- `CONTROL_LITERAL`;
- `STRING_LITERAL`;
- `CATALOG_DECLARATION`;
- comments, which are ignored.

Non-Kotlin repository files use `STRUCTURED_TEXT` context and retain their path classification.

A finding contributes to semantic health only when all of the following are true:

1. it is in active semantic source;
2. its category is target, infrastructure, tool or data-system coupling;
3. its lexical context is executable code or a control/default literal.

The following remain inventory but are not Core semantic-health failures:

- adapter implementation names;
- CLI and release composition;
- documentation and reports;
- scenario and conformance fixtures;
- module and target declarations;
- ordinary explanatory strings;
- the inventory catalog itself.

This permits an honest `PASS` while retaining a complete repository inventory.

## CLI command model

The application has one public main class:

```text
org.flowlang.cli.honest.HonestFlowCliKt
```

The legacy `org.flowlang.cli.FlowCliKt` main is removed.

### No implicit target

Without `--target`, `intent` and lowered `normalize` commands emit target-neutral planning evidence:

- capability negotiation;
- target selection candidates;
- no target manifest;
- no renderer invocation;
- no rendered target syntax.

Flow does not default to Jenkins or any other provider.

### Diagnostic target outcomes

When a target is selected, the CLI returns one target outcome:

| Outcome | Meaning |
|---|---|
| `EXECUTABLE` | Concrete manifest evidence authorizes rendering. |
| `REVIEW_ONLY` | Diagnostic manifest evidence is valid, but target syntax is not executable. |
| `BLOCKED` | Concrete evidence blocks target materialization or use. |

Expected target incompatibility is converted to diagnostic manifest evidence through the mandatory materialization authority. Structurally invalid or internally inconsistent plans still fail rather than using diagnostic fallback as a bypass.

### Exit statuses

| Status | Meaning |
|---|---|
| `0` | The requested diagnostic or executable action completed. A non-strict review-only inspection may return `0`. |
| `2` | Command input or an integrity boundary is invalid. A structured CLI failure report is printed. |
| `3` | Strict or render-requested target evidence is not executable. Review evidence may still be exported. |

No expected user-facing failure requires a JVM stack trace.

## Canonical topology provenance

ExecutionPlan source metadata preserves:

- authored workflow names and step IDs;
- approval source IDs;
- failure notification and rollback policy.

`ExecutionPlanCanonicalTopologyAuthority` re-derives:

- `WORKFLOW_SCOPE`;
- `WORKFLOW_LIFETIME`;
- `SUSPEND_RESUME` for authored approvals;
- `FAILURE_PROPAGATION` when failure policy requires it.

The materialization validator compares this independent result with the plan's canonical topology requirements. It rejects:

- omitted canonical requirements;
- modified evidence references;
- canonical requirements without source provenance;
- missing structure-derived requirements.

## Retained compatibility projections

Two legacy surfaces remain for serialized compatibility:

- `TaskNode.dependencies` and `ApprovalNode.dependencies` project `dependsOn`;
- node `effects` projects resource names from typed `effectModel`.

They are not independent authorities. A materialization candidate is invalid when the projection differs from its typed source. This is an explicit governance decision replacing the earlier ambiguous requirement to remove every duplicate property immediately.

## GitHub Actions job conditions

Ordinary dependency jobs emit `needs` but no explicit `if`. GitHub therefore retains its native rule that the dependent job runs only after successful dependencies and does not continue after cancellation.

Special cases are explicit:

- error handler: `!cancelled() && failure()`;
- provider-backed approval dependency: `!cancelled()` plus exact `success` or `skipped` result checks.

`always()` is not used as a general dependency wrapper.

## Governance lifecycle

An active correction is represented mechanically:

```text
0.9.7.9 status: correction-required
0.9.7.9.8 work package status: active
0.9.7.10 status: blocked
```

Flow Agent rejects a repository that simultaneously declares an active correction and a Core item with status `next`.

After implementation validation, completion requires a separate metadata transition:

```text
0.9.7.9 status: completed
0.9.7.9.8 work package status: complete
0.9.7.10 status: next
```

Both implementation and final metadata heads require exact-head Flow CI evidence.

## Architecture boundary

This correction introduces no:

- runtime executor;
- command transport;
- SDK lifecycle;
- plugin discovery;
- target-specific public DSL;
- hidden target selection;
- shell projection;
- automatic deployment.
