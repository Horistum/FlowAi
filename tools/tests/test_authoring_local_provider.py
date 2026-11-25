"""Exercise local GGUF setup and prompting without downloading model weights."""
import importlib.util
import io
import json
import hashlib
import os
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[2] / "examples/goal-authoring/local_gguf.py"
SPEC = importlib.util.spec_from_file_location("local_gguf", SCRIPT)
PROVIDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROVIDER)


class LocalProviderSetupTests(unittest.TestCase):
    def test_only_verified_complete_weights_reach_the_cache(self):
        expected = b"example model"
        with tempfile.TemporaryDirectory() as cache, patch.dict(os.environ, XDG_CACHE_HOME=cache), \
                patch.object(PROVIDER, "EXAMPLE_BYTES", len(expected)), \
                patch.object(PROVIDER, "EXAMPLE_SHA256", hashlib.sha256(expected).hexdigest()), \
                patch.object(PROVIDER, "urlopen") as download:
            directory = Path(cache) / "horistum/models"
            for bad in (expected[:-1], b"x" * len(expected), expected + b"x"):
                download.return_value = io.BytesIO(bad)
                with self.subTest(bad=bad), self.assertRaises(ValueError):
                    PROVIDER.download_example()
                self.assertEqual([], list(directory.iterdir()))

            download.return_value = io.BytesIO(expected)
            path = PROVIDER.download_example()
            self.assertEqual(expected, path.read_bytes())
            download.reset_mock()
            self.assertEqual(path, PROVIDER.download_example())
            download.assert_not_called()

            path.write_bytes(b"x" * len(expected))
            download.side_effect = OSError("network unavailable")
            with self.assertRaises(OSError):
                PROVIDER.download_example()
            self.assertEqual(b"x" * len(expected), path.read_bytes())
            self.assertEqual([path], list(directory.iterdir()))


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
