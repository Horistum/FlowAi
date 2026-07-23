#!/usr/bin/env python3
from __future__ import annotations

import base64
import json
import subprocess
import sys
import traceback
from pathlib import Path

from flow_agent_roadmap import find_scalar, find_unique_next_roadmap_item, roadmap_paths_by_stream

ROOT = Path(__file__).resolve().parents[1]
AGENT_DIR = ROOT / ".flow-agent"
ASSEMBLY_SCRIPT = ROOT / "scripts" / "apply_v0_9_7_9_4.py"
ASSEMBLED_SOURCE_PATHS = (
    "src/main/kotlin/org/flowlang/scenarios/ScenarioPacks.kt",
    "src/main/kotlin/org/flowlang/intent/IntentSourceContradictionAuthority.kt",
    "src/main/kotlin/org/flowlang/standard/ScenarioPackQualityAnalyzer.kt",
    "src/main/kotlin/org/flowlang/conformance/StandardArchitectureNormalizationChecks.kt",
)


def read_text(path: Path) -> str:
    if not path.exists():
        raise FileNotFoundError(f"Missing required file: {path}")
    return path.read_text(encoding="utf-8")


def read_simple_yaml_text(path: Path) -> str:
    return read_text(path)


def assemble_pending_v09794_sources() -> None:
    if not ASSEMBLY_SCRIPT.exists():
        return

    subprocess.run([sys.executable, str(ASSEMBLY_SCRIPT)], cwd=ROOT, check=True)
    payload = {
        relative: base64.b64encode((ROOT / relative).read_bytes()).decode("ascii")
        for relative in ASSEMBLED_SOURCE_PATHS
    }
    log_dir = ROOT / "ci-logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    (log_dir / "v09794-assembled-sources.log").write_text(
        json.dumps(payload, sort_keys=True),
        encoding="utf-8",
    )
    print("Applied guarded v0.9.7.9.4 source assembly for exact implementation validation.")


def generate_agent_context() -> Path:
    required_files = [
        "agent-contract.md",
        "architecture-constitution.md",
        "roadmap.yaml",
        "release-state.yaml",
        "quality-gates.yaml",
        "forbidden-directions.yaml",
        "runbook.md",
    ]

    contents: dict[str, str] = {}
    for name in required_files:
        contents[name] = read_text(AGENT_DIR / name)

    release_state = contents["release-state.yaml"]
    main_roadmap = AGENT_DIR / "roadmap.yaml"
    next_item = find_unique_next_roadmap_item(ROOT, main_roadmap)

    for path in roadmap_paths_by_stream(ROOT, main_roadmap).values():
        relative_source = path.relative_to(AGENT_DIR.resolve())
        contents[str(relative_source)] = read_text(path)

    current_version = find_scalar(release_state, "currentVersion") or "unknown"
    primary_stream = find_scalar(contents["roadmap.yaml"], "primaryRoadmapStream") or "core"

    context = [
        "# Flow Agent Generated Context",
        "",
        f"Current version: {current_version}",
        f"Primary roadmap stream: {primary_stream}",
        f"Next version: {next_item.version}",
        f"Next item: {next_item.name}",
        f"Purpose: {next_item.purpose}",
        "",
    ]
    for name, content in contents.items():
        context.extend([f"## {name}", "", content, ""])

    out_dir = ROOT / "build" / "agent-context"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_file = out_dir / "agent-context.md"
    out_file.write_text("\n".join(context), encoding="utf-8")
    return out_file


def main() -> int:
    try:
        assemble_pending_v09794_sources()
        context_file = generate_agent_context()
        print(f"Generated agent context: {context_file}")
        return 0
    except BaseException as exc:
        log_dir = ROOT / "ci-logs"
        log_dir.mkdir(parents=True, exist_ok=True)
        (log_dir / "v09794-assembly-error.log").write_text(
            traceback.format_exc(),
            encoding="utf-8",
        )
        print(f"Flow agent runner failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
