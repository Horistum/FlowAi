#!/usr/bin/env python3
"""Compile the real kernel build after physically omitting all residual product sources.

This is a build proof, not an import scanner. The copied Gradle files and kernel
sources are the production inputs; no substitute build, stubs or friend paths
are injected. The caller's Gradle dependency cache may be reused, but no project
outputs or product class files are copied.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

MANIFEST = Path("gradle/semantic-kernel-sources.txt")
MODULE = Path("flow-semantic-kernel")
SOURCE_ROOT = Path("src/main/kotlin")
BUILD_INPUTS = (
    Path("build.gradle.kts"), Path("settings.gradle.kts"), Path("gradle.properties"),
    Path("gradlew"), Path("gradle/wrapper/gradle-wrapper.jar"),
    Path("gradle/wrapper/gradle-wrapper.properties"), MANIFEST,
    MODULE / "build.gradle.kts", Path("gradle/production-source-ownership.gradle.kts"),
)


def regular_source(root: Path, relative: Path) -> Path:
    source = root / relative
    if source.is_symlink() or not source.is_file() or not source.resolve().is_relative_to(root.resolve()):
        raise ValueError(f"Missing, symbolic or escaping build input: {relative}")
    return source


def kernel_sources(root: Path) -> list[Path]:
    entries = [line.strip() for line in regular_source(root, MANIFEST).read_text().splitlines()
               if line.strip() and not line.lstrip().startswith("#")]
    if not entries or entries != sorted(set(entries)):
        raise ValueError("Kernel ownership must be non-empty, unique and sorted.")
    paths: list[Path] = []
    for entry in entries:
        if not re.fullmatch(r"org/flowlang/[A-Za-z0-9_/]+\.kt", entry):
            raise ValueError(f"Kernel ownership must contain explicit relative Kotlin files: {entry}")
        relative = SOURCE_ROOT / entry
        source = regular_source(root, relative)
        if not source.resolve().is_relative_to((root / SOURCE_ROOT).resolve()):
            raise ValueError(f"Kernel source escapes its source root: {entry}")
        paths.append(relative)
    return paths


def prepare_isolated_project(root: Path, destination: Path) -> dict[str, str]:
    """Copy the production build and its exact kernel-only inputs, returning digests."""
    if destination.exists() and any(destination.iterdir()):
        raise ValueError("Isolation destination must be empty.")
    sources = kernel_sources(root)
    test_root = root / MODULE / "src/test"
    tests = sorted(path.relative_to(root) for path in test_root.rglob("*") if path.is_file())
    if not any(path.suffix == ".kt" for path in tests):
        raise ValueError("The real kernel test suite is missing.")
    inputs = list(BUILD_INPUTS) + sources + tests
    digests: dict[str, str] = {}
    for relative in inputs:
        source = regular_source(root, relative)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        digests[relative.as_posix()] = hashlib.sha256(target.read_bytes()).hexdigest()
    observed = sorted(path.relative_to(destination) for path in (destination / SOURCE_ROOT).rglob("*.kt"))
    if observed != sorted(sources):
        raise ValueError("Isolation copied unowned production sources.")
    return digests


def initialize_report(report_dir: Path) -> None:
    # Never recursively delete a caller-selected directory. Invalidate any old
    # success before preparing inputs so an interrupted proof fails closed.
    report_dir.mkdir(parents=True, exist_ok=True)
    (report_dir / "proof.json").write_text(json.dumps({
        "status": "failed", "reason": "Current isolated build has not completed."
    }, indent=2) + "\n")


def verify(root: Path, report_dir: Path, offline: bool) -> None:
    initialize_report(report_dir)
    command = ["bash", "gradlew", "--no-daemon", "--console=plain", "--no-build-cache"]
    if offline:
        command.append("--offline")
    command += ["-Pflow.isolatedBoundary=semantic-kernel", ":flow-semantic-kernel:clean", ":flow-semantic-kernel:test"]
    with tempfile.TemporaryDirectory(prefix="flow-kernel-isolation-") as temporary:
        isolated = Path(temporary)
        inputs = prepare_isolated_project(root, isolated)
        print(f"Kernel-only production build: {len(kernel_sources(root))} sources; no residual implementation.", flush=True)
        # Inherit stdout/stderr so CI captures compiler diagnostics even on failure.
        result = subprocess.run(command, cwd=isolated, check=False, timeout=600)
        module = isolated / MODULE
        reports = sorted((module / "build/test-results/test").glob("*.xml"))
        test_count = 0
        failures = 0
        for report in reports:
            suite = ET.parse(report).getroot()
            test_count += int(suite.attrib.get("tests", "0"))
            failures += sum(int(suite.attrib.get(key, "0")) for key in ("failures", "errors", "skipped"))
            shutil.copy2(report, report_dir / report.name)
        classpath = module / "build/reports/semantic-kernel-boundary/classpath.txt"
        if classpath.is_file():
            shutil.copy2(classpath, report_dir / "classpath.txt")
        passed = result.returncode == 0 and test_count > 0 and failures == 0 and classpath.is_file()
        (report_dir / "proof.json").write_text(json.dumps({
            "status": "passed" if passed else "failed", "command": command,
            "productionSourceCount": len(kernel_sources(root)), "tests": test_count,
            "failuresErrorsOrSkipped": failures, "inputsSha256": inputs,
            "residualProductSourcesPresent": False, "testReportFiles": [report.name for report in reports],
        }, indent=2, sort_keys=True) + "\n")
        if not passed:
            raise RuntimeError(f"Isolated kernel proof failed: exit={result.returncode}, tests={test_count}, failures={failures}.")
        print(f"Isolated kernel proof passed: {test_count} tests; real production build without adapters or compiler orchestration.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--report-dir", type=Path, default=Path("ci-logs/kernel-isolation"))
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    try:
        verify(args.root.resolve(), args.report_dir.resolve(), args.offline)
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Kernel isolation proof: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
