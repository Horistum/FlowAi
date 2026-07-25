# v0.9.7.9.8 Closure-Blocking Safety, Governance and Diagnostic Integrity

## Purpose

This correction closes the remaining evidence-boundary defects that made a bounded semantic closure decision unreliable. It does not add execution, an SDK, a plugin system or new target semantics. It makes existing safety, governance, CLI, topology, adapter and CI identity claims falsifiable.

## Authored control evidence

Authored text is not confirmation merely because it is non-empty.

`AuthoredControlEvidenceTextAuthority` returns one of three states:

| State | Meaning |
|---|---|
| `CONFIRMED` | A bounded explicit confirmation or concrete control-specific evidence is present. |
| `DENIED` | The author explicitly states that the evidence is false, absent, disabled or unavailable. |
| `UNKNOWN` | The value is missing, ambiguous, a placeholder, generic status prose or unrelated text. |

Examples:

| Parameter | Value | State |
|---|---|---|
| `backup` | `s3://recovery/db-before-migration-42` | `CONFIRMED` |
| `backup` | `backup-db-before-migration-42` | `CONFIRMED` |
| `rollbackPlan` | `Restore the database snapshot and redeploy the previous image` | `CONFIRMED` |
| `changeTicket` | `OPS-1842` | `CONFIRMED` |
| `retention` | `retain for 30 days` | `CONFIRMED` |
| `backup` | `not available` | `DENIED` |
| `backup` | `backup unavailable` | `DENIED` |
| `backup` | `backup later` | `UNKNOWN` |
| `backup` | `backup something` | `UNKNOWN` |
| `safety` | `approval pending` | `UNKNOWN` |
| `backup` | `approved` | `UNKNOWN` |

Denial and unresolved markers are recognized inside longer text. Generic words such as `available`, `complete`, `approved`, `required` and `policy` do not satisfy a control by themselves. Unknown evidence blocks authorization.

## CI/CD bias inventory

The bias inventory is a governance signal, not a grep report.

Kotlin source is classified into lexical contexts:

- `CODE_IDENTIFIER`;
- `CONTROL_LITERAL`;
- `STRING_LITERAL`;
- `CATALOG_DECLARATION`;
- `COMPATIBILITY_SYMBOL`;
- comments, which are ignored.

Non-Kotlin repository files use `STRUCTURED_TEXT` context and retain their path classification.

A finding contributes to semantic health only when all of the following are true:

1. it is in active semantic source;
2. its category is target, infrastructure, tool or data-system coupling;
3. its lexical context is executable code or a control/default literal.

Adapter implementation names, CLI and release composition, documentation, scenarios, conformance fixtures, module declarations, ordinary strings and the inventory catalog remain visible inventory without manufacturing a Core semantic-health failure.

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

Expected target incompatibility becomes diagnostic manifest evidence through the mandatory materialization authority. Structurally invalid or internally inconsistent plans still fail rather than using diagnostic fallback as a bypass.

### Exit statuses

| Status | Meaning |
|---|---|
| `0` | The requested diagnostic or executable action completed. A non-strict review-only inspection may return `0`. |
| `2` | Command input or an integrity boundary is invalid. A structured CLI failure report is printed. |
| `3` | Strict or render-requested target evidence is not executable. Review evidence may still be exported. |

No expected user-facing failure requires a JVM stack trace.

## Canonical topology provenance

ExecutionPlan source metadata preserves authored workflow names, step IDs, approval source IDs and failure policy. `ExecutionPlanCanonicalTopologyAuthority` re-derives canonical topology only from that independent `sourceIntent` provenance.

Canonical requirements already stored on the plan are never reused as expected values for their own validation. The materialization validator rejects:

- omitted canonical requirements;
- modified evidence references;
- orphaned canonical requirements;
- canonical requirements without source provenance;
- intent-derived source signals without source provenance;
- missing structure-derived requirements.

Removing both `sourceIntent` and retained canonical claims therefore cannot hide an intent-derived approval or workflow topology requirement.

## Retained compatibility projections

Two legacy surfaces remain for serialized compatibility:

- `TaskNode.dependencies` and `ApprovalNode.dependencies` project `dependsOn`;
- node `effects` projects resource names from typed `effectModel`.

They are not independent authorities. A materialization candidate is invalid when the projection differs from its typed source.

## GitHub Actions job conditions

Ordinary dependency jobs emit `needs` but no explicit `if`. GitHub retains its native success and cancellation rule.

Special cases are explicit:

- error handler: `!cancelled() && failure()`;
- provider-backed approval dependency: `!cancelled()` plus exact `success` or `skipped` result checks.

`always()` is not used as a general dependency wrapper.

## Validation identity

Flow CI separates two claims:

1. `compile-test-conformance` checks out `github.event.pull_request.head.sha`, compares it with `git rev-parse HEAD`, and validates the exact implementation revision.
2. `merge-candidate-compile-test-conformance` checks out GitHub's synthetic pull-request merge ref, verifies its SHA, and validates the integration candidate.

Flow CI #2025, run `30144935197`, passed exact implementation head `af2062a687e4fbf0a5ec4d3e50d44b8ba07e9319` and merge candidate `2e30830d55fe73ec0c46c75dfd425c1047dc9853` independently.

## Governance lifecycle

The implementation validation authorized the separate transition:

```text
0.9.7.9 status: completed
0.9.7.9.8 work package status: complete
0.9.7.10 status: next
```

The completion-metadata head must pass the same exact-head and merge-candidate workflow before the pull request becomes ready for review.

## Architecture boundary

This correction introduces no runtime executor, command transport, SDK lifecycle, plugin discovery, target-specific public DSL, hidden target selection, shell projection or automatic deployment.
