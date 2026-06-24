# Package Validation

Package: `flow-core-prod-v0_7_6_semantic-correctness-hardening-fix2-source-with-wrapper.zip`

This package is a clean source handoff package. It intentionally excludes heavy local Gradle caches, build outputs, and generated report directories, but it keeps the official Gradle wrapper files required by `./gradlew` / `gradlew.bat`:

- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`

Validation performed from an extracted copy using an external offline Gradle user home:

```bash
GRADLE_USER_HOME=/mnt/data/flow_fix2_work/.gradle-user-home ./gradlew --offline --no-daemon clean test --console=plain
```

Result:

```text
BUILD SUCCESSFUL in 1m 25s
5 actionable tasks: 5 executed
```

Observed test suite result from the Gradle output:

```text
151 tests
0 failures
0 errors
0 skipped
```

Note: without an existing Gradle distribution/cache, `./gradlew clean test` may download the Gradle distribution declared in `gradle/wrapper/gradle-wrapper.properties`. For fully offline build execution, use the previously provided full-offline build environment or provide an equivalent local Gradle cache.
