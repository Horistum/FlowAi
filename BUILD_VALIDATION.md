# Build Validation

This file records the current validation procedure, not a stale historical build result.

## Commands

```bash
./gradlew --no-daemon clean test --stacktrace --console=plain
./gradlew --no-daemon run --args="conformance" --console=plain
```

## Offline mode

When validating a full-offline package, set `GRADLE_USER_HOME` to a prepared cache and add `--offline`:

```bash
GRADLE_USER_HOME=/path/to/gradle-cache ./gradlew --offline --no-daemon clean test --stacktrace --console=plain
GRADLE_USER_HOME=/path/to/gradle-cache ./gradlew --offline --no-daemon run --args="conformance" --console=plain
```

## Note

Historical v0.7.6 package results were removed from this top-level validation file because they are not evidence for the current package line.
