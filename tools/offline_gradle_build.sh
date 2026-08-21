#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-}"
CACHE_DIR="${FLOW_OFFLINE_CACHE_DIR:-$ROOT_DIR/.flow-offline/gradle-home}"
VERIFY_DIR="${FLOW_OFFLINE_VERIFY_DIR:-$ROOT_DIR/.flow-offline/verify-home}"

require_jdk25() {
  local major
  major="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
  if [[ "$major" != "25" ]]; then
    echo "Offline build requires JDK 25; detected '${major:-unknown}'." >&2
    exit 2
  fi
}

run_boundary() {
  local gradle_home="$1"
  shift
  (
    cd "$ROOT_DIR"
    GRADLE_USER_HOME="$gradle_home" ./gradlew --no-daemon "$@" --stacktrace --console=plain
  )
}

prepare() {
  require_jdk25
  rm -rf "$CACHE_DIR"
  mkdir -p "$CACHE_DIR"

  # Resolve the wrapper distribution, plugins and all build/runtime dependencies
  # into one isolated Gradle home. This phase is intentionally allowed network
  # access and is the only phase that refreshes the offline input closure.
  run_boundary "$CACHE_DIR" --version
  run_boundary "$CACHE_DIR" clean test
  run_boundary "$CACHE_DIR" run --args=conformance

  mkdir -p "$ROOT_DIR/.flow-offline"
  {
    printf 'jdk=25\n'
    printf 'gradle-wrapper-properties-sha256='
    sha256sum "$ROOT_DIR/gradle/wrapper/gradle-wrapper.properties" | awk '{print $1}'
    printf 'gradle-wrapper-jar-sha256='
    sha256sum "$ROOT_DIR/gradle/wrapper/gradle-wrapper.jar" | awk '{print $1}'
    printf 'build-gradle-sha256='
    sha256sum "$ROOT_DIR/build.gradle.kts" | awk '{print $1}'
    if [[ -f "$ROOT_DIR/settings.gradle.kts" ]]; then
      printf 'settings-gradle-sha256='
      sha256sum "$ROOT_DIR/settings.gradle.kts" | awk '{print $1}'
    fi
  } > "$ROOT_DIR/.flow-offline/input-manifest.txt"
}

verify() {
  require_jdk25
  if [[ ! -d "$CACHE_DIR" ]]; then
    echo "Prepared offline Gradle home is missing: $CACHE_DIR" >&2
    exit 3
  fi

  rm -rf "$VERIFY_DIR"
  mkdir -p "$VERIFY_DIR"
  cp -a "$CACHE_DIR"/. "$VERIFY_DIR"/

  # The verification boundary uses a distinct Gradle home and --offline for
  # every Gradle invocation. A missing wrapper/plugin/dependency therefore
  # fails instead of being silently downloaded during verification.
  run_boundary "$VERIFY_DIR" --offline clean test
  run_boundary "$VERIFY_DIR" --offline run --args=conformance
}

case "$MODE" in
  prepare) prepare ;;
  verify) verify ;;
  all) prepare; verify ;;
  *)
    echo "Usage: $0 {prepare|verify|all}" >&2
    exit 64
    ;;
esac
