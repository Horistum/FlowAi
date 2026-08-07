#!/usr/bin/env python3
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path


REQUIRED_ROADMAP_STREAMS = ("core", "adapters", "conformance", "architecture")


@dataclass(frozen=True)
class RoadmapItem:
    version: str
    name: str
    purpose: str
    item_type: str
    stream: str
    source: Path


def read_text(path: Path) -> str:
    if not path.is_file():
        raise FileNotFoundError(f"Missing roadmap file: {path}")
    return path.read_text(encoding="utf-8")


def find_scalar(text: str, key: str) -> str | None:
    pattern = rf"(?m)^\s*{re.escape(key)}:\s*[\"']?([^\"'\n#]+)[\"']?\s*(?:#.*)?$"
    match = re.search(pattern, text)
    return match.group(1).strip() if match else None


def _section_lines(text: str, key: str) -> list[str]:
    lines = text.splitlines()
    section_pattern = re.compile(rf"^(\s*){re.escape(key)}:\s*(?:#.*)?$")

    for index, line in enumerate(lines):
        match = section_pattern.match(line)
        if not match:
            continue
        base_indent = len(match.group(1))
        section: list[str] = []
        for candidate in lines[index + 1 :]:
            if not candidate.strip():
                continue
            indent = len(candidate) - len(candidate.lstrip())
            if indent <= base_indent:
                break
            section.append(candidate)
        return section
    return []


def find_list(text: str, key: str) -> tuple[str, ...]:
    values: list[str] = []
    for line in _section_lines(text, key):
        match = re.match(r"^\s*-\s*[\"']?(.+?)[\"']?\s*(?:#.*)?$", line)
        if match:
            values.append(match.group(1).strip())
    return tuple(values)


