"""Negative controls for distribution byte and resource inventory verification."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("relocated", Path(__file__).resolve().parents[1] / "verify_relocated_product.py")
relocated = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relocated)


class RelocatedProductTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def test_inventory_rejects_empty_duplicate_unsorted_absolute_and_traversing_paths(self):
        manifest = self.root / relocated.RESOURCE_INVENTORY
        manifest.parent.mkdir()
        for value in ("", "modules/a.yaml\nmodules/a.yaml\n", "modules/z.yaml\nmodules/a.yaml\n",
                      "/tmp/a.yaml\n", "modules/../outside.yaml\n"):
            manifest.write_text(value)
            with self.subTest(value=value), self.assertRaises(ValueError):
                relocated.resource_paths(self.root)
        manifest.write_text("modules/a.yaml\n")
        self.assertEqual([Path("modules/a.yaml")], relocated.resource_paths(self.root))

    def write_jar(self, name="contracts.jar", data=b"authored", indexed=b"authored", extra=False):
        lib = self.root / "lib"
        lib.mkdir(exist_ok=True)
        with zipfile.ZipFile(lib / name, "w") as jar:
            jar.writestr(relocated.PREFIX + "modules/a.yaml", data)
            jar.writestr(relocated.PREFIX + "index.tsv", hashlib.sha256(indexed).hexdigest() + "\tmodules/a.yaml\n")
            if extra:
                jar.writestr(relocated.PREFIX + "modules/unlisted.yaml", b"unlisted")

    def test_actual_packaged_bytes_must_match_the_complete_index(self):
        self.write_jar()
        self.assertEqual({"modules/a.yaml": b"authored"}, relocated.packaged_resources(self.root))
        self.write_jar(data=b"tampered")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            relocated.packaged_resources(self.root)
        self.write_jar(extra=True)
        with self.assertRaisesRegex(ValueError, "complete index"):
            relocated.packaged_resources(self.root)

    def test_missing_and_duplicate_classpath_resources_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "index"):
            relocated.packaged_resources(self.root)
        self.write_jar()
        self.write_jar(name="shadow.jar")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            relocated.packaged_resources(self.root)

    def test_cli_receipt_requires_parseable_sections(self):
        self.assertEqual({"REPORT": {"status": "PASS"}}, relocated.sections('===== REPORT =====\n{"status":"PASS"}\n'))
        for text in ("unstructured", "===== REPORT =====\nnot json", "noise\n===== REPORT =====\n{}"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                relocated.sections(text)


if __name__ == "__main__":
    unittest.main()
