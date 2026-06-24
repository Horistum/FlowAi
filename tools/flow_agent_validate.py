#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AGENT_DIR = ROOT / ".flow-agent"


REQUIRED_FILES = [
    "README.md",
    "agent-contract.md",
    "architecture-constitution.md",
    "roadmap.yaml",
    "release-state.yaml",
    "quality-gates.yaml",
    "forbidden-directions.yaml",
    "work-package-template.yaml",
    "runbook.md",
]


FORBIDDEN_TERMS_IN_PUBLIC_CONTRACT = [
    "Flow is a runtime executor",
    "Flow Core executes workflows",
]


def read(path: Path) -> str:
    if not path.exists():
        raise AssertionError(f"Missing required file: {path}")
    return path.read_text(encoding="utf-8")


def assert_required_files() -> None:
    for name in REQUIRED_FILES:
        read(AGENT_DIR / name)


def assert_immutable_principles() -> None:
    constitution = read(AGENT_DIR / "architecture-constitution.md")
    required = [
        "Flow is an AI-first standardization layer",
        "Flow is not Jenkins-specific",
        "Flow is not a runtime executor",
        "Flow public syntax must remain target-neutral",
    ]
    for item in required:
        if item not in constitution:
            raise AssertionError(f"Missing immutable principle: {item}")


def assert_next_roadmap_item_exists() -> None:
    roadmap = read(AGENT_DIR / "roadmap.yaml")
    has_next = 'status: "next"' in roadmap or "status: next" in roadmap or "status: 'next'" in roadmap
    if not has_next:
        raise AssertionError("No roadmap item with status: next found.")


def assert_no_obvious_forbidden_contract_terms() -> None:
    combined_parts = []
    for path in AGENT_DIR.rglob("*"):
        if path.is_file():
            combined_parts.append(path.read_text(encoding="utf-8"))
    combined = "\n".join(combined_parts)

    for term in FORBIDDEN_TERMS_IN_PUBLIC_CONTRACT:
        if term in combined:
            raise AssertionError(f"Forbidden contract term found: {term}")


def main() -> int:
    try:
        assert_required_files()
        assert_immutable_principles()
        assert_next_roadmap_item_exists()
        assert_no_obvious_forbidden_contract_terms()
        print("Flow agent validation passed.")
        return 0
    except Exception as exc:
        print(f"Flow agent validation failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
