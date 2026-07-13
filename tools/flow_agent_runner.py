#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any

from flow_agent_roadmap import find_scalar, find_unique_next_roadmap_item


ROOT = Path(__file__).resolve().parents[1]
AGENT_DIR = ROOT / ".flow-agent"


def read_text(path: Path) -> str:
    if not path.exists():
        raise FileNotFoundError(f"Missing required file: {path}")
    return path.read_text(encoding="utf-8")


def read_simple_yaml_text(path: Path) -> str:
    return read_text(path)


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

    if next_item.source != main_roadmap.resolve():
        relative_source = next_item.source.relative_to(AGENT_DIR.resolve())
        contents[str(relative_source)] = read_text(next_item.source)

    current_version = find_scalar(release_state, "currentVersion") or "unknown"

    context = []
    context.append("# Flow Agent Generated Context")
    context.append("")
    context.append(f"Current version: {current_version}")
    context.append(f"Next version: {next_item.version}")
    context.append(f"Next item: {next_item.name}")
    context.append(f"Purpose: {next_item.purpose}")
    context.append("")
    for name, content in contents.items():
        context.append(f"## {name}")
        context.append("")
        context.append(content)
        context.append("")

    out_dir = ROOT / "build" / "agent-context"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_file = out_dir / "agent-context.md"
    out_file.write_text("\n".join(context), encoding="utf-8")
    return out_file


def run(command: list[str]) -> int:
    print(f"Running: {' '.join(command)}")
    completed = subprocess.run(command, cwd=ROOT)
    return completed.returncode


def main() -> int:
    try:
        context_file = generate_agent_context()
        print(f"Generated agent context: {context_file}")
        return 0
    except Exception as exc:
        print(f"Flow agent runner failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
