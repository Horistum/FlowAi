#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from flow_agent_roadmap import (
    REQUIRED_ROADMAP_STREAMS,
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


@dataclass(frozen=True)
class CompletedItemProjection:
    item_key: str
    name_key: str
    forbidden_next_keys: tuple[str, ...]


COMPLETED_ITEM_PROJECTIONS = {
    "core": CompletedItemProjection(
        item_key="completedItem",
        name_key="completedItemName",
        forbidden_next_keys=("nextCoreItem", "nextCoreItemName", "nextCoreItemStatus"),
    ),
    "adapters": CompletedItemProjection(
        item_key="completedAdapterItem",
        name_key="completedAdapterItemName",
        forbidden_next_keys=("nextItem", "nextItemName", "nextItemStream"),
    ),
    "conformance": CompletedItemProjection(
        item_key="completedConformanceItem",
        name_key="completedConformanceItemName",
        forbidden_next_keys=("nextItem", "nextItemName", "nextItemStream"),
    ),
}


def _item_version(block: str) -> str:
    first_line = block.splitlines()[0].strip()
    return first_line.strip('"\' ')


def _require_closed_core_boundary(main_text: str) -> tuple[str, str]:
    closure_item = find_scalar(main_text, "closureItem") or ""
    closure_name = find_scalar(main_text, "closureItemName") or ""
    closure_status = find_scalar(main_text, "closureItemStatus") or ""
    if closure_status != "completed":
        raise RuntimeError(
            "A completed primary track must preserve closureItemStatus: completed."
        )
    if not closure_item or not closure_name:
        raise RuntimeError(
            "A completed primary track must preserve the closed Core item identity."
        )
    return closure_item, closure_name


def _completed_primary_item(root: Path, main_roadmap: Path) -> RoadmapItem:
    root = root.resolve()
    main_roadmap = main_roadmap.resolve()
    main_text = read_text(main_roadmap)
    primary_stream = find_scalar(main_text, "primaryRoadmapStream") or "core"
    paths = roadmap_paths_by_stream(root, main_roadmap)
    primary_path = paths.get(primary_stream)
    if primary_path is None:
        raise RuntimeError(f"Completed roadmap has no primary stream file: {primary_stream}")

    projection = COMPLETED_ITEM_PROJECTIONS.get(primary_stream)
    if projection is None or primary_stream not in REQUIRED_ROADMAP_STREAMS:
        raise RuntimeError(
            f"Completed roadmap has no completion metadata contract for primary stream: {primary_stream}"
        )

    if active_correction_work_package(root, main_roadmap) is not None:
        raise RuntimeError("A completed primary track must not retain an active correction work package.")

    primary_text = read_text(primary_path)
    if find_scalar(primary_text, "status") != "completed":
        raise RuntimeError("A primary roadmap without a next item must declare track status: completed.")
    if next_items_in(primary_path, primary_stream):
        raise RuntimeError("A completed primary track must not retain a status: next item.")

    closure_item, closure_name = _require_closed_core_boundary(main_text)
    completed_item = find_scalar(main_text, projection.item_key) or ""
    completed_name = find_scalar(main_text, projection.name_key) or ""
    retained_next = {
        key: value
        for key in projection.forbidden_next_keys
        if (value := find_scalar(main_text, key))
    }
    if retained_next:
        details = ", ".join(f"{key}={value}" for key, value in retained_next.items())
        raise RuntimeError(
            f"A completed {primary_stream} track must not retain next-item metadata: {details}"
        )
    if not completed_item or not completed_name:
        raise RuntimeError(
            f"Completed {primary_stream} roadmap metadata must declare "
            f"{projection.item_key} and {projection.name_key}."
        )
    if primary_stream == "core":
        if completed_item != closure_item:
            raise RuntimeError(
                "Completed Core roadmap metadata must identify the same completedItem and closureItem identity."
            )
        if completed_name != closure_name:
            raise RuntimeError(
                "Completed Core roadmap metadata must identify the same completedItemName and closureItemName."
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
        raise RuntimeError(
            f"Completed roadmap item is not declared in the primary {primary_stream} track: {completed_item}"
        )

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
