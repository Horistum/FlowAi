# Intent Design Report

The Intent Design Report is a pre-lowering architecture report.

It exists to keep the user in the role of solution architect. It explains what the intent requires before Flow lowers it into AST and execution-plan details.

## It reports

- used standard capabilities,
- capability category and maturity,
- required systems,
- missing system declarations,
- missing required config,
- design decisions that must be supplied by policy/environment/module registry,
- portability notes.

## Why it matters

A user should not discover during runtime that ArgoCD requires `url` and `token`, or that Tekton does not natively support manual approvals.

The design report surfaces these facts early.

## CLI

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target jenkins --strict --render --out generated/jenkins"
```

Exported artifact:

```text
generated/jenkins/intent-design-report.json
```

## Work in progress

- Design report severity levels are still simple.
- Organization-specific policy catalogs are not implemented yet.
- Report-to-human narrative rendering is planned after the JSON contract stabilizes.
