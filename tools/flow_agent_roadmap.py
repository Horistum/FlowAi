#!/usr/bin/env python3
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class RoadmapItem:
    version: str
    name: str
    purpose: str
    item_type: str
    source: Path


def read_text(path: Path) -> str:
    if not path.is_file():
        raise FileNotFoundError(f"Missing roadmap file: {path}")
    return path.read_text(encoding="utf-8")


def find_scalar(text: str, key: str) -> str | None:
    pattern = rf"(?m)^\s*{re.escape(key)}:\s*[\"']?([^\"'\n#]+)[\"']?\s*(?:#.*)?$"
    match = re.search(pattern, text)
    return match.group(1).strip() if match else None


def active_roadmap_paths(root: Path, main_roadmap: Path) -> tuple[Path, ...]:
    root = root.resolve()
    main_roadmap = main_roadmap.resolve()
    main_text = read_text(main_roadmap)
    paths = [main_roadmap]

    active_track = find_scalar(main_text, "activeRepairTrack")
    if active_track:
        candidate = (root / active_track).resolve()
        try:
            candidate.relative_to(root)
        except ValueError as exc:
            raise RuntimeError(
                f"Active repair track must stay inside the repository: {active_track}"
            ) from exc
        if candidate == main_roadmap:
            raise RuntimeError("Active repair track must not reference the main roadmap itself.")
        read_text(candidate)
        paths.append(candidate)

    return tuple(paths)


def next_items_in(path: Path) -> list[RoadmapItem]:
    text = read_text(path)
    items: list[RoadmapItem] = []
    blocks = re.split(r"(?m)^\s*-\s+version:\s*", text)

    for block in blocks[1:]:
        version_match = re.match(r"[\"']?([^\"'\n]+)[\"']?", block)
        if not version_match or find_scalar(block, "status") != "next":
            continue
        items.append(
            RoadmapItem(
                version=version_match.group(1).strip(),
                name=find_scalar(block, "name") or "",
                purpose=find_scalar(block, "purpose") or "",
                item_type=find_scalar(block, "type") or "",
                source=path,
            )
        )

    return items


def find_unique_next_roadmap_item(root: Path, main_roadmap: Path) -> RoadmapItem:
    paths = active_roadmap_paths(root, main_roadmap)
    items = [item for path in paths for item in next_items_in(path)]

    if not items:
        checked = ", ".join(str(path.relative_to(root.resolve())) for path in paths)
        raise RuntimeError(f"No roadmap item with status: next found in: {checked}")

    if len(items) > 1:
        details = ", ".join(
            f"{item.version} ({item.source.relative_to(root.resolve())})" for item in items
        )
        raise RuntimeError(f"Multiple roadmap items have status: next: {details}")

    return items[0]
