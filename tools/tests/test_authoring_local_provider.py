"""Exercise the optional GGUF provider's prompt without downloading model weights."""
import importlib.util
import json
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
        self.assertIn("marker", system["content"])
        for entry in entries:
            self.assertIn(entry["capability"] + " | " + entry["description"], user["content"])
        self.assertIn('requiredParams=["scope"]', user["content"])
        for item in [*observed.values(), request]:
            self.assertIn(json.dumps(item, separators=(",", ":")), user["content"])
        self.assertEqual(before, json.dumps(payload))
