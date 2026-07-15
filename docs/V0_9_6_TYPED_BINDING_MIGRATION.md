# Target Registry and Target Manifest 2.0 to 3.0 Migration

## Scope

Flow implementation work item `0.9.6.2` introduces universal typed projection bindings.

This is a breaking serialized-contract change for:

- Target Registry `2.0` to `3.0`
- Target Manifest `2.0` to `3.0`

Intent, AST and ExecutionPlan remain at `2.0`. The public Flow standard remains `0.8.0`, and the published implementation package remains `0.9.5` until the `0.9.6` package line is promoted.

## Why the contract changes

Target Registry 2.0 encoded projection binding types inside strings:

```yaml
parameters:
  url: param:url
  branch: literal:main
```

The resolver parsed prefixes and collapsed every value into `Map<String, String>`. That design had four material problems:

1. invalid combinations could not be rejected structurally;
2. missing parameters became empty strings or aborted review generation;
3. source provenance disappeared after resolution;
4. runtime references such as Flow inputs, secrets and task outputs could not be represented consistently.

Target Registry and Target Manifest 3.0 replace those strings with discriminated binding objects.

## Registry migration

### Task parameter

Before:

```yaml
parameters:
  url: param:url
```

After:

```yaml
bindings:
  url:
    kind: TASK_PARAMETER
    name: url
```

### Optional task parameter

Before, optionality was inferred from downstream empty-string handling:

```yaml
parameters:
  branch: param:branch
```

After, the default is explicit:

```yaml
bindings:
  branch:
    kind: TASK_PARAMETER
    name: branch
    defaultValue: main
```

### Literal

Before:

```yaml
parameters:
  depth: literal:1
```

After:

```yaml
bindings:
  depth:
    kind: LITERAL
    value: "1"
```

### Task metadata

Before:

```yaml
parameters:
  taskName: task:id
```

After:

```yaml
bindings:
  taskName:
    kind: TASK_METADATA
    field: ID
```

### Runtime references

Registry 3.0 also supports symbolic bindings that remain unresolved until an edge renderer translates them:

```yaml
bindings:
  environment:
    kind: FLOW_INPUT
    name: environment
  registryToken:
    kind: SECRET
    name: registry-token
  image:
    kind: TASK_OUTPUT
    taskId: build-image
    output: image-reference
```

A target renderer must explicitly support each binding kind it emits. Unsupported combinations fail closed.

## Manifest migration

Target Manifest 2.0 payload:

```yaml
rendererPayload:
  kind: JENKINS_STEP
  target: jenkins
  reference: git
  parameters:
    url: https://example.invalid/repository.git
```

Target Manifest 3.0 resolved payload:

```yaml
rendererPayload:
  kind: JENKINS_STEP
  target: jenkins
  reference: git
  bindings:
    url:
      kind: TASK_PARAMETER
      name: url
      value: https://example.invalid/repository.git
      resolutionStatus: RESOLVED
  evidenceReference: targets/builtin-targets.yaml#targets.jenkins.projectionRules.git.checkout
```

The resolved value remains associated with its semantic origin. Consumers must no longer treat payload values as an untyped string map.

## Resolution states

Target Registry templates do not declare a resolution state. Manifest generation assigns exactly one state to every binding:

- `RESOLVED`: a compile-time value is available and preserved with its source provenance;
- `SYMBOLIC`: a valid runtime reference remains for the concrete edge renderer;
- `UNRESOLVED`: required compile-time evidence is missing, with an explicit reason.

An unresolved binding remains part of a structurally valid Target Manifest and review artifact:

```yaml
bindings:
  url:
    kind: TASK_PARAMETER
    name: url
    resolutionStatus: UNRESOLVED
    reason: Task 'checkout' does not provide required parameter 'url' for projection binding 'url'.
```

`UNRESOLVED` never becomes executable. Generic readiness reports `TARGET_BINDING_UNRESOLVED` and produces a review-only artifact. This preserves the semantic inventory without inventing a URL, replacing the value with an empty string, or aborting unrelated review evidence.

## Fail-closed behavior

Manifest or registry validation fails when:

- a binding contains fields that do not belong to its declared kind;
- a registry template pre-declares a resolution result;
- a `RESOLVED` compile-time binding lacks its value;
- an `UNRESOLVED` binding lacks its reason;
- a symbolic runtime binding declares a value;
- a target expression is bound to a different target;
- a concrete renderer does not support the binding kind.

Executable readiness fails closed when any structurally valid binding remains `UNRESOLVED`.

No missing value is silently replaced with an empty string, and review generation is not discarded merely because executable evidence is incomplete.

## Consumer migration checklist

1. Reject Target Registry and Target Manifest documents whose version is not supported.
2. Replace reads of `payload.parameters` with reads of `payload.bindings`.
3. Dispatch on the binding `kind` discriminator.
4. Inspect `resolutionStatus` before attempting target rendering.
5. Preserve symbolic runtime references until the concrete target rendering boundary.
6. Treat `UNRESOLVED` as review-only, never executable.
7. Fail closed on unknown kinds and invalid field combinations.
8. Update exact snapshots and conformance fixtures to contract version `3.0`.

## Compatibility statement

Flow does not silently reinterpret Target Registry 2.0 string prefixes as Target Registry 3.0 typed bindings. Producers must migrate declarative registry data explicitly. This prevents ambiguous values such as a legitimate literal beginning with `param:` from changing meaning based on undocumented parser behavior.
