"""Orchestration and evidence rejection tests. These fixtures do not prove compilation."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
try:
    spec = importlib.util.spec_from_file_location("product_isolation", TOOLS / "verify_product_isolation.py")
    isolation = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(isolation)
finally:
    sys.path.pop(0)


class ProductIsolationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="product-isolation-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "source"
        self.destination = Path(temporary.name) / "isolated"
        self.report = Path(temporary.name) / "evidence"
        self.report.mkdir()
        for relative in isolation.BUILD_INPUTS:
            self.write(relative, "actual build fixture\n")
        for index, (module, manifest) in enumerate(isolation.MODULES.items()):
            source = f"org/flowlang/boundary/Source{index}.kt"
            self.write(manifest, source + "\n")
            self.write(isolation.SOURCE_ROOT / source, f"class Source{index}\n")
            if module in isolation.TEST_MODULES:
                self.write(Path(module) / "src/test/kotlin/Test.kt", f"class Tests{index}\n")
        self.write(Path("test-support/kotlin/Support.kt"), "class Support\n")
        self.write(isolation.RESOURCE_INVENTORY, "modules/fixture.yaml\n")
        self.write(Path("modules/fixture.yaml"), "contract fixture\n")

    def write(self, relative, contents):
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents)
        return target

    def test_actual_product_inputs_copied_without_verification_fixtures_or_outputs(self):
        excluded = ["flow-conformance-kit/src/test/kotlin/Kit.kt", "src/main/kotlin/org/flowlang/conformance/Runner.kt",
                    "src/test/kotlin/Old.kt", "tests/Old.kt", "flow-compiler/src/testFixtures/kotlin/Fixture.kt",
                    "flow-cli/build/classes/Old.class", ".flow-agent/report.yaml", ".git/config"]
        for relative in excluded:
            self.write(Path(relative), "excluded")
        digests = isolation.prepare_isolated_project(self.root, self.destination)
        self.assertIn("gradle/cli-application.gradle.kts", digests)
        self.assertEqual(len(isolation.BUILD_INPUTS) + len(isolation.MODULES) + len(isolation.TEST_MODULES) + 2, len(digests))
        for relative in digests:
            target = self.destination / (isolation.RESOURCE_INPUTS if relative == "modules/fixture.yaml" else Path()) / relative
            self.assertEqual((self.root / relative).read_bytes(), target.read_bytes())
        for relative in excluded:
            self.assertFalse((self.destination / relative).exists(), relative)

    def test_empty_duplicate_unsorted_or_escaping_manifest_rejected(self):
        manifest = isolation.MODULES["flow-cli"]
        for invalid in ("", "# empty\n", "org/flowlang/Z.kt\norg/flowlang/A.kt\n",
                        "org/flowlang/A.kt\n" * 2, "/tmp/A.kt\n", "org/flowlang/../../A.kt\n", "org/flowlang/*.kt\n"):
            with self.subTest(invalid=invalid):
                self.write(manifest, invalid)
                with self.assertRaises(ValueError):
                    isolation.production_sources(self.root)

    def test_overlapping_manifest_rejected(self):
        self.write(isolation.MODULES["flow-cli"], "org/flowlang/boundary/Source0.kt\n")
        with self.assertRaisesRegex(ValueError, "Overlapping"):
            isolation.production_sources(self.root)

    def test_missing_or_symbolic_build_inputs_rejected(self):
        relative = Path("gradle/cli-application.gradle.kts")
        original = self.root / relative
        original.unlink()
        with self.assertRaises(ValueError):
            isolation.prepare_isolated_project(self.root, self.destination)
        original.symlink_to(self.root / "build.gradle.kts")
        with self.assertRaises(ValueError):
            isolation.prepare_isolated_project(self.root, self.destination)

    def test_actual_sources_and_tests_are_mandatory(self):
        source = self.root / isolation.SOURCE_ROOT / "org/flowlang/boundary/Source0.kt"
        source.unlink()
        with self.assertRaises(ValueError):
            isolation.prepare_isolated_project(self.root, self.destination)
        source.write_text("class Source0\n")
        (self.root / "flow-cli/src/test/kotlin/Test.kt").unlink()
        with self.assertRaisesRegex(ValueError, "actual test"):
            isolation.prepare_isolated_project(self.root, self.destination)

    def test_dirty_destination_cannot_reuse_existing_outputs(self):
        self.destination.mkdir()
        (self.destination / "old.class").write_text("stale")
        with self.assertRaisesRegex(ValueError, "empty"):
            isolation.prepare_isolated_project(self.root, self.destination)

    def compiled_evidence(self, destination):
        """Controlled output shapes for validation tests, never executable bytecode."""
        ownership = isolation.production_sources(self.root)
        for module in isolation.MODULES:
            if module in isolation.TEST_MODULES:
                report = destination / module / "build/test-results/test/TEST-fixture.xml"
                report.parent.mkdir(parents=True, exist_ok=True)
                report.write_text(f'<testsuite tests="1" failures="0" errors="0" skipped="0">'
                                  f'<testcase classname="fixture.{module}" name="fixture"/></testsuite>')
            boundary = "semantic-kernel-boundary" if module == "flow-semantic-kernel" else "production-module-boundary"
            report = destination / module / "build/reports" / boundary / "classpath.txt"
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text("compileClasspath org.jetbrains.kotlin:kotlin-stdlib:2.4.10\n"
                              "runtimeClasspath org.jetbrains.kotlin:kotlin-stdlib:2.4.10\n")
        report = destination / isolation.OWNERSHIP_REPORT
        report.parent.mkdir(parents=True, exist_ok=True)
        owners = {":" + module: {"role": "product", "sources": [p.relative_to(isolation.SOURCE_ROOT).as_posix() for p in paths]}
                  for module, paths in ownership.items()}
        owners[":"] = {"role": "aggregate", "sources": []}
        report.write_text(json.dumps({"version": 1, "completePartition": True, "modules": owners}))
        lib = destination / isolation.INSTALL / "lib"
        lib.mkdir(parents=True, exist_ok=True)
        for index, module in enumerate(isolation.MODULES):
            with zipfile.ZipFile(lib / f"{module}-0.9.5.jar", "w") as jar:
                jar.writestr(f"org/flowlang/boundary/Source{index}.class", b"fixture, not bytecode")

    def runner(self, command, *, cwd, check, timeout):
        self.assertFalse(check)
        self.assertEqual(900, timeout)
        for item in ("--offline", "--no-build-cache", "-Pflow.isolatedBoundary=product", ":flow-cli:installDist"):
            self.assertIn(item, command)
        self.assertFalse((cwd / "flow-conformance-kit").exists())
        self.assertFalse((cwd / "src/test").exists())
        self.assertFalse((cwd / "tests").exists())
        self.assertFalse((cwd / ".git").exists())
        for module in isolation.MODULES:
            self.assertIn(f":{module}:clean", command)
            self.assertEqual(module in isolation.TEST_MODULES, f":{module}:test" in command)
        self.compiled_evidence(cwd)
        return SimpleNamespace(returncode=0)

    def test_real_orchestration_requires_fresh_product_build_and_installed_smoke(self):
        with patch.object(isolation.subprocess, "run", side_effect=self.runner) as run, \
             patch.object(isolation, "smoke_product", return_value={"controlled": True}) as smoke, \
             patch.object(isolation, "verify_relocated_product", return_value={"controlled": True}) as relocated:
            isolation.verify(self.root, self.report, True)
        self.assertEqual(1, run.call_count)
        self.assertEqual(1, smoke.call_count)
        self.assertEqual(1, relocated.call_count)
        proof = json.loads((self.report / "proof.json").read_text())
        self.assertEqual("passed", proof["status"])
        self.assertFalse(proof["verificationSourcesPresent"])
        self.assertEqual(len(isolation.MODULES), proof["installedProduct"]["projectJarCount"])

    def test_nonzero_exit_cannot_be_hidden_by_success_shaped_reports(self):
        def failed(*args, **kwargs):
            self.runner(*args, **kwargs)
            return SimpleNamespace(returncode=37)
        with patch.object(isolation.subprocess, "run", side_effect=failed), patch.object(isolation, "smoke_product") as smoke:
            with self.assertRaises(RuntimeError):
                isolation.verify(self.root, self.report, True)
            smoke.assert_not_called()
        self.assertEqual("failed", json.loads((self.report / "proof.json").read_text())["status"])

    def test_success_exit_without_reports_is_not_accepted(self):
        with patch.object(isolation.subprocess, "run", return_value=SimpleNamespace(returncode=0)):
            with self.assertRaises(ValueError):
                isolation.verify(self.root, self.report, True)
        self.assertEqual("failed", json.loads((self.report / "proof.json").read_text())["status"])

    def test_testcase_failure_cannot_hide_behind_declared_counts(self):
        self.compiled_evidence(self.destination)
        report = self.destination / "flow-cli/build/test-results/test/TEST-fixture.xml"
        report.write_text(report.read_text().replace('/></testsuite>', '><failure/></testcase></testsuite>'))
        with self.assertRaisesRegex(ValueError, "JUnit failures"):
            isolation.collect_results(self.destination, self.report, isolation.production_sources(self.root))

    def test_duplicate_or_missing_test_identities_are_rejected(self):
        for identity in ('classname="fixture.flow-standard-artifacts" name="fixture"', 'classname="" name="fixture"'):
            with self.subTest(identity=identity):
                self.compiled_evidence(self.destination)
                report = self.destination / "flow-cli/build/test-results/test/TEST-fixture.xml"
                report.write_text(report.read_text().replace('classname="fixture.flow-cli" name="fixture"', identity))
                with self.assertRaisesRegex(ValueError, "JUnit identity"):
                    isolation.collect_results(self.destination, self.report, isolation.production_sources(self.root))

    def test_missing_or_verification_classpath_evidence_fails(self):
        for contents in (None, "", "runtimeClasspath project::flow-conformance-kit\n", "runtimeClasspath wrong-test-fixtures\n"):
            with self.subTest(contents=contents):
                self.compiled_evidence(self.destination)
                report = self.destination / "flow-cli/build/reports/production-module-boundary/classpath.txt"
                if contents is None:
                    report.unlink()
                else:
                    report.write_text(contents)
                with self.assertRaises(ValueError):
                    isolation.collect_results(self.destination, self.report, isolation.production_sources(self.root))

    def test_actual_source_ownership_cannot_claim_kit_or_residual_root(self):
        for owner in (":", ":flow-conformance-kit"):
            with self.subTest(owner=owner):
                self.compiled_evidence(self.destination)
                report = self.destination / isolation.OWNERSHIP_REPORT
                doc = json.loads(report.read_text())
                doc["modules"][owner] = {"role": "verification", "sources": ["org/flowlang/conformance/Runner.kt"]}
                report.write_text(json.dumps(doc))
                with self.assertRaisesRegex(ValueError, "ownership report"):
                    isolation.collect_results(self.destination, self.report, isolation.production_sources(self.root))

    def test_distribution_requires_exact_product_jars_and_no_kit(self):
        self.compiled_evidence(self.destination)
        lib = self.destination / isolation.INSTALL / "lib"
        with zipfile.ZipFile(lib / "flow-conformance-kit-0.9.5.jar", "w") as jar:
            jar.writestr("anything.class", b"fixture")
        with self.assertRaisesRegex(ValueError, "exactly one JAR"):
            isolation.inspect_distribution(self.destination)
        (lib / "flow-conformance-kit-0.9.5.jar").unlink()
        (lib / "flow-cli-0.9.5.jar").unlink()
        with self.assertRaises(ValueError):
            isolation.inspect_distribution(self.destination)

    def test_verification_or_duplicate_class_cannot_hide_in_product_named_jar(self):
        for name in ("org/flowlang/conformance/Runner.class", "org/flowlang/verification/Commands.class",
                     "org/flowlang/compiler/fixtures/Fixture.class", "org/flowlang/boundary/Source0.class"):
            with self.subTest(name=name):
                self.compiled_evidence(self.destination)
                with zipfile.ZipFile(self.destination / isolation.INSTALL / "lib/flow-cli-0.9.5.jar", "a") as jar:
                    jar.writestr(name, b"fixture")
                with self.assertRaises(ValueError):
                    isolation.inspect_distribution(self.destination)

    def test_installed_smoke_uses_empty_cwd_and_checks_real_exit_codes(self):
        launcher = self.destination / isolation.INSTALL / "bin/flow-core"
        launcher.parent.mkdir(parents=True)
        launcher.write_text("fixture launcher\n")
        def smoke(command, *, cwd, **kwargs):
            self.assertEqual([], list(Path(cwd).iterdir()))
            self.assertEqual(["bash", str(launcher)], command[:2])
            if command[2:] == ["diagnostics"]:
                return SimpleNamespace(returncode=0, stdout='===== FLOW STANDARD DIAGNOSTIC CATALOG =====\n{"codes":[{}]}', stderr="")
            if command[2:] == []:
                return SimpleNamespace(returncode=0, stdout="Flow CLI commands: diagnostics, intent, standard-verify\n", stderr="")
            self.assertEqual(["conformance"], command[2:])
            return SimpleNamespace(returncode=2, stdout="CLI_UNKNOWN_COMMAND", stderr="")
        with patch.object(isolation.subprocess, "run", side_effect=smoke):
            runs = isolation.smoke_product(self.destination, self.report)
        self.assertEqual(2, runs["reject-verification"]["exitCode"])

    def test_empty_or_incomplete_help_cannot_certify_an_installed_product(self):
        launcher = self.destination / isolation.INSTALL / "bin/flow-core"
        launcher.parent.mkdir(parents=True)
        launcher.write_text("fixture launcher\n")
        for help_text in ("", "Flow CLI commands: diagnostics\n", "Flow CLI commands: diagnostics, intent, standard-verify, conformance\n"):
            responses = [SimpleNamespace(returncode=0, stdout='===== FLOW STANDARD DIAGNOSTIC CATALOG =====\n{"codes":[{}]}', stderr=""),
                         SimpleNamespace(returncode=0, stdout=help_text, stderr="")]
            with self.subTest(help_text=help_text), patch.object(isolation.subprocess, "run", side_effect=responses):
                with self.assertRaisesRegex(ValueError, "truthful product-only"):
                    isolation.smoke_product(self.destination, self.report)

    def test_failed_installed_command_does_not_produce_success(self):
        launcher = self.destination / isolation.INSTALL / "bin/flow-core"
        launcher.parent.mkdir(parents=True)
        launcher.write_text("fixture launcher\n")
        with patch.object(isolation.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout="fake pass", stderr="")):
            with self.assertRaises(ValueError):
                isolation.smoke_product(self.destination, self.report)

    def test_failure_before_compile_invalidates_previous_success(self):
        (self.report / "proof.json").write_text('{"status":"passed"}')
        (self.root / "settings.gradle.kts").unlink()
        with self.assertRaises(ValueError):
            isolation.verify(self.root, self.report, True)
        self.assertEqual("failed", json.loads((self.report / "proof.json").read_text())["status"])


if __name__ == "__main__":
    unittest.main()
