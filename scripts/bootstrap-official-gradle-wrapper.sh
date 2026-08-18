#!/usr/bin/env bash
set -euo pipefail
# The official Gradle wrapper (gradle/wrapper/gradle-wrapper.jar + gradlew) ships
# with this repository, so no bootstrap is required. To regenerate/upgrade the
# Gradle 9.5.0 wrapper, run the wrapper task twice so the distribution metadata,
# wrapper JAR and generated launch scripts all come from the selected Gradle:
#   checksum='553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746'
#   ./gradlew --no-daemon wrapper --gradle-version 9.5.0 --distribution-type bin --gradle-distribution-sha256-sum "$checksum"
#   ./gradlew --no-daemon wrapper --gradle-version 9.5.0 --distribution-type bin --gradle-distribution-sha256-sum "$checksum"
