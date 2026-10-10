import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("portfolio_receipt", Path(__file__).resolve().parents[1] / "certification_portfolio_receipt.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PortfolioReceiptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.revision = "a" * 40

    def write(self, name="", scenario="test"):
        path = self.root / name
        path.mkdir(exist_ok=True)
        (path / "proof.json").write_text(json.dumps({"sourceRevision": self.revision, "status": "passed", "bundle": {"scenarios": [{"id": scenario}]}}))
        (path / "trust.json").write_text('{"test":true}')
        return path

    def receipt(self):
        return MODULE.receipt(self.root, "jenkins-checkout-runtime", self.revision)

    def test_digest_pins_exact_bytes_and_order_is_stable(self):
        first = self.write("z", "second")
        self.write("a", "first")
        doc = self.receipt()
        self.assertEqual(["first", "second"], [r["scenarioId"] for r in doc["assessments"]])
        self.assertEqual(hashlib.sha256((first / "proof.json").read_bytes()).hexdigest(), doc["assessments"][1]["proofSha256"])

    def test_stale_failed_empty_and_duplicate_inputs_are_rejected(self):
        with self.assertRaises(ValueError): self.receipt()
        path = self.write()
        original = (path / "proof.json").read_text()
        for field, value in [("status", "failed"), ("sourceRevision", "b" * 40)]:
            doc = json.loads(original); doc[field] = value
            (path / "proof.json").write_text(json.dumps(doc))
            with self.assertRaises(ValueError): self.receipt()
        (path / "proof.json").write_text(original)
        self.write("duplicate")
        with self.assertRaises(ValueError): self.receipt()

    def test_symlinked_trust_is_rejected(self):
        path = self.write()
        (path / "trust.json").unlink()
        (path / "real.json").write_text("{}")
        (path / "trust.json").symlink_to(path / "real.json")
        with self.assertRaises(ValueError): self.receipt()

    def test_missing_and_oversized_trust_are_rejected(self):
        path = self.write()
        (path / "trust.json").write_bytes(b"x" * 16385)
        with self.assertRaises(ValueError): self.receipt()
        (path / "trust.json").unlink()
        with self.assertRaises(FileNotFoundError): self.receipt()
