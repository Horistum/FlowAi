# Flow v0.7.6 Build Validation

Version: `0.7.6-semantic-correctness-hardening`

## Sandbox validation status

This package was validated in a restricted offline sandbox. The full Gradle test suite could not be executed because the source package does not include the Gradle distribution/cache and the wrapper attempts to download `gradle-8.10.2-bin.zip` from `services.gradle.org`, which is unavailable in the sandbox.

The Gradle failure mode was:

```text
Downloading https://services.gradle.org/distributions/gradle-8.10.2-bin.zip
java.net.UnknownHostException: services.gradle.org
```

## Commands executed successfully

Core semantic subset compilation:

```bash
kotlinc -jvm-target 20 @/tmp/flow_sources.txt \
  src/main/kotlin/org/flowlang/generators/manifest/TargetManifestRenderers.kt \
  -d /tmp/flow_core3
```

Result: `PASS`

Intent YAML loader source compilation with minimal Jackson stubs:

```bash
kotlinc -jvm-target 20 \
  src/main/kotlin/org/flowlang/intent/IntentModel.kt \
  src/main/kotlin/org/flowlang/intent/IntentYamlLoader.kt \
  <jackson-stubs> \
  -d /tmp/flow_yaml_min
```

Result: `PASS`

Rendered snapshot regeneration from the fixed planning/rendering path:

```bash
kotlin -classpath /tmp/flow_core3:/tmp/genflow075.jar GenerateFlow075Kt
```

Result: `PASS`

## Full validation to run in a complete offline build environment

```bash
GRADLE_USER_HOME=$PWD/.gradle-user-home ./gradlew --no-daemon clean test --offline --stacktrace --console=plain
GRADLE_USER_HOME=$PWD/.gradle-user-home ./gradlew --no-daemon run --args="conformance" --offline --stacktrace --console=plain
```

These commands require the packaged Gradle distribution/cache to be present locally.
