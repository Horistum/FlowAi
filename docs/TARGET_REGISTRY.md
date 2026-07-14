# Target Registry v2.0

Flow target capabilities and projection evidence are represented as versioned YAML under `targets/`.

This keeps target support data reviewable and extensible. Adding a target must not require adding another vendor field to a public Kotlin data class, and declaring a capability must not be mistaken for proof that an executable renderer payload exists.

## Registry document

```yaml
kind: FlowTargetRegistry
version: "2.0"

expressionProfiles:
  - id: equality-membership-condition
    description: Equality and membership conditions over native scalar values.
    features:
      - node.literal
      - node.reference
      - operator.logical.and
      - operator.binary.==
      - operator.binary.!=
      - operator.binary.in

targets:
  - name: jenkins
    expressionProfile: equality-membership-condition
    capabilities:
      parallel: supported
      approvals: partial
    projectionRules:
      - module: git
        action: checkout
        mode: NATIVE
        reason: Jenkins has a concrete Git checkout step.
        evidenceReference: targets/builtin-targets.yaml#jenkins.git.checkout
        payload:
          kind: JENKINS_STEP
          reference: git
          params:
            url: param:url
            branch: param:branch
```

## Projection rules

A rule identifies one semantic action and states how that target can represent it:

- `NATIVE`: concrete target-native payload evidence exists.
- `NOTES_PROJECTED`: a declarative notes package owns the projection.
- `ADAPTER_REQUIRED`: no complete projection is available.
- `UNSUPPORTED`: the target cannot represent the action safely.
- `BLOCKED`: architecture or safety policy forbids projection.

`NATIVE` and `NOTES_PROJECTED` are not promises made by enum value alone. They require evidence and, for executable output, a structured renderer payload supported by the target renderer. Missing rules fail closed as `ADAPTER_REQUIRED`.

## Renderer payloads

Target Registry 2.0 supports structured payload kinds rather than arbitrary command strings:

- `JENKINS_STEP`
- `GITHUB_ACTION`
- `TEKTON_TASK`

Payload parameters may reference semantic task parameters, inputs or stable literals. The materialization resolver validates those references and carries the resolved payload into Target Manifest 2.0.

## Expression profiles

A target that declares condition support selects an explicit expression profile. The profile describes Flow AST features, not a hardcoded target switch. Missing or empty evidence fails closed.

## Trigger capabilities

Trigger requirements are ordinary compatibility features such as:

- `trigger.manual`
- `trigger.schedule.cron`
- `trigger.schedule.interval`
- `trigger.schedule.calendar`
- `trigger.event`
- `trigger.webhook`

A renderer emits trigger syntax only when the target registry and manifest evidence support the required form.

## Enforcement

The CLI and conformance path both load the same registry, run `CompatibilityAnalyzer`, generate through `TargetManifestGenerationPipeline`, reconcile compatibility with actual materialization readiness and then apply render policy. There is one evidence path, not one truth for tests and another for users.
