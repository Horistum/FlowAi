"""Presentation behavior for compiler-admitted nested control structures."""
import io
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
try:
    from authoring_host.conversation import show_result
finally:
    sys.path.pop(0)


class TerminalReviewTests(unittest.TestCase):
    def test_missing_provider_does_not_create_a_session_to_resume(self):
        author = Path(__file__).resolve().parents[1] / "flow-author"
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory) / "my review"
            config = Path(directory) / "missing.json"
            start = subprocess.run([sys.executable, str(author), "chat", "Inspect the catalog", "--config", str(config),
                                    "--out", str(folder)], capture_output=True, text=True)
            self.assertEqual(2, start.returncode)
            self.assertIn(str(config), start.stderr)
            self.assertFalse(folder.exists())
            resume = subprocess.run([sys.executable, str(author), "chat", "--run", str(folder)],
                                    capture_output=True, text=True)
            self.assertEqual(2, resume.returncode)
            self.assertIn(f"No saved session at {folder}", resume.stderr)
            self.assertFalse(folder.exists())

    def test_failure_handler_and_retry_stay_nested_and_limits_are_visible(self):
        nested = {"kind": "retry", "id": "poll", "maxAttempts": 2, "delaySeconds": 4, "body": [
            {"kind": "operation", "id": "check", "capability": "VERIFY", "params": {"resource": "catalog"}}]}
        intent = {"name": "service check", "workflows": [{"name": "audit", "kind": "CUSTOM", "body": [
            {"id": "guard", "kind": "attempt", "disposition": "propagate", "onFailure": [
                {"id": "tell", "kind": "operation", "capability": "NOTIFY"}], "body": [nested]}]}]}
        view = {"status": "plan-ready-for-review", "requestFidelity": "unassessed", "candidateIntent": intent,
                "planFile": "/session/plan.json", "reviewFile": "/session/review.json"}
        output = io.StringIO()
        with redirect_stdout(output):
            show_result(view)
        text = output.getvalue()
        self.assertIn("disposition: propagate", text)
        self.assertIn("maxAttempts: 2", text)
        self.assertIn("delaySeconds: 4", text)
        self.assertLess(text.index("body:"), text.index("poll  retry"))
        self.assertLess(text.index("resource: catalog"), text.index("onFailure:"))
        self.assertIn("onFailure:\n      tell  NOTIFY", text)

    def test_provider_limitation_is_separate_from_compiler_review_and_safe_for_terminal(self):
        output = io.StringIO()
        with redirect_stdout(output):
            show_result({"status": "provider-cannot-plan", "reason": "Unsupported\x1b[2J\u009b!"})
        text = output.getvalue()
        self.assertNotIn("\x1b", text)
        self.assertNotIn("\u009b", text)
        self.assertIn("Provider explanation", text)
        self.assertNotIn("Compiler-checked plan", text)

    def test_process_signal_is_distinct_from_a_model_decision(self):
        output = io.StringIO()
        with redirect_stdout(output):
            show_result({"status": "provider-error", "failure": "provider-process-error", "providerReturnCode": -9})
        self.assertIn("Provider process terminated by signal 9", output.getvalue())
        self.assertNotIn("Provider explanation", output.getvalue())
