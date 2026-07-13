# v0.9.5.7.7 Policy-Driven Safety Prelude

## Purpose

This correction removes two production assumptions that were still embedded in Flow Core:

- `SafetyBoundaryValidator` owned a fixed list of environment parameter names and production-like values.
- the GitHub Actions renderer forced every approval job to use the target environment `production`.

Both behaviors confused a human naming convention with safety evidence. A namespace named `prod` may be sensitive under one policy, while a differently named tenant or environment may be sensitive under another. A universal automation standard cannot decide that question through a private list of fashionable strings.

## Policy notes boundary

`EnvironmentSafetyPolicyNotes` declares:

- the notes package identity and version,
- environment parameter names understood by each rule,
- values classified by each rule,
- the resulting sensitivity,
- an optional target approval environment for sensitive evidence,
- the reason for the rule.

The policy is descriptive. It does not load plugins, execute lookups, contact a runtime or create target resources.

`EnvironmentSafetyPolicy` produces `EnvironmentClassificationEvidence` with:

- `SENSITIVE`, `NON_SENSITIVE` or `UNKNOWN`,
- policy package identity,
- matched rule identity,
- matched parameter and value,
- optional approval-environment evidence,
- an explanation.

Unknown evidence remains unknown. It is not promoted to sensitive or non-sensitive merely to make a target renderer convenient.

## Safety validation

`SafetyBoundaryValidator` no longer contains production parameter or value sets.

For an externally mutating action, it evaluates scalar action parameters through the supplied environment policy. Approval is required when the resulting evidence is sensitive. Existing safety behavior remains intact:

- destructive actions still require approval,
- contract-declared approval requirements still apply,
- rollback outside error-handler recovery still requires approval,
- rollback inside error-handler recovery remains allowed by the established boundary.

The validation issue cites the policy package, rule and matched parameter evidence. This makes the decision reviewable instead of presenting a magic word match as architectural truth.

## GitHub Actions approval environments

A GitHub Actions approval job no longer automatically emits:

```yaml
environment: production
```

`TargetEnvironmentSafetyEvidenceResolver` inspects action parameters in jobs that directly depend on the approval job. The renderer emits a target approval environment only when:

1. downstream evidence is classified as sensitive,
2. the matching safety policy declares an approval environment,
3. all sensitive matches resolve to one unambiguous environment.

For non-sensitive, unknown, missing or conflicting evidence, no target environment is emitted. The generated artifact records the policy and classification decision as review evidence.

This prelude intentionally does not solve arbitrary graph-wide approval scope or dynamic runtime environment resolution. Those require stronger declarative contracts and belong to the wider policy-driven safety work rather than being improvised inside a renderer.

## Baseline policy

The initial baseline remains compatible with the previously recognized production-like environment names, but those names now live in `flow.safety.core` policy evidence rather than validator code.

The baseline also explicitly classifies common engineering environments as non-sensitive. Custom safety notes can classify other parameter names and values, including tenant-specific environments that do not use `prod` terminology.

## Validation

Regression coverage proves:

- sensitive baseline evidence requires approval for mutating actions,
- approved sensitive actions pass,
- unknown names are not guessed as production,
- custom policy notes classify nonstandard sensitive environments,
- GitHub approval environments require sensitive downstream evidence,
- non-sensitive and unknown evidence do not emit a target environment,
- the old hardcoded validator lists and unconditional renderer statement are absent.

## Version boundary

- published package remains `0.9.4`,
- correction track remains unreleased `v0.9.5.x`,
- public Flow standard remains `0.7.6`,
- artifact contract versions remain unchanged.

## Architecture boundary

This correction does not introduce:

- a runtime executor,
- an SDK surface,
- a framework or plugin lifecycle,
- shell or command projection,
- a target-specific public Flow DSL,
- dynamic policy loading,
- renderer payload support for unresolved semantic actions.
