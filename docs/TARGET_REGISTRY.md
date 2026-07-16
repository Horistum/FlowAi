# Target Registry v3.0

Flow target capabilities and projection evidence are represented as versioned YAML under `targets/`.

This keeps target support data reviewable and extensible. Adding a target must not require adding another vendor field to a public Kotlin data class, and declaring a capability must not be mistaken for proof that an executable renderer payload exists.

## Registry document

```yaml
kind: FlowTargetRegistry
version: "3.0"

targets:
  - name: jenkins
    expressionProfile: equality-membership-condition
    capabilities:
      parallel: supported
      approvals: partial
    projectionRules:
      - module: git
        action: checkout
        mode: native
        reason: Jenkins has a concrete Git checkout step.
        evidenceReference: targets/builtin-targets.yaml#jenkins.git.checkout
        payload:
          kind: jenkins-step
          reference: git
          bindings:
            url:
              kind: TASK_PARAMETER
              name: url
            branch:
              kind: TASK_PARAMETER
              name: branch
              defaultValue: main
```

## Projection rules

A rule identifies one semantic action and states how that target can represent it:

- `NATIVE`: concrete target-native payload evidence exists.
- `NOTES_PROJECTED`: a declarative notes package owns the projection.
- `ADAPTER_REQUIRED`: no complete projection is available.
- `UNSUPPORTED`: the target cannot represent the action safely.
- `BLOCKED`: architecture or safety policy forbids projection.

`NATIVE` and `NOTES_PROJECTED` are not promises made by enum value alone. They require evidence. A `NATIVE` rule must also match an immutable provider-owned native projection definition for the same opaque payload kind, reference and typed binding schema. Missing rules fail closed as `ADAPTER_REQUIRED`; missing or inconsistent native implementation evidence rejects generation before executable readiness can be inferred.

## Renderer payloads

The `kind` field is an opaque projection-consumer identifier, not a Flow Core enum. The registry loader validates and canonicalizes the identifier. The selected target provider must then own a matching native projection definition, while only the concrete edge renderer interprets target syntax and behavior.

Consequences:

- Flow Core does not enumerate Jenkins, GitHub Actions, Tekton, or future target payload kinds.
- Adding a new target payload kind does not change Intent, AST, ExecutionPlan or materialization semantics.
- Provider composition validates registry rules against implemented opaque payload identities and typed binding schemas.
- Generic readiness validates payload presence, target binding, reference, evidence and typed bindings.
- A concrete renderer still fails closed when it receives syntax or behavior it does not own.

## Typed bindings

Target Registry 3.0 replaces prefix-encoded strings such as `param:url`, `input:name` and `literal:value` with discriminated binding objects.

Supported binding kinds are:

| Kind | Registry meaning | Manifest behavior |
|---|---|---|
| `LITERAL` | Stable literal value | Preserved with its literal provenance |
| `TASK_PARAMETER` | Read a named task parameter | Resolved during manifest generation |
| `TASK_INPUT` | Read a named task input | Resolved during manifest generation |
| `TASK_METADATA` | Read task `ID` or `TARGET` | Resolved during manifest generation |
| `FLOW_INPUT` | Reference a top-level Flow input | Remains symbolic for the edge renderer |
| `SECRET` | Reference an opaque secret | Remains symbolic and requires renderer evidence |
| `ARTIFACT` | Reference a named artifact | Remains symbolic and requires renderer evidence |
| `TASK_OUTPUT` | Reference a named output of another task | Remains symbolic for dependency-aware rendering |
| `TARGET_EXPRESSION` | Explicit target-owned expression | Allowed only for the declared target |

Compile-time bindings preserve their kind after resolution. A resolved `TASK_PARAMETER` therefore carries its source name and resolved value instead of collapsing into an anonymous string.

Missing compile-time task parameters or inputs remain explicit `UNRESOLVED` manifest bindings. They force review-only readiness and are never silently converted to empty strings. Optional compile-time values may use an explicit `defaultValue`.

## Native implementation catalogs

A target distribution explicitly composes a `TargetNativeProjectionCatalog`. Each definition contains only target-neutral contract data:

- opaque payload kind and reference;
- named required or optional binding slots;
- accepted `ProjectionBindingKind` values.

The catalog does not discover plugins, select runtime handlers or define target syntax. It proves only that the selected edge distribution owns a concrete implementation contract corresponding to registry evidence. Registry declaration, provider implementation contract and generated manifest bindings must all agree.

The current built-in catalog intentionally declares only the existing Jenkins Git checkout payload. GitHub Actions and Tekton have empty native catalogs until later reviewed coverage work adds concrete implementations.

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
