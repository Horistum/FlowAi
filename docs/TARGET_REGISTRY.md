# Target Registry v1.0

Flow target capabilities are represented as versioned YAML under `targets/`.

This is deliberate: target support must be data-driven, reviewable and conformance-testable. Hardcoding every Jenkins, Tekton, Argo Workflows or GitHub Actions behavior in Kotlin would politely re-create the same portability mess Flow exists to avoid.

## File

```text
targets/builtin-targets.yaml
```

## Capability example

```yaml
kind: FlowTargetRegistry
version: "1.0"
targets:
  - name: tekton
    expressionProfile: equality-membership-condition
    capabilities:
      parallel: supported
      approvals: unsupported
      dynamicLoops: partial
```

## Expression profiles

A target that declares condition capability must also select an explicit expression profile. Profiles describe Flow AST features, not target names.

```yaml
expressionProfiles:
  - id: equality-membership-condition
    description: Equality and membership conditions over native scalar target values.
    features:
      - node.literal
      - node.reference
      - operator.logical.and
      - operator.binary.==
      - operator.binary.!=
      - operator.binary.in
```

Each resolved declaration carries a registry evidence reference. Missing profiles fail closed. A profile with an empty feature list is explicit unsupported evidence and is not treated as implicit capability.

Future adapters may provide equivalent target-notes evidence without modifying universal Flow expression semantics.

## Support levels

- `supported` - native or safe representation exists.
- `partial` - representation exists with limitation or workaround.
- `unsupported` - target cannot represent the feature safely.
- `requires_runtime` - target needs Flow runtime support.

## Enforcement

The CLI loads the registry and runs `CompatibilityAnalyzer` over the Execution Plan. Expression requirements are derived from the parsed Flow AST and checked against the target's resolved profile before generation. Translators consume the same declaration, preventing compatibility and renderer behavior from drifting apart.

In strict mode partial support is elevated to an error:

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target tekton --strict"
```
