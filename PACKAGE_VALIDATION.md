# Package Validation

This source package should be validated from a clean checkout after applying the consolidated review fixes.

## Required commands

```bash
./gradlew --no-daemon clean test --stacktrace --console=plain
./gradlew --no-daemon run --args="conformance" --console=plain
```

For offline validation, use a prepared Gradle cache and add `--offline`.

The package intentionally does not claim a fixed test count because test and conformance counts change as review gates are added.
