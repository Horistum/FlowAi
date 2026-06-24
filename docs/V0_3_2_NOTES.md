# Flow v0.3.2 Notes

v0.3.2 is a contract-hardening release.

## Safety policy validation

Safety policies are validated before AST lowering. The validator blocks unresolved or unsafe intents instead of relying on target generators to discover risk late.

Supported standard gates:

- `requiresApproval`
- `requiresClarification`
- `requiresDryRun`
- `requiresBackup`
- `requiresRollbackPlan`
- `requiresChangeTicket`
- `destructiveOperation`
- `externalSideEffect`
- `unmitigatedHighRisk`

Cleanup is treated as destructive enough to require explicit retention or safety. A cleanup request such as `Cleanup old docker images` now asks for `safety.cleanup.retention` and blocks lowering until the decision is provided.

## Canonical Execution Plan export

The internal planner keeps historical Kotlin node classes for compatibility. Adapters and runtimes should consume `canonical-execution-plan.json`, where public node kinds are lowercase:

```text
task
approval
condition
parallel
retry
rollback
notification
artifact
secret
```

The legacy `execution-plan.json` remains exported for compatibility.

## Capability negotiation schema

`schemas/capability-negotiation-report.schema.json` defines the public report that explains whether each target fully, partially or cannot support the required plan capabilities.

## Adapter loader namespace

New code should use:

```text
org.flowlang.adapters.yaml.IntentYamlLoader
org.flowlang.adapters.yaml.TargetRegistryYamlLoader
```

The legacy loader locations remain available as compatibility shims.

## Conformance additions

New v0.3.2 checks cover:

- canonical lowercase execution plan,
- cleanup without retention blocked,
- cleanup with retention accepted,
- Kubernetes maintenance without dry-run blocked.
