#!/usr/bin/env python3
"""Build the actual compiler, contracts and kernel without any frontend or adapter.

The source partition and build logic are copied byte-for-byte. The isolation
property selects only these production projects and rejects residual source
roots. No substitute build, class outputs, stubs or friend-paths are injected.
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

from verify_semantic_kernel_isolation import initialize_report, regular_source

SOURCE_ROOT = Path("src/main/kotlin")
MODULES = {
    "flow-semantic-kernel": Path("gradle/semantic-kernel-sources.txt"),
    "flow-module-contracts": Path("gradle/module-contracts-sources.txt"),
    "flow-compiler": Path("gradle/compiler-sources.txt"),
}
# The kernel's full standalone suite has its own physical-isolation proof.
# Build its actual JAR here, but do not repeat that suite a third time in CI.
TEST_MODULES = {"flow-module-contracts", "flow-compiler"}
BUILD_INPUTS = (
    Path("build.gradle.kts"), Path("settings.gradle.kts"), Path("gradle.properties"),
    Path("gradlew"), Path("gradle/wrapper/gradle-wrapper.jar"),
    Path("gradle/wrapper/gradle-wrapper.properties"),
    Path("gradle/production-source-ownership.gradle.kts"),
    Path("gradle/production-module.gradle.kts"),
) + tuple(MODULES.values()) + tuple(Path(module) / "build.gradle.kts" for module in MODULES)


def production_sources(root: Path) -> dict[str, list[Path]]:
    ownership: dict[str, list[Path]] = {}
    seen: set[Path] = set()
    for module, manifest in MODULES.items():
        entries = [line.strip() for line in regular_source(root, manifest).read_text().splitlines()
                   if line.strip() and not line.lstrip().startswith("#")]
        if not entries or entries != sorted(set(entries)):
            raise ValueError(f"{module}: ownership must be non-empty, unique and sorted.")
        sources = []
        for entry in entries:
            if not re.fullmatch(r"org/flowlang/[A-Za-z0-9_/]+\.kt", entry):
                raise ValueError(f"{module}: ownership requires exact relative Kotlin paths: {entry}")
            relative = SOURCE_ROOT / entry
            regular_source(root, relative)
            if relative in seen:
                raise ValueError(f"Overlapping production ownership: {relative}")
            seen.add(relative)
            sources.append(relative)
        ownership[module] = sources
    return ownership


def prepare_isolated_project(root: Path, destination: Path) -> dict[str, str]:
    if destination.exists() and any(destination.iterdir()):
        raise ValueError("Isolation destination must be empty.")
    ownership = production_sources(root)
    inputs = list(BUILD_INPUTS) + [path for paths in ownership.values() for path in paths]
    for module in MODULES:
        tests = sorted(path.relative_to(root) for path in (root / module / "src/test").rglob("*") if path.is_file())
        if not any(path.suffix == ".kt" for path in tests):
            raise ValueError(f"Missing actual test suite: {module}")
        inputs += tests
    # Only test-scoped fixtures owned by the compiler are available in isolation.
    # Frontend and root test suites/fixtures are intentionally absent.
    for relative_root in (Path("flow-compiler/src/testFixtures"), Path("test-support/kotlin")):
        files = sorted(path.relative_to(root) for path in (root / relative_root).rglob("*") if path.is_file())
        if not any(path.suffix == ".kt" for path in files):
            raise ValueError(f"Missing actual test support: {relative_root}")
        inputs += files
    digests: dict[str, str] = {}
    for relative in inputs:
        source = regular_source(root, relative)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        digests[relative.as_posix()] = hashlib.sha256(target.read_bytes()).hexdigest()
    observed = {path.relative_to(destination) for path in (destination / SOURCE_ROOT).rglob("*.kt")}
    expected = {path for paths in ownership.values() for path in paths}
    if observed != expected:
        raise ValueError("Isolated production sources do not match the exact module partition.")
    return digests


def collect_results(isolated: Path, report_dir: Path) -> dict:
    results = {}
    all_identities = set()
    for module in MODULES:
        output = report_dir / module
        output.mkdir(parents=True, exist_ok=True)
        reports = sorted((isolated / module / "build/test-results/test").glob("*.xml")) if module in TEST_MODULES else []
        tests = failures = 0
        for report in reports:
            suite = ET.parse(report).getroot()
            cases = suite.findall("testcase")
            declared = int(suite.attrib.get("tests", "0"))
            if declared != len(cases):
                raise ValueError(f"Inconsistent JUnit count in {module}/{report.name}")
            tests += len(cases)
            case_counts = {key: sum(case.find(key) is not None for case in cases)
                           for key in ("failure", "error", "skipped")}
            for declared_key, case_key in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                if int(suite.attrib.get(declared_key, "0")) != case_counts[case_key]:
                    raise ValueError(f"Inconsistent JUnit {declared_key} in {module}/{report.name}")
            for case in cases:
                identity = (case.attrib.get("classname", ""), case.attrib.get("name", ""))
                if not all(identity) or identity in all_identities:
                    raise ValueError(f"Missing or duplicate JUnit identity: {identity}")
                all_identities.add(identity)
                failures += sum(case.find(key) is not None for key in ("failure", "error", "skipped"))
            shutil.copy2(report, output / report.name)
        boundary = "semantic-kernel-boundary" if module == "flow-semantic-kernel" else "production-module-boundary"
        classpath = isolated / module / "build/reports" / boundary / "classpath.txt"
        if classpath.is_file():
            shutil.copy2(classpath, output / "classpath.txt")
        results[module] = {"testsRequired": module in TEST_MODULES, "tests": tests, "failuresErrorsOrSkipped": failures,
                           "classpathRecorded": classpath.is_file(), "reports": [p.name for p in reports]}
    return results


def verify(root: Path, report_dir: Path, offline: bool) -> None:
    initialize_report(report_dir)
    command = ["bash", "gradlew", "--no-daemon", "--console=plain", "--no-build-cache",
               "-Pflow.isolatedBoundary=compiler"]
    if offline:
        command.append("--offline")
    command += [f":{module}:clean" for module in MODULES]
    command += [f":{module}:test" for module in MODULES if module in TEST_MODULES]
    with tempfile.TemporaryDirectory(prefix="flow-compiler-isolation-") as temporary:
        isolated = Path(temporary)
        inputs = prepare_isolated_project(root, isolated)
        ownership = production_sources(root)
        print(f"Compiler-only production build: {sum(map(len, ownership.values()))} sources; "
              "no frontends, concrete adapters, serialization or conformance sources.", flush=True)
        result = subprocess.run(command, cwd=isolated, check=False, timeout=600)
        modules = collect_results(isolated, report_dir)
        passed = result.returncode == 0 and all(
            (not item["testsRequired"] or item["tests"] > 0) and item["failuresErrorsOrSkipped"] == 0 and item["classpathRecorded"]
            for item in modules.values())
        proof = {"status": "passed" if passed else "failed", "command": command,
                 "gradleExitCode": result.returncode, "modules": modules,
                 "productionSourceCounts": {module: len(paths) for module, paths in ownership.items()},
                 "excludedProductionSourcesPresent": False, "inputsSha256": inputs}
        (report_dir / "proof.json").write_text(json.dumps(proof, indent=2, sort_keys=True) + "\n")
        if not passed:
            raise RuntimeError(f"Compiler isolation failed: exit={result.returncode}, results={modules}.")
        print(f"Isolated compiler proof passed: {sum(item['tests'] for item in modules.values())} distinct tests.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--report-dir", type=Path, default=Path("ci-logs/compiler-isolation"))
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    try:
        verify(args.root.resolve(), args.report_dir.resolve(), args.offline)
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError, ET.ParseError) as error:
        parser.exit(1, f"Compiler isolation proof: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
