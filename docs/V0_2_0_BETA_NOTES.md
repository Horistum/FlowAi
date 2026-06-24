# Flow Core v0.2.0-beta Notes

This release focuses on making the project more standard-like and less demo-like.

## Added

- `conformance` CLI command.
- Conformance runner for the public intent-to-manifest path.
- GitHub Actions Target Manifest generator.
- Jenkins Target Manifest renderer.
- GitHub Actions workflow renderer.
- `--render` support for the `intent` CLI command.
- Conformance vector files under `conformance/`.

## Example Commands

```bash
./gradlew run --args="conformance"
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target jenkins --strict --render"
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target github-actions --render"
```

## Still Work In Progress

- Renderers are draft outputs, not production-grade Jenkins/GitHub Actions generators.
- Target manifests do not yet preserve all high-level semantics such as artifacts, environments, matrices or protected approvals.
- Conformance vector files are not yet fully data-driven.
- Execution plan dependency semantics need a stricter DAG model in later versions.
