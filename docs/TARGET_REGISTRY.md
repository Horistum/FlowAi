# Target Registry v1.0

Flow target capabilities are now represented as versioned YAML under `targets/`.

This is deliberate: target support must be data-driven, reviewable and conformance-testable. Hardcoding every Jenkins, Tekton, Argo Workflows or GitHub Actions behavior in Kotlin would politely re-create the same portability mess Flow exists to avoid.

## File

```text
targets/builtin-targets.yaml
```

## Example

```yaml
kind: FlowTargetRegistry
version: "1.0"
targets:
  - name: tekton
    capabilities:
      parallel: supported
      approvals: unsupported
      dynamicLoops: partial
```

## Support levels

- `supported` - native or safe representation exists.
- `partial` - representation exists with limitation or workaround.
- `unsupported` - target cannot represent the feature safely.
- `requires_runtime` - target needs Flow runtime support.

## Enforcement

The CLI loads this registry and runs the `CompatibilityAnalyzer` over the Execution Plan. In strict mode partial support is elevated to an error:

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target tekton --strict"
```