def find_mapping(text: str, key: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in _section_lines(text, key):
        match = re.match(
            r"^\s*([A-Za-z][A-Za-z0-9_-]*):\s*[\"']?([^\"'\n#]+)[\"']?\s*(?:#.*)?$",
            line,
        )
        if match:
            values[match.group(1)] = match.group(2).strip()
    return values


def _resolve_repository_path(root: Path, declared_path: str, label: str) -> Path:
    root = root.resolve()
    candidate = (root / declared_path).resolve()
    try:
        candidate.relative_to(root)
    except ValueError as exc:
        raise RuntimeError(f"{label} must stay inside the repository: {declared_path}") from exc
    read_text(candidate)
    return candidate


def roadmap_paths_by_stream(root: Path, main_roadmap: Path) -> dict[str, Path]:
    root = root.resolve()
    main_roadmap = main_roadmap.resolve()
    main_text = read_text(main_roadmap)
    declared = find_mapping(main_text, "activeRoadmaps")

    if declared:
        missing = sorted(set(REQUIRED_ROADMAP_STREAMS) - set(declared))
        extra = sorted(set(declared) - set(REQUIRED_ROADMAP_STREAMS))
        if missing or extra:
            details = []
            if missing:
                details.append(f"missing streams: {', '.join(missing)}")
            if extra:
                details.append(f"unknown streams: {', '.join(extra)}")
            raise RuntimeError("Invalid active roadmap stream index: " + "; ".join(details))

        resolved: dict[str, Path] = {}
        for stream, declared_path in declared.items():
            candidate = _resolve_repository_path(root, declared_path, f"Active {stream} roadmap")
            if candidate == main_roadmap:
                raise RuntimeError("An active roadmap stream must not reference the roadmap index itself.")
            if candidate in resolved.values():
                raise RuntimeError(f"Active roadmap file is declared more than once: {declared_path}")
            resolved[stream] = candidate
        return resolved

    paths = {"core": main_roadmap}
    active_track = find_scalar(main_text, "activeRepairTrack")
    if active_track:
        paths["repair"] = _resolve_repository_path(root, active_track, "Active repair track")
    return paths


def active_roadmap_paths(root: Path, main_roadmap: Path) -> tuple[Path, ...]:
    paths = list(roadmap_paths_by_stream(root, main_roadmap).values())
    correction = active_correction_work_package(root, main_roadmap)
    if correction is not None:
        paths.append(correction)
    return tuple(paths)


def active_correction_work_package(root: Path, main_roadmap: Path) -> Path | None:
    declared = find_scalar(read_text(main_roadmap), "activeCorrectionWorkPackage")
    if not declared:
        return None
    return _resolve_repository_path(root, declared, "Active correction work package")


def roadmap_item_blocks(text: str) -> tuple[str, ...]:
    blocks = re.split(r"(?m)^\s*-\s+version:\s*", text)
    return tuple(blocks[1:])


def _item_version(block: str) -> str:
    match = re.match(r"[\"']?([^\"'\n]+)[\"']?", block)
    return match.group(1).strip() if match else ""


def _dependency_values(block: str, key: str) -> tuple[str, ...]:
    listed = find_list(block, key)
    if listed:
        return listed
    scalar = find_scalar(block, key)
    return (scalar,) if scalar else ()


def next_items_in(path: Path, declared_stream: str | None = None) -> list[RoadmapItem]:
    text = read_text(path)
    stream = find_scalar(text, "stream") or declared_stream or "core"
    items: list[RoadmapItem] = []

    for block in roadmap_item_blocks(text):
        if find_scalar(block, "status") != "next":
            continue
        items.append(
            RoadmapItem(
                version=_item_version(block),
                name=find_scalar(block, "name") or "",
                purpose=find_scalar(block, "purpose") or "",
                item_type=find_scalar(block, "type") or "",
                stream=stream,
                source=path,
            )
        )
    return items


def correction_item(path: Path) -> RoadmapItem:
    text = read_text(path)
    status = find_scalar(text, "status")
    if status != "active":
        raise RuntimeError(f"Active correction work package must declare status: active: {path}")
    version = find_scalar(text, "version") or ""
    name = find_scalar(text, "name") or ""
    purpose = find_scalar(text, "objective") or find_scalar(text, "purpose") or ""
    if not version or not name or not purpose:
        raise RuntimeError(f"Active correction work package is missing version, name or objective: {path}")
    return RoadmapItem(
        version=version,
        name=name,
        purpose=purpose,
        item_type=find_scalar(text, "type") or "bounded-correction",
        stream="correction",
        source=path,
    )


def next_items_by_stream(root: Path, main_roadmap: Path) -> dict[str, RoadmapItem]:
    items_by_stream: dict[str, RoadmapItem] = {}
    for declared_stream, path in roadmap_paths_by_stream(root, main_roadmap).items():
        items = next_items_in(path, declared_stream)
        if len(items) > 1:
            details = ", ".join(item.version for item in items)
            raise RuntimeError(f"Multiple roadmap items have status: next in {declared_stream}: {details}")
        if items:
            items_by_stream[declared_stream] = items[0]
    return items_by_stream


def find_unique_next_roadmap_item(root: Path, main_roadmap: Path) -> RoadmapItem:
    main_text = read_text(main_roadmap)
    primary_stream = find_scalar(main_text, "primaryRoadmapStream") or "core"
    items_by_stream = next_items_by_stream(root, main_roadmap)
    primary_item = items_by_stream.get(primary_stream)
    correction_path = active_correction_work_package(root, main_roadmap)

    if correction_path is not None:
        if primary_item is not None:
            raise RuntimeError(
                "An active correction work package and a primary roadmap status: next item must not coexist."
            )
        return correction_item(correction_path)

    if primary_item is None:
        checked = roadmap_paths_by_stream(root, main_roadmap).get(primary_stream)
        location = str(checked.relative_to(root.resolve())) if checked else primary_stream
        raise RuntimeError(
            f"No roadmap item with status: next found for primary stream {primary_stream}: {location}"
        )
    return primary_item


def _roadmap_versions(path: Path) -> set[str]:
    return {_item_version(block) for block in roadmap_item_blocks(read_text(path)) if _item_version(block)}


def _validate_core_item_contract(core_path: Path) -> None:
    text = read_text(core_path)
    if find_scalar(text, "stream") != "core":
        raise RuntimeError(f"Core roadmap must declare stream: core: {core_path}")

    versions: set[str] = set()
    for block in roadmap_item_blocks(text):
        version = _item_version(block)
        if not version:
            raise RuntimeError(f"Core roadmap contains an item without a version: {core_path}")
        if version in versions:
            raise RuntimeError(f"Duplicate Core roadmap version: {version}")
        versions.add(version)

        missing_fields = []
        if not find_scalar(block, "universalInvariant"):
            missing_fields.append("universalInvariant")
        if not find_list(block, "forbiddenScope"):
            missing_fields.append("forbiddenScope")
        if not find_list(block, "completionEvidence"):
            missing_fields.append("completionEvidence")
        if missing_fields:
            raise RuntimeError(
                f"Core roadmap item {version} is missing required fields: {', '.join(missing_fields)}"
            )

        forbidden_dependencies = {
            "dependsOnAdapters": _dependency_values(block, "dependsOnAdapters"),
            "dependsOnConformance": _dependency_values(block, "dependsOnConformance"),
            "dependsOnArchitecture": _dependency_values(block, "dependsOnArchitecture"),
        }
        reversed_dependencies = {
            key: values for key, values in forbidden_dependencies.items() if values
        }
        if reversed_dependencies:
            details = ", ".join(
                f"{key}={','.join(values)}" for key, values in reversed_dependencies.items()
            )
            raise RuntimeError(
                f"Core roadmap item {version} reverses roadmap ownership direction: {details}"
            )


def _validate_cross_stream_references(paths: dict[str, Path]) -> None:
    versions_by_stream = {
        stream: _roadmap_versions(path)
        for stream, path in paths.items()
    }
    dependency_keys = {
        "core": "dependsOnCore",
        "adapters": "dependsOnAdapters",
        "conformance": "dependsOnConformance",
        "architecture": "dependsOnArchitecture",
    }
    allowed_dependencies = {
        "core": {"core"},
        "adapters": {"core", "adapters"},
        "conformance": {"core", "adapters", "conformance"},
        "architecture": {"core", "adapters", "conformance", "architecture"},
    }
    display_names = {
        "core": "Core",
        "adapters": "adapter",
        "conformance": "conformance",
        "architecture": "architecture",
    }

    for stream, path in paths.items():
        for block in roadmap_item_blocks(read_text(path)):
            version = _item_version(block)
            for dependency_stream, key in dependency_keys.items():
                dependencies = _dependency_values(block, key)
                if not dependencies:
                    continue
                if dependency_stream not in allowed_dependencies[stream]:
                    raise RuntimeError(
                        f"{stream} roadmap item {version} reverses roadmap ownership direction: "
                        f"{key}={','.join(dependencies)}"
                    )
                known_versions = versions_by_stream[dependency_stream]
                for dependency in dependencies:
                    if dependency not in known_versions:
                        raise RuntimeError(
                            f"{stream} roadmap item {version} references unknown "
                            f"{display_names[dependency_stream]} item: {dependency}"
                        )


def _validate_active_correction(root: Path, main_roadmap: Path, paths: dict[str, Path]) -> None:
    correction_path = active_correction_work_package(root, main_roadmap)
    if correction_path is None:
        return
    correction = correction_item(correction_path)
    text = read_text(correction_path)
    parent = find_scalar(text, "parentCoreItem")
    if parent not in _roadmap_versions(paths["core"]):
        raise RuntimeError(
            f"Active correction {correction.version} references unknown parent Core item: {parent or 'missing'}"
        )
    primary_stream = find_scalar(read_text(main_roadmap), "primaryRoadmapStream") or "core"
    primary_next = next_items_in(paths[primary_stream], primary_stream)
    if primary_next:
        raise RuntimeError(
            "The primary roadmap item must remain blocked while an active correction work package exists."
        )


def validate_roadmap_structure(root: Path, main_roadmap: Path) -> None:
    root = root.resolve()
    main_roadmap = main_roadmap.resolve()
    main_text = read_text(main_roadmap)
    paths = roadmap_paths_by_stream(root, main_roadmap)

    for historical_path in find_list(main_text, "historicalRoadmaps"):
        candidate = _resolve_repository_path(root, historical_path, "Historical roadmap")
        if candidate == main_roadmap or candidate in paths.values():
            raise RuntimeError(f"Historical roadmap must not duplicate an active roadmap: {historical_path}")

    if find_mapping(main_text, "activeRoadmaps"):
        if roadmap_item_blocks(main_text):
            raise RuntimeError("The roadmap index must not contain active version items.")
        primary_stream = find_scalar(main_text, "primaryRoadmapStream")
        if primary_stream not in REQUIRED_ROADMAP_STREAMS:
            raise RuntimeError("primaryRoadmapStream must name one declared active roadmap stream.")

        for declared_stream, path in paths.items():
            actual_stream = find_scalar(read_text(path), "stream")
            if actual_stream != declared_stream:
                raise RuntimeError(
                    f"Roadmap stream mismatch for {path.relative_to(root)}: "
                    f"declared {declared_stream}, file says {actual_stream or 'missing'}"
                )

        _validate_core_item_contract(paths["core"])
        _validate_cross_stream_references(paths)
        _validate_active_correction(root, main_roadmap, paths)

    next_items_by_stream(root, main_roadmap)
    find_unique_next_roadmap_item(root, main_roadmap)
