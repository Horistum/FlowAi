#!/usr/bin/env python3
"""Compile and test generic adapter contracts, materialization and evidence after
physically removing all concrete adapters, reference composition and root code.

The original Gradle build, owned production sources and actual tests are copied
byte-for-byte. No class outputs, replacement implementations or build cache are
allowed. Kernel/compiler deletion proofs and per-adapter external compiler probes
cover the other directions independently.
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
    "flow-frontends": Path("gradle/frontends-sources.txt"),
    "flow-adapter-contracts": Path("gradle/adapter-contracts-sources.txt"),
    "flow-adapter-runtime": Path("gradle/adapter-runtime-sources.txt"),
    "flow-adapter-evidence": Path("gradle/adapter-evidence-sources.txt"),
}
TEST_MODULES = {"flow-adapter-contracts", "flow-adapter-runtime", "flow-adapter-evidence"}
BUILD_INPUTS = (
    Path("build.gradle.kts"), Path("settings.gradle.kts"), Path("gradle.properties"),
    Path("gradlew"), Path("gradle/wrapper/gradle-wrapper.jar"),
    Path("gradle/wrapper/gradle-wrapper.properties"),
    Path("gradle/production-source-ownership.gradle.kts"),
    Path("gradle/production-module.gradle.kts"),
    Path("src/main/resources/standard/compatibility/capability-aliases.yaml"),
) + tuple(MODULES.values()) + tuple(Path(module) / "build.gradle.kts" for module in MODULES)


def production_sources(root: Path) -> dict[str, list[Path]]:
    ownership = {}
    seen = set()
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
    for module in sorted(TEST_MODULES):
        tests = sorted(path.relative_to(root) for path in (root / module / "src/test").rglob("*") if path.is_file())
        if not any(path.suffix == ".kt" for path in tests):
            raise ValueError(f"Missing actual test suite: {module}")
        inputs += tests
    # Existing fixtures remain test-scoped. Root and concrete test suites are
    # absent; external probes use production classpaths, never these fixtures.
    support_roots = [Path("test-support/kotlin")] + [Path(module) / "src/testFixtures" for module in MODULES
                                                               if (root / module / "src/testFixtures").exists()]
    for relative_root in support_roots:
        files = sorted(path.relative_to(root) for path in (root / relative_root).rglob("*") if path.is_file())
        if not any(path.suffix == ".kt" for path in files):
            raise ValueError(f"Missing actual test support: {relative_root}")
        inputs += files
    digests = {}
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
    identities = set()
    for module in MODULES:
        output = report_dir / module
        output.mkdir(parents=True, exist_ok=True)
        reports = sorted((isolated / module / "build/test-results/test").glob("*.xml")) if module in TEST_MODULES else []
        tests = failures = 0
        for report in reports:
            suite = ET.parse(report).getroot()
            cases = suite.findall("testcase")
            if int(suite.attrib.get("tests", "0")) != len(cases):
                raise ValueError(f"Inconsistent JUnit count in {module}/{report.name}")
            tests += len(cases)
            for declared, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                count = sum(case.find(tag) is not None for case in cases)
                if int(suite.attrib.get(declared, "0")) != count:
                    raise ValueError(f"Inconsistent JUnit {declared} in {module}/{report.name}")
                failures += count
            for case in cases:
                identity = (case.attrib.get("classname", ""), case.attrib.get("name", ""))
                if not all(identity) or identity in identities:
                    raise ValueError(f"Missing or duplicate JUnit identity: {identity}")
                identities.add(identity)
            shutil.copy2(report, output / report.name)
        boundary = "semantic-kernel-boundary" if module == "flow-semantic-kernel" else "production-module-boundary"
        classpath = isolated / module / "build/reports" / boundary / "classpath.txt"
        if classpath.is_file():
            shutil.copy2(classpath, output / "classpath.txt")
        results[module] = {"testsRequired": module in TEST_MODULES, "tests": tests,
                           "failuresErrorsOrSkipped": failures, "classpathRecorded": classpath.is_file(),
                           "reports": [path.name for path in reports]}
    return results


def verify(root: Path, report_dir: Path, offline: bool) -> None:
    initialize_report(report_dir)
    command = ["bash", "gradlew", "--no-daemon", "--console=plain", "--no-build-cache",
               "-Pflow.isolatedBoundary=adapter-evidence"]
    if offline:
        command.append("--offline")
    command += [f":{module}:clean" for module in MODULES]
    command += [f":{module}:test" for module in MODULES if module in TEST_MODULES]
    with tempfile.TemporaryDirectory(prefix="flow-adapter-isolation-") as temporary:
        isolated = Path(temporary)
        inputs = prepare_isolated_project(root, isolated)
        ownership = production_sources(root)
        print(f"Generic adapter build: {sum(map(len, ownership.values()))} actual sources; "
              "no concrete adapters, reference distribution, CLI or conformance.", flush=True)
        result = subprocess.run(command, cwd=isolated, check=False, timeout=900)
        modules = collect_results(isolated, report_dir)
        passed = result.returncode == 0 and all(
            (not item["testsRequired"] or item["tests"] > 0) and item["failuresErrorsOrSkipped"] == 0
            and item["classpathRecorded"] for item in modules.values())
        proof = {"status": "passed" if passed else "failed", "command": command,
                 "gradleExitCode": result.returncode, "modules": modules,
                 "productionSourceCounts": {module: len(paths) for module, paths in ownership.items()},
                 "excludedProductionSourcesPresent": False, "inputsSha256": inputs}
        (report_dir / "proof.json").write_text(json.dumps(proof, indent=2, sort_keys=True) + "\n")
        if not passed:
            raise RuntimeError(f"Adapter isolation failed: exit={result.returncode}, results={modules}.")
        print(f"Isolated adapter proof passed: {sum(item['tests'] for item in modules.values())} distinct tests.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--report-dir", type=Path, default=Path("ci-logs/adapter-isolation"))
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    try:
        verify(args.root.resolve(), args.report_dir.resolve(), args.offline)
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError, ET.ParseError) as error:
        parser.exit(1, f"Adapter isolation proof: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
