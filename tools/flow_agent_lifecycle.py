#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from flow_agent_roadmap import (
    RoadmapItem,
    active_correction_work_package,
    find_scalar,
    find_unique_next_roadmap_item,
    next_items_in,
    read_text,
    roadmap_item_blocks,
    roadmap_paths_by_stream,
    validate_roadmap_structure,
)


@dataclass(frozen=True)
class RoadmapFocus:
    item: RoadmapItem
    lifecycle: str


def _item_version(block: str) -> str:
    first_line = block.splitlines()[0].strip()
    return first_line.strip('"\' ')


def _completed_primary_item(root: Path, main_roadmap: Path) -> RoadmapItem:
    root = root.resolve()
    main_roadmap = main_roadmap.resolve()
    main_text = read_text(main_roadmap)
    primary_stream = find_scalar(main_text, "primaryRoadmapStream") or "core"
    paths = roadmap_paths_by_stream(root, main_roadmap)
    primary_path = paths.get(primary_stream)
    if primary_path is None:
        raise RuntimeError(f"Completed roadmap has no primary stream file: {primary_stream}")

    if active_correction_work_package(root, main_roadmap) is not None:
        raise RuntimeError("A completed primary track must not retain an active correction work package.")

    primary_text = read_text(primary_path)
    if find_scalar(primary_text, "status") != "completed":
        raise RuntimeError("A primary roadmap without a next item must declare track status: completed.")

    if next_items_in(primary_path, primary_stream):
        raise RuntimeError("A completed primary track must not retain a status: next item.")

    completed_item = find_scalar(main_text, "completedItem") or ""
    completed_name = find_scalar(main_text, "completedItemName") or ""
    closure_item = find_scalar(main_text, "nextCoreItem") or ""
    closure_name = find_scalar(main_text, "nextCoreItemName") or ""
    closure_status = find_scalar(main_text, "nextCoreItemStatus") or ""

    if closure_status != "completed":
        raise RuntimeError(
            "A primary roadmap without a next item must declare nextCoreItemStatus: completed."
        )
    if not completed_item or completed_item != closure_item:
        raise RuntimeError(
            "Completed roadmap metadata must identify the same completedItem and nextCoreItem."
        )
    if not completed_name or completed_name != closure_name:
        raise RuntimeError(
            "Completed roadmap metadata must identify the same completedItemName and nextCoreItemName."
        )

    selected_block: str | None = None
    incomplete: list[str] = []
    for block in roadmap_item_blocks(primary_text):
        version = _item_version(block)
        status = find_scalar(block, "status") or "missing"
        if status != "completed":
            incomplete.append(f"{version}:{status}")
        if version == completed_item:
            selected_block = block

    if incomplete:
        raise RuntimeError(
            "A completed primary track contains non-completed items: " + ", ".join(incomplete)
        )
    if selected_block is None:
        raise RuntimeError(f"Completed roadmap item is not declared in the primary track: {completed_item}")

    declared_name = find_scalar(selected_block, "name") or ""
    if declared_name != completed_name:
        raise RuntimeError(
            f"Completed roadmap item name mismatch for {completed_item}: "
            f"index says {completed_name!r}, primary roadmap says {declared_name!r}"
        )

    return RoadmapItem(
        version=completed_item,
        name=completed_name,
        purpose=find_scalar(selected_block, "purpose") or "",
        item_type=find_scalar(selected_block, "type") or "",
        stream=primary_stream,
        source=primary_path,
    )


def validate_roadmap_structure_with_completed_track(root: Path, main_roadmap: Path) -> None:
    try:
        validate_roadmap_structure(root, main_roadmap)
    except RuntimeError as exc:
        if "No roadmap item with status: next found for primary stream" not in str(exc):
            raise
        _completed_primary_item(root, main_roadmap)


def resolve_primary_roadmap_focus(root: Path, main_roadmap: Path) -> RoadmapFocus:
    try:
        return RoadmapFocus(find_unique_next_roadmap_item(root, main_roadmap), "next")
    except RuntimeError as exc:
        if "No roadmap item with status: next found for primary stream" not in str(exc):
            raise
        return RoadmapFocus(_completed_primary_item(root, main_roadmap), "completed")
