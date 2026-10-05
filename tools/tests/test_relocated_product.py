"""Negative controls for distribution byte and resource inventory verification."""
import hashlib
import importlib.util
import json
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
        rendered = '===== REPORT =====\n{}\n===== RENDERED EXECUTABLE TARGET OUTPUT: output.yaml =====\nsteps: []\n'
        self.assertEqual("steps: []\n", relocated.sections(rendered)["RENDERED EXECUTABLE TARGET OUTPUT: output.yaml"])
        review = rendered.replace("RENDERED EXECUTABLE TARGET OUTPUT", "RENDERED NON-EXECUTABLE REVIEW EVIDENCE")
        self.assertEqual("steps: []\n", relocated.sections(review)["RENDERED NON-EXECUTABLE REVIEW EVIDENCE: output.yaml"])
        for text in ("unstructured", "===== REPORT =====\nnot json", "noise\n===== REPORT =====\n{}",
                     "===== REPORT =====\n{}\n===== REPORT =====\n{}\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                relocated.sections(text)

    def test_publication_receipt_binds_bytes_inventory_and_installed_schema(self):
        data = b'{"standardVersion":"0.8.0"}\n'
        schema = b'{"type":"object"}'
        (self.root / "value.json").write_bytes(data)
        receipt = {"status": "PASS", "artifactIntegrityVersion": "1.1", "requiredArtifactsExpected": ["value.json"],
                   "requiredArtifactsPresent": ["value.json"], "publication": {
                       "protocol": "staged-atomic-directory-v1", "excludedPaths": ["artifact-integrity-report.json"],
                       "fileDataForced": True, "coveredFiles": [{"path": "value.json", "sizeBytes": len(data),
                           "sha256": hashlib.sha256(data).hexdigest(), "validation": "JSON_SCHEMA",
                           "schema": "schemas/value.schema.json", "schemaSha256": hashlib.sha256(schema).hexdigest(),
                           "standardVersion": "0.8.0"}]}}
        (self.root / "artifact-integrity-report.json").write_text(json.dumps(receipt))
        resources = {"schemas/value.schema.json": schema}
        self.assertEqual(64, len(relocated.verify_publication(self.root, resources)))
        (self.root / "value.json").write_bytes(data + b" ")
        with self.assertRaisesRegex(ValueError, "bytes"):
            relocated.verify_publication(self.root, resources)
        (self.root / "value.json").write_bytes(data)
        with self.assertRaisesRegex(ValueError, "schema"):
            relocated.verify_publication(self.root, {"schemas/value.schema.json": b"false"})
        (self.root / "extra.txt").write_text("extra")
        with self.assertRaisesRegex(ValueError, "inventory"):
            relocated.verify_publication(self.root, resources)

    def test_logical_integrity_pass_is_not_a_publication_receipt(self):
        (self.root / "artifact-integrity-report.json").write_text('{"status":"PASS"}')
        with self.assertRaisesRegex(ValueError, "actual-byte"):
            relocated.verify_publication(self.root, {})


if __name__ == "__main__":
    unittest.main()
