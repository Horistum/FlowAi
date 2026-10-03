#!/usr/bin/env python3
"""Build, test and install the actual product with no verification sources.

This complements kernel/compiler/adapter deletion proofs. It uses the original
Gradle inputs and fresh outputs, not a substitute build or source-name scan.
Only the two newly extracted product suites execute here; the complete suite
still executes independently in both required CI jobs.
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
import zipfile

from verify_semantic_kernel_isolation import initialize_report, regular_source
from verify_relocated_product import RESOURCE_INVENTORY, RESOURCE_INPUTS, resource_paths, verify_relocated_product

SOURCE_ROOT = Path("src/main/kotlin")
MODULES = {
    "flow-semantic-kernel": Path("gradle/semantic-kernel-sources.txt"),
    "flow-module-contracts": Path("gradle/module-contracts-sources.txt"),
    "flow-compiler": Path("gradle/compiler-sources.txt"),
    "flow-frontends": Path("gradle/frontends-sources.txt"),
    "flow-adapter-contracts": Path("gradle/adapter-contracts-sources.txt"),
    "flow-adapter-runtime": Path("gradle/adapter-runtime-sources.txt"),
    "flow-adapter-evidence": Path("gradle/adapter-evidence-sources.txt"),
    "flow-adapter-jenkins": Path("gradle/adapter-jenkins-sources.txt"),
    "flow-adapter-github-actions": Path("gradle/adapter-github-actions-sources.txt"),
    "flow-adapter-tekton": Path("gradle/adapter-tekton-sources.txt"),
    "flow-standard-artifacts": Path("gradle/standard-artifacts-sources.txt"),
    "flow-reference-distribution": Path("gradle/reference-distribution-sources.txt"),
    "flow-cli": Path("gradle/cli-sources.txt"),
}
TEST_MODULES = ("flow-standard-artifacts", "flow-cli")
BUILD_INPUTS = (
    Path("build.gradle.kts"), Path("settings.gradle.kts"), Path("gradle.properties"),
    Path("gradlew"), Path("gradle/wrapper/gradle-wrapper.jar"),
    Path("gradle/wrapper/gradle-wrapper.properties"),
    Path("gradle/production-source-ownership.gradle.kts"),
    Path("gradle/production-module.gradle.kts"), Path("gradle/cli-application.gradle.kts"),
    Path("src/main/resources/standard/compatibility/capability-aliases.yaml"), RESOURCE_INVENTORY,
) + tuple(MODULES.values()) + tuple(Path(module) / "build.gradle.kts" for module in MODULES)
OWNERSHIP_REPORT = Path("build/reports/module-ownership/source-ownership.json")
INSTALL = Path("flow-cli/build/install/flow-core")
FORBIDDEN_CLASS_PREFIXES = (
    "org/flowlang/conformance/", "org/flowlang/verification/", "org/flowlang/release/",
    "org/flowlang/architecture/", "org/flowlang/roadmap/",
)
VERIFICATION_COMMANDS = {"conformance", "reference-snapshot", "release-profile", "standard-draft", "standard-export"}


def production_sources(root: Path) -> dict[str, list[Path]]:
    owners = {}
    seen = set()
    for module, manifest in MODULES.items():
        entries = [line.strip() for line in regular_source(root, manifest).read_text().splitlines()
                   if line.strip() and not line.lstrip().startswith("#")]
        if not entries or entries != sorted(set(entries)):
            raise ValueError(f"{module}: ownership must be non-empty, unique and sorted.")
        paths = []
        for entry in entries:
            if not re.fullmatch(r"org/flowlang/[A-Za-z0-9_/]+\.kt", entry):
                raise ValueError(f"{module}: ownership requires exact relative Kotlin paths: {entry}")
            relative = SOURCE_ROOT / entry
            source = regular_source(root, relative)
            if not source.resolve().is_relative_to((root / SOURCE_ROOT).resolve()):
                raise ValueError(f"Source escapes production root: {relative}")
            if relative in seen:
                raise ValueError(f"Overlapping production ownership: {relative}")
            seen.add(relative)
            paths.append(relative)
        owners[module] = paths
    return owners


def prepare_isolated_project(root: Path, destination: Path) -> dict[str, str]:
    if destination.exists() and any(destination.iterdir()):
        raise ValueError("Isolation destination must be empty.")
    ownership = production_sources(root)
    inputs = list(BUILD_INPUTS) + [path for paths in ownership.values() for path in paths]
    for test_root in [Path(module) / "src/test" for module in TEST_MODULES] + [Path("test-support/kotlin")]:
        tests = sorted(path.relative_to(root) for path in (root / test_root).rglob("*") if path.is_file())
        if not any(path.suffix == ".kt" for path in tests):
            raise ValueError(f"Missing actual test suite or test support: {test_root}")
        inputs += tests
    # Witness source text is staged outside all compilation/test roots. No kit,
    # root integration suites, fixtures, .git, class outputs or cached results.
    digests = {}
    for relative in inputs:
        source = regular_source(root, relative)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        digests[relative.as_posix()] = hashlib.sha256(target.read_bytes()).hexdigest()
    for relative in resource_paths(root):
        source = regular_source(root, relative)
        target = destination / RESOURCE_INPUTS / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        digests[relative.as_posix()] = hashlib.sha256(target.read_bytes()).hexdigest()
    observed = {path.relative_to(destination) for path in (destination / SOURCE_ROOT).rglob("*.kt")}
    if observed != {path for paths in ownership.values() for path in paths}:
        raise ValueError("Isolated source tree differs from the declared product partition.")
    return digests


def collect_results(isolated: Path, report_dir: Path, ownership: dict[str, list[Path]]) -> dict:
    results = {}
    identities = set()
    for module in MODULES:
        output = report_dir / module
        output.mkdir(parents=True, exist_ok=True)
        reports = sorted((isolated / module / "build/test-results/test").glob("*.xml")) if module in TEST_MODULES else []
        tests = failures = 0
        for report in reports:
            suite = ET.parse(report).getroot()
            if suite.tag != "testsuite":
                raise ValueError(f"Unexpected JUnit document in {report}")
            cases = suite.findall("testcase")
            if int(suite.attrib.get("tests", "0")) != len(cases):
                raise ValueError(f"Inconsistent JUnit count in {report}")
            tests += len(cases)
            for declared, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                count = sum(case.find(tag) is not None for case in cases)
                if int(suite.attrib.get(declared, "0")) != count:
                    raise ValueError(f"Inconsistent JUnit {declared} in {report}")
                failures += count
            for case in cases:
                identity = (case.attrib.get("classname", ""), case.attrib.get("name", ""))
                if not all(identity) or identity in identities:
                    raise ValueError(f"Missing or duplicate JUnit identity: {identity}")
                identities.add(identity)
            shutil.copy2(report, output / report.name)
        boundary = "semantic-kernel-boundary" if module == "flow-semantic-kernel" else "production-module-boundary"
        classpath = isolated / module / "build/reports" / boundary / "classpath.txt"
        if not classpath.is_file() or not classpath.read_text().strip():
            raise ValueError(f"Missing current classpath evidence: {module}")
        if "flow-conformance-kit" in classpath.read_text() or "test-fixtures" in classpath.read_text():
            raise ValueError(f"Verification dependency in product evidence: {module}")
        shutil.copy2(classpath, output / "classpath.txt")
        results[module] = {"testsRequired": module in TEST_MODULES, "tests": tests,
                           "failuresErrorsOrSkipped": failures, "classpathRecorded": True,
                           "reports": [path.name for path in reports]}
    ownership_file = regular_source(isolated, OWNERSHIP_REPORT)
    document = json.loads(ownership_file.read_text())
    expected = {":" + module: {"role": "product", "sources": [path.relative_to(SOURCE_ROOT).as_posix() for path in paths]}
                for module, paths in ownership.items()}
    expected[":"] = {"role": "aggregate", "sources": []}
    if document != {"version": 1, "completePartition": True, "modules": expected}:
        raise ValueError("Actual Gradle ownership report does not match the exact product-only partition.")
    shutil.copy2(ownership_file, report_dir / "source-ownership.json")
    return results


def inspect_distribution(isolated: Path) -> dict:
    jars = sorted((isolated / INSTALL / "lib").glob("*.jar"))
    if not jars:
        raise ValueError("Missing installed product JARs.")
    flow_jars = [jar for jar in jars if jar.name.startswith("flow-")]
    if len(flow_jars) != len(MODULES) or any(
        sum(bool(re.fullmatch(re.escape(module) + r"-\d.*\.jar", jar.name)) for jar in flow_jars) != 1
        for module in MODULES
    ):
        raise ValueError("Product distribution must contain exactly one JAR for each product module, and no kit.")
    identities = set()
    digests = {}
    for jar in jars:
        if "test-fixtures" in jar.name or "conformance-kit" in jar.name or "jsonSchema" in jar.name:
            raise ValueError(f"Verification artifact leaked into product distribution: {jar.name}")
        with zipfile.ZipFile(jar) as archive:
            if archive.testzip() is not None:
                raise ValueError(f"Corrupt distribution JAR: {jar.name}")
            classes = [name for name in archive.namelist() if name.startswith("org/flowlang/") and name.endswith(".class")]
            for name in classes:
                if name.startswith(FORBIDDEN_CLASS_PREFIXES) or "/fixtures/" in name or "/testfixtures/" in name.lower():
                    raise ValueError(f"Verification class leaked into product distribution: {name}")
                if name in identities:
                    raise ValueError(f"Duplicate product class: {name}")
                identities.add(name)
        digests[jar.name] = hashlib.sha256(jar.read_bytes()).hexdigest()
    if not identities:
        raise ValueError("Installed product contains no Flow classes.")
    return {"jarsSha256": digests, "uniqueFlowClasses": len(identities), "projectJarCount": len(flow_jars)}


def smoke_product(isolated: Path, report_dir: Path) -> dict:
    launcher = regular_source(isolated, INSTALL / "bin/flow-core")
    runs = {}
    # Diagnostics and help are repository-independent product operations. This
    # does not claim that all repository-bound commands satisfy the later AR-05.
    with tempfile.TemporaryDirectory(prefix="flow-installed-product-cwd-") as empty:
        for name, arguments in (("diagnostics", ["diagnostics"]), ("help", []), ("reject-verification", ["conformance"])):
            completed = subprocess.run(["bash", str(launcher), *arguments], cwd=empty,
                                       capture_output=True, text=True, check=False, timeout=60)
            (report_dir / f"{name}.stdout").write_text(completed.stdout)
            (report_dir / f"{name}.stderr").write_text(completed.stderr)
            runs[name] = {"exitCode": completed.returncode,
                          "stdoutSha256": hashlib.sha256(completed.stdout.encode()).hexdigest(),
                          "stderrSha256": hashlib.sha256(completed.stderr.encode()).hexdigest()}
            if name == "diagnostics":
                title, _, payload = completed.stdout.partition("\n")
                if completed.returncode or completed.stderr or title != "===== FLOW STANDARD DIAGNOSTIC CATALOG =====":
                    raise ValueError("Installed diagnostics failed outside the source checkout.")
                document = json.loads(payload)
                if not isinstance(document.get("codes"), list) or not document["codes"]:
                    raise ValueError("Installed diagnostics produced no catalog.")
            elif name == "help":
                if (completed.returncode or completed.stderr or not completed.stdout.startswith("Flow CLI commands: ")
                        or not all(command in completed.stdout for command in ("diagnostics", "intent", "standard-verify"))
                        or any(command in completed.stdout for command in VERIFICATION_COMMANDS)):
                    raise ValueError("Product help is not a truthful product-only command surface.")
            elif completed.returncode != 2 or completed.stderr or "CLI_UNKNOWN_COMMAND" not in completed.stdout:
                raise ValueError("Product silently accepted an unavailable verification command.")
    return runs


def verify(root: Path, report_dir: Path, offline: bool) -> None:
    initialize_report(report_dir)
    command = ["bash", "gradlew", "--no-daemon", "--console=plain", "--no-build-cache",
               "-Pflow.isolatedBoundary=product", f"-Pflow.contractSourceRoot={RESOURCE_INPUTS}"]
    if offline:
        command.append("--offline")
    command += [f":{module}:clean" for module in MODULES]
    command += [f":{module}:test" for module in TEST_MODULES] + [":flow-cli:installDist", ":flow-cli:distZip"]
    with tempfile.TemporaryDirectory(prefix="flow-product-isolation-") as temporary:
        isolated = Path(temporary)
        inputs = prepare_isolated_project(root, isolated)
        ownership = production_sources(root)
        print(f"Product-only build: {sum(map(len, ownership.values()))} actual sources; no verification kit or integration suites.", flush=True)
        result = subprocess.run(command, cwd=isolated, check=False, timeout=900)
        modules = collect_results(isolated, report_dir, ownership)
        passed = result.returncode == 0 and all(
            (not item["testsRequired"] or item["tests"] > 0) and item["failuresErrorsOrSkipped"] == 0
            for item in modules.values())
        # A successful-looking install cannot authorize a failed compilation.
        distribution = inspect_distribution(isolated) if passed else None
        smoke = smoke_product(isolated, report_dir) if passed else None
        relocated = verify_relocated_product(isolated, INSTALL, report_dir) if passed else None
        proof = {"status": "passed" if passed else "failed", "command": command,
                 "gradleExitCode": result.returncode, "modules": modules,
                 "productionSourceCounts": {module: len(paths) for module, paths in ownership.items()},
                 "verificationSourcesPresent": False, "rootIntegrationTestsPresent": False,
                 "sourcePresenceScope": "compilation roots; inventoried source-text witnesses are resource data",
                 "inputsSha256": inputs, "installedProduct": distribution, "installedCli": smoke, "relocatedProduct": relocated}
        (report_dir / "proof.json").write_text(json.dumps(proof, indent=2, sort_keys=True) + "\n")
        if not passed:
            raise RuntimeError(f"Product isolation failed: exit={result.returncode}, results={modules}.")
        print(f"Product isolation passed: {sum(item['tests'] for item in modules.values())} actual tests and installed product checks.", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--report-dir", type=Path, default=Path("ci-logs/product-isolation"))
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    try:
        verify(args.root.resolve(), args.report_dir.resolve(), args.offline)
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError, ET.ParseError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Product isolation proof: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
