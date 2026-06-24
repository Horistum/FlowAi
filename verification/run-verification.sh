#!/usr/bin/env bash
#
# Runs the FlowLang legacy verification suite WITHOUT Gradle/network.
#
# v0.1.9 note: user-facing intent loading now uses Jackson YAML. Full intent
# conformance therefore belongs to Gradle tests where dependencies are present.
# This offline script remains useful for low-level core/parser/planner checks in
# restricted sandboxes, but it is not the authoritative full test command.
#
# Authoritative verification:
#   ./gradlew clean test
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

OUT="${TMPDIR:-/tmp}/flow-verify"
mkdir -p "$OUT"

echo "[1/3] Compiling low-level core subset (excluding CLI, YAML adapters and Jackson-backed conformance)..."
# The offline core subset must exclude every source that depends on Jackson or the CLI:
#   cli/*                      -> Json.kt, FlowCli.kt (Jackson)
#   adapters/yaml/*            -> IntentYamlLoader, TargetRegistryYamlLoader (Jackson)
#   intent/IntentYamlLoader.kt -> Jackson (matched by name)
#   targets/TargetRegistry.kt  -> Jackson
#   conformance/ConformanceRunner.kt, conformance/JsonSchemaSmokeValidator.kt -> Jackson + CLI
# These are exercised by `./gradlew clean test`, where the dependencies are present.
CORE=$(find src/main/kotlin -name '*.kt' \
    ! -path '*/cli/*' \
    ! -path '*/adapters/yaml/*' \
    ! -name 'IntentYamlLoader.kt' \
    ! -name 'TargetRegistry.kt' \
    ! -name 'ConformanceRunner.kt' \
    ! -name 'JsonSchemaSmokeValidator.kt')
kotlinc $CORE -d "$OUT/flow-core.jar"

echo "[2/3] Compiling legacy non-intent scenario suite..."
# betaConformanceTests() and rc4SemanticGeneratorRegressionTests() drive ConformanceRunner /
# IntentYamlLoader / TargetRegistryYamlLoader (Jackson-backed) and therefore belong to Gradle.
# Compile copies of the suite with those two entrypoints removed so the offline subset still builds;
# the real test sources are left untouched and remain authoritative under Gradle.
cp tests/FlowSpecTests_part1.kt tests/FlowSpecTests_part2.kt "$OUT/"
sed -i '/^[[:space:]]*betaConformanceTests()[[:space:]]*$/d; /^[[:space:]]*rc4SemanticGeneratorRegressionTests()[[:space:]]*$/d' "$OUT/FlowSpecTests_part2.kt"
kotlinc -cp "$OUT/flow-core.jar" "$OUT/FlowSpecTests_part1.kt" "$OUT/FlowSpecTests_part2.kt" \
    -include-runtime -d "$OUT/flow-tests.jar"

echo "[3/3] Running scenarios (offline subset; betaConformanceTests + rc4 regression run under Gradle)..."
java -cp "$OUT/flow-tests.jar:$OUT/flow-core.jar" FlowSpecTests_part2Kt
