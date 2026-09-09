"""Test isolation orchestration and fail-closed evidence, not mock compilation."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
try:
    spec = importlib.util.spec_from_file_location("compiler_isolation", TOOLS / "verify_compiler_isolation.py")
    isolation = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(isolation)
finally:
    sys.path.pop(0)


class CompilerIsolationTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="compiler-isolation-test-")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name) / "source"
        self.destination = Path(temp.name) / "isolated"
        self.report = Path(temp.name) / "evidence"
        for relative in isolation.BUILD_INPUTS:
            self.write(relative, "actual build fixture\n")
        for index, (module, manifest) in enumerate(isolation.MODULES.items()):
            source = f"org/flowlang/boundary/Source{index}.kt"
            self.write(manifest, source + "\n")
            self.write(isolation.SOURCE_ROOT / source, f"class Source{index}\n")
            self.write(Path(module) / "src/test/kotlin/Test.kt", f"class Tests{index}\n")
        self.write(Path("flow-compiler/src/testFixtures/kotlin/Fixture.kt"), "class Fixture\n")
        self.write(Path("test-support/kotlin/Support.kt"), "class Support\n")

    def write(self, relative, text):
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        return target

    def test_copies_actual_partition_build_and_tests_but_no_frontends_or_outputs(self):
        for relative in ("flow-frontends/src/main/kotlin/Frontend.kt", "src/main/kotlin/org/flowlang/adapter/Target.kt",
                         "src/test/kotlin/Integration.kt", "flow-compiler/build/classes/Old.class"):
            self.write(Path(relative), "excluded")
        digests = isolation.prepare_isolated_project(self.root, self.destination)
        self.assertEqual(len(isolation.BUILD_INPUTS) + 8, len(digests))
        for relative in digests:
            self.assertEqual((self.root / relative).read_bytes(), (self.destination / relative).read_bytes())
        self.assertFalse((self.destination / "flow-frontends").exists())
        self.assertFalse((self.destination / "src/test").exists())
        self.assertFalse((self.destination / "flow-compiler/build").exists())
        self.assertFalse((self.destination / "src/main/kotlin/org/flowlang/adapter").exists())

    def test_manifest_cannot_be_empty_unsorted_or_duplicate(self):
        manifest = isolation.MODULES["flow-compiler"]
        for contents in ("", "# comment", "org/flowlang/Z.kt\norg/flowlang/A.kt\n",
                         "org/flowlang/boundary/Source2.kt\n" * 2):
            with self.subTest(contents=contents):
                self.write(manifest, contents)
                with self.assertRaises(ValueError):
                    isolation.production_sources(self.root)

    def test_manifests_cannot_overlap(self):
        self.write(isolation.MODULES["flow-compiler"], "org/flowlang/boundary/Source0.kt\n")
        with self.assertRaisesRegex(ValueError, "Overlapping"):
            isolation.production_sources(self.root)

    def test_ownership_cannot_escape_or_use_globs(self):
        for entry in ("/tmp/X.kt", "org/flowlang/../../X.kt", "org/flowlang/**/*.kt"):
            with self.subTest(entry=entry):
                self.write(isolation.MODULES["flow-compiler"], entry + "\n")
                with self.assertRaises(ValueError):
                    isolation.production_sources(self.root)

    def test_missing_production_build_input_fails(self):
        (self.root / "gradle/production-module.gradle.kts").unlink()
        with self.assertRaises(ValueError):
            isolation.prepare_isolated_project(self.root, self.destination)

    def test_missing_source_fails(self):
        (self.root / isolation.SOURCE_ROOT / "org/flowlang/boundary/Source2.kt").unlink()
        with self.assertRaises(ValueError):
            isolation.prepare_isolated_project(self.root, self.destination)

    def test_symbolic_source_fails(self):
        source = self.root / isolation.SOURCE_ROOT / "org/flowlang/boundary/Source2.kt"
        source.unlink()
        other = self.write(Path("unowned.kt"), "class Unowned")
        source.symlink_to(other)
        with self.assertRaises(ValueError):
            isolation.prepare_isolated_project(self.root, self.destination)

    def test_missing_real_test_suite_fails(self):
        (self.root / "flow-compiler/src/test/kotlin/Test.kt").unlink()
        with self.assertRaisesRegex(ValueError, "test suite"):
            isolation.prepare_isolated_project(self.root, self.destination)

    def test_missing_real_test_support_fails(self):
        (self.root / "test-support/kotlin/Support.kt").unlink()
        with self.assertRaisesRegex(ValueError, "test support"):
            isolation.prepare_isolated_project(self.root, self.destination)

    def test_existing_isolation_outputs_are_rejected(self):
        self.destination.mkdir()
        (self.destination / "old.class").write_text("stale")
        with self.assertRaisesRegex(ValueError, "empty"):
            isolation.prepare_isolated_project(self.root, self.destination)

    def runner(self, command, *, cwd, check, timeout):
        self.assertFalse(check)
        self.assertEqual(timeout, 600)
        self.assertIn("--offline", command)
        self.assertIn("--no-build-cache", command)
        self.assertIn("-Pflow.isolatedBoundary=compiler", command)
        self.assertNotIn("--tests", command)
        self.assertNotIn("--exclude-task", command)
        self.assertFalse((cwd / "flow-frontends").exists())
        for module in isolation.MODULES:
            self.assertIn(f":{module}:clean", command)
            if module in isolation.TEST_MODULES:
                self.assertIn(f":{module}:test", command)
            else:
                self.assertNotIn(f":{module}:test", command)
            directory = cwd / module / "build/test-results/test"
            directory.mkdir(parents=True)
            (directory / "TEST-fixture.xml").write_text(
                f'<testsuite tests="1" failures="0" errors="0" skipped="0">'
                f'<testcase classname="fixture.{module}" name="fixture"/></testsuite>')
            boundary = "semantic-kernel-boundary" if module == "flow-semantic-kernel" else "production-module-boundary"
            report = cwd / module / "build/reports" / boundary / "classpath.txt"
            report.parent.mkdir(parents=True)
            report.write_text("fixture classpath\n")
        return SimpleNamespace(returncode=0)

    def test_real_entrypoint_requires_clean_offline_module_builds_and_fresh_reports(self):
        with patch.object(isolation.subprocess, "run", side_effect=self.runner) as run:
            isolation.verify(self.root, self.report, True)
        self.assertEqual(run.call_count, 1)
        proof = json.loads((self.report / "proof.json").read_text())
        self.assertEqual("passed", proof["status"])
        self.assertEqual({key: 1 for key in isolation.MODULES}, proof["productionSourceCounts"])

    def test_nonzero_exit_is_not_hidden_by_success_shaped_test_reports(self):
        def failed(*args, **kwargs):
            self.runner(*args, **kwargs)
            return SimpleNamespace(returncode=37)
        with patch.object(isolation.subprocess, "run", side_effect=failed):
            with self.assertRaises(RuntimeError):
                isolation.verify(self.root, self.report, True)
        self.assertEqual("failed", json.loads((self.report / "proof.json").read_text())["status"])

    def test_success_exit_without_current_test_reports_fails(self):
        with patch.object(isolation.subprocess, "run", return_value=SimpleNamespace(returncode=0)):
            with self.assertRaises(RuntimeError):
                isolation.verify(self.root, self.report, True)

    def test_testcase_failure_cannot_hide_behind_declared_zero_count(self):
        def lying(*args, **kwargs):
            result = self.runner(*args, **kwargs)
            report = kwargs["cwd"] / "flow-compiler/build/test-results/test/TEST-fixture.xml"
            report.write_text(report.read_text().replace('/></testsuite>', '><failure message="failed"/></testcase></testsuite>'))
            return result
        with patch.object(isolation.subprocess, "run", side_effect=lying):
            with self.assertRaises((RuntimeError, ValueError)):
                isolation.verify(self.root, self.report, True)
        self.assertEqual("failed", json.loads((self.report / "proof.json").read_text())["status"])

    def test_missing_classpath_evidence_fails(self):
        def incomplete(*args, **kwargs):
            result = self.runner(*args, **kwargs)
            (kwargs["cwd"] / "flow-compiler/build/reports/production-module-boundary/classpath.txt").unlink()
            return result
        with patch.object(isolation.subprocess, "run", side_effect=incomplete):
            with self.assertRaises(RuntimeError):
                isolation.verify(self.root, self.report, True)

    def test_failure_before_compile_invalidates_stale_success(self):
        self.report.mkdir()
        (self.report / "proof.json").write_text('{"status":"passed"}')
        (self.root / "settings.gradle.kts").unlink()
        with self.assertRaises(ValueError):
            isolation.verify(self.root, self.report, True)
        self.assertEqual("failed", json.loads((self.report / "proof.json").read_text())["status"])
