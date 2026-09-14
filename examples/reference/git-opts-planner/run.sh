#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../../.." && pwd)"
OUT_DIR="${1:-generated}"

resolve_binary() {
  if [[ -n "${HORISTUM_BIN:-}" ]]; then
    printf '%s\n' "$HORISTUM_BIN"
    return
  fi

  if command -v flow-core >/dev/null 2>&1; then
    command -v flow-core
    return
  fi

  local repo_binary="$REPO_ROOT/flow-cli/build/install/flow-core/bin/flow-core"
  if [[ ! -x "$repo_binary" && -x "$REPO_ROOT/gradlew" ]]; then
    (
      cd "$REPO_ROOT"
      ./gradlew :flow-cli:installDist
    )
  fi

  if [[ -x "$repo_binary" ]]; then
    printf '%s\n' "$repo_binary"
    return
  fi

  echo "Horistum product CLI was not found." >&2
  echo "Set HORISTUM_BIN=/absolute/path/to/flow-core or run this example inside the Horistum repository." >&2
  exit 2
}

HORISTUM="$(resolve_binary)"
if [[ "$HORISTUM" != /* ]]; then
  HORISTUM="$(cd "$(dirname "$HORISTUM")" && pwd)/$(basename "$HORISTUM")"
fi

rm -rf "$HERE/$OUT_DIR"

(
  cd "$HERE"
  "$HORISTUM" intent intent/git-change.intent.yaml --out "$OUT_DIR"
)

python3 "$HERE/verify.py" "$HERE/$OUT_DIR"

echo
echo "Generated Horistum artifacts: $HERE/$OUT_DIR"
echo "Edit intent/git-change.intent.yaml and rerun this script to plan another Git change."
