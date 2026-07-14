# Flow Standard Intent Model v2.0

The Standard Intent Model is Flow's target-neutral contract for describing what should happen before any target adapter decides how it can be represented.

It sits above the concrete Flow source language and below human or AI-authored requests:

```text
Human or AI intent
  -> Standard Intent Model 2.0
  -> intent validation
  -> Flow AST 2.0
  -> Flow validation
  -> Execution Plan 2.0
  -> compatibility and materialization negotiation
  -> Target Manifest 2.0
```

The model does not execute work and does not choose a vendor by guessing. Target selection, projection evidence and renderer payloads are separate contracts.

## Document shape

```yaml
intentVersion: "2.0"
kind: FlowIntentDocument
name: build-test-deploy

inputs:
  - name: environment
    type: option[dev,test,prod]
    required: true

systems:
  - name: source
    type: git
  - name: standard
    type: standard

triggers:
  - id: manual-run
    type: MANUAL
    workflows: [application-lifecycle]

workflows:
  - name: application-lifecycle
    kind: DEPLOY
    steps:
      - id: checkout
        capability: CHECKOUT
        uses: git.checkout
        params:
          system: source
          url: https://example.invalid/application.git
      - id: test
        capability: TEST
        requires: [checkout]
      - id: deploy
        capability: DEPLOY
        requires: [test]
      - id: verify
        capability: VERIFY
        requires: [deploy]

policies:
  - name: production-approval
    type: APPROVAL
    condition: environment == 'prod'

failure:
  notify: true
  rollback: true
  stopOnError: true
```

`uses` is explicit. Without it, a standard capability lowers to target-neutral `standard.execute` intent. Flow does not silently invent Kubernetes, a shell command or another vendor implementation.

## First-class triggers

Triggers are top-level orchestration intent, not workflow steps.

### Interval schedule

```yaml
triggers:
  - id: renew-every-30-days
    type: SCHEDULE
    workflows: [renew-certificate]
    schedule:
      kind: INTERVAL
      expression: P30D
```

### Cron schedule

```yaml
triggers:
  - id: nightly-backup
    type: SCHEDULE
    workflows: [backup]
    schedule:
      kind: CRON
      expression: "0 2 * * *"
      timezone: Europe/Prague
```

### Other trigger kinds

Intent 2.0 defines `MANUAL`, `SCHEDULE`, `EVENT` and `WEBHOOK`. Target compatibility must state whether a trigger can be represented. Unsupported trigger semantics fail closed or remain review-only; they are never converted into an ordinary `SCHEDULE` task.

## Standard capabilities

The public capability vocabulary includes target-neutral work such as:

- CHECKOUT
- BUILD
- TEST
- PACKAGE
- BUILD_IMAGE
- PUSH_IMAGE
- DEPLOY
- VERIFY
- APPROVE
- ROLLBACK
- NOTIFY
- SYNC
- TRANSFORM
- VALIDATE
- BACKUP
- RESTORE
- CLEANUP
- CERTIFICATE_RENEW
- CALL_API
- CUSTOM

`SCHEDULE` is deliberately absent because scheduling belongs to the trigger model. `RUN_COMMAND` may represent preserved legacy or migration intent, but Flow Core does not project it through a shell execution path.

## Lowering rules

A step with an explicit implementation reference preserves that intent:

```yaml
- id: checkout
  capability: CHECKOUT
  uses: git.checkout
```

A step without `uses` remains semantic:

```yaml
- id: deploy
  capability: DEPLOY
```

This lowers to a standard operation, not to `kubernetes.deploy`. A target may later provide declarative projection evidence for that semantic operation. Missing evidence remains `ADAPTER_REQUIRED`.

## Validation boundary

Intent validation covers, among other things:

- document and contract version,
- unique workflow, trigger and step identifiers,
- valid dependency references,
- trigger-to-workflow references,
- schedule form and ISO-8601 interval syntax,
- explicit implementation references,
- policy and failure structure.

Domain loaders parse YAML; intent validation determines whether the resulting document is a valid Flow intent contract.

## Version migration

Intent 1.x documents are not silently accepted as Intent 2.0. They require explicit migration because triggers and target-neutral lowering change serialized semantics. In particular, legacy `SCHEDULE` workflow steps must move to top-level `triggers`.
