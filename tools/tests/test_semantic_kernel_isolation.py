import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

MODULE_PATH = Path(__file__).resolve().parents[1] / "verify_semantic_kernel_isolation.py"
spec = importlib.util.spec_from_file_location("kernel_isolation", MODULE_PATH)
isolation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(isolation)


class SemanticKernelIsolationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "source"
        self.destination = Path(self.temporary.name) / "isolated"
        for relative in isolation.BUILD_INPUTS:
            self.write(relative, "build input\n")
        self.write(isolation.MANIFEST, "org/flowlang/kernel/Graph.kt\n")
        self.write(isolation.SOURCE_ROOT / "org/flowlang/kernel/Graph.kt", "class Graph\n")
        self.write(isolation.MODULE / "src/test/kotlin/GraphTests.kt", "class GraphTests\n")

    def write(self, relative, text):
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        return target

    def test_copies_exact_build_and_owned_sources_without_outputs_or_adapters(self):
        self.write(isolation.SOURCE_ROOT / "org/flowlang/adapters/Concrete.kt", "class Concrete\n")
        self.write(isolation.MODULE / "build/classes/Fake.class", "fake output")
        digests = isolation.prepare_isolated_project(self.root, self.destination)
        self.assertEqual(len(isolation.BUILD_INPUTS) + 2, len(digests))
        self.assertFalse((self.destination / isolation.SOURCE_ROOT / "org/flowlang/adapters").exists())
        self.assertFalse((self.destination / isolation.MODULE / "build").exists())
        for relative in digests:
            self.assertEqual((self.root / relative).read_bytes(), (self.destination / relative).read_bytes())

    def test_rejects_duplicates_empty_or_unsorted_ownership(self):
        for manifest in ("", "# comment\n", "org/flowlang/kernel/Graph.kt\n" * 2,
                         "org/flowlang/Z.kt\norg/flowlang/A.kt\n"):
            with self.subTest(manifest=manifest):
                self.write(isolation.MANIFEST, manifest)
                with self.assertRaises(ValueError):
                    isolation.kernel_sources(self.root)

    def test_rejects_globs_absolute_and_parent_paths(self):
        for entry in ("/tmp/X.kt", "org/flowlang/../../X.kt", "org/flowlang/**/*.kt"):
            with self.subTest(entry=entry):
                self.write(isolation.MANIFEST, entry + "\n")
                with self.assertRaises(ValueError):
                    isolation.kernel_sources(self.root)

    def test_rejects_missing_production_input(self):
        (self.root / isolation.SOURCE_ROOT / "org/flowlang/kernel/Graph.kt").unlink()
        with self.assertRaises(ValueError):
            isolation.prepare_isolated_project(self.root, self.destination)

    def test_rejects_symbolic_sources(self):
        original = self.root / isolation.SOURCE_ROOT / "org/flowlang/kernel/Graph.kt"
        original.unlink()
        other = self.write(Path("unowned.kt"), "class Fake\n")
        original.symlink_to(other)
        with self.assertRaises(ValueError):
            isolation.kernel_sources(self.root)

    def test_rejects_missing_actual_tests(self):
        (self.root / isolation.MODULE / "src/test/kotlin/GraphTests.kt").unlink()
        with self.assertRaises(ValueError):
            isolation.prepare_isolated_project(self.root, self.destination)

    def test_rejects_missing_production_build_configuration(self):
        (self.root / "settings.gradle.kts").unlink()
        with self.assertRaises(ValueError):
            isolation.prepare_isolated_project(self.root, self.destination)

    def test_rejects_preexisting_outputs_in_isolation_directory(self):
        self.destination.mkdir()
        (self.destination / "old.class").write_text("stale")
        with self.assertRaises(ValueError):
            isolation.prepare_isolated_project(self.root, self.destination)

    def test_report_reset_preserves_unrelated_files_and_invalidates_stale_success(self):
        self.destination.mkdir()
        valuable = self.destination / "keep.txt"
        valuable.write_text("not a generated report")
        proof = self.destination / "proof.json"
        proof.write_text('{"status": "passed"}')
        isolation.initialize_report(self.destination)
        self.assertEqual("not a generated report", valuable.read_text())
        self.assertEqual("failed", json.loads(proof.read_text())["status"])
