"""Exercise the optional GGUF provider's prompt without downloading model weights."""
import importlib.util
import json
import hashlib
import tempfile
from pathlib import Path
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "examples/goal-authoring/local_gguf.py"
SPEC = importlib.util.spec_from_file_location("local_gguf", SCRIPT)
PROVIDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROVIDER)


class LocalProviderPromptTests(unittest.TestCase):
    def test_every_catalog_entry_and_conversation_input_reaches_the_model(self):
        entries = [{"capability": name, "description": description, "requiredParams": ["scope"]}
                   for name, description in (("CATALOG_FIRST", "First operation"), ("CATALOG_NEXT", "Next operation"))]
        request = {"userText": "Use only the stated resources", "context": {"owner": "archive-team"}}
        observed = {"capabilityContracts": {"custom.yaml": "custom module contract"},
                    "compilerFeedback": {"diagnostic": "a missing dependency"},
                    "userAnswers": [{"id": "1:scope", "answer": "agreed scope"}],
                    "previousResponse": {"draft": "prior proposal"}}
        payload = {"instructions": "author a proposal", "output_schema": {"properties": {"marker": {"type": "string"}}},
                   "input": {"capabilityCatalog": entries, "request": request, **observed}}
        before = json.dumps(payload)
        system, user = PROVIDER.model_messages(payload)
        self.assertEqual(payload["output_schema"], json.loads(system["content"].split("Response schema:\n")[1]))
        self.assertEqual({"capabilityCatalog": entries, "request": request, **observed}, json.loads(user["content"]))
        self.assertEqual(before, json.dumps(payload))

    def test_model_identity_covers_every_gguf_shard_in_order(self):
        with tempfile.TemporaryDirectory() as work:
            plain = Path(work) / "local.gguf"
            plain.write_bytes(b"weights")
            self.assertEqual("sha256:" + hashlib.sha256(b"weights").hexdigest(), PROVIDER.model_identity(plain))
            first = Path(work) / "local-00001-of-00002.gguf"
            second = Path(work) / "local-00002-of-00002.gguf"
            first.write_bytes(b"a")
            with self.assertRaises(FileNotFoundError):
                PROVIDER.model_identity(first)
            second.write_bytes(b"b")
            original = PROVIDER.model_identity(first)
            second.write_bytes(b"c")
            self.assertNotEqual(original, PROVIDER.model_identity(first))
            with self.assertRaises(ValueError):
                PROVIDER.model_identity(second)
