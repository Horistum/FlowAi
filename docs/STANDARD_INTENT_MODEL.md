# Flow Standard Intent Model v1.0

The Standard Intent Model is the high-level layer above low-level Flow source syntax.

It captures what the user wants before the system lowers it to canonical Flow AST and then to an Execution Plan.

## Why this layer exists

Low-level Flow can express precise actions:

```flow
shell.run local {
  command: "mvn test"
} -> tests
```

But the original Flow vision is higher-level:

```text
Build the application.
Run tests.
Deploy to production only after approval.
Verify health.
Rollback on failure.
```

The Standard Intent Model keeps Flow from becoming only a technical parser DSL.

## Intent pipeline

```text
Human Intent
  -> AI Draft Intent
  -> Standard Intent Model
  -> Intent Validator
  -> Flow AST
  -> Flow Validator
  -> Execution Plan
```

## Model shape

```yaml
intentVersion: "1.0"
kind: FlowIntentDocument
name: build-test-deploy
inputs:
  - name: environment
    type: option[dev,test,prod]
    required: true
systems:
  - name: source
    type: git
  - name: registry
    type: dockerRegistry
  - name: cluster
    type: kubernetes
workflows:
  - name: application-lifecycle
    kind: DEPLOY
    steps:
      - id: checkout
        capability: CHECKOUT
        uses: git
      - id: test
        capability: TEST
        uses: shell
        requires: [checkout]
      - id: build-image
        capability: BUILD_IMAGE
        uses: docker
        requires: [test]
      - id: approve-prod
        capability: APPROVE
        requires: [build-image]
      - id: deploy
        capability: DEPLOY
        uses: kubernetes
        requires: [approve-prod]
      - id: verify
        capability: VERIFY
        uses: kubernetes
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

## Standard capabilities

Initial standard capabilities:

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
- RUN_COMMAND
- CALL_API
- CUSTOM

## Important design rule

The Standard Intent Model does not replace modules. It selects and organizes capabilities. Modules provide concrete implementations.

Example:

```text
Intent: TEST
  -> could lower to shell.run, maven.test, gradle.test, npm.test, or a target-native task
```

## Work in progress

- Full intent-to-AST lowering is not implemented yet.
- Intent validation is only represented by data classes in this version.
- AI prompt contracts for producing this model are not implemented yet.
- Capability selection rules are not implemented yet.
