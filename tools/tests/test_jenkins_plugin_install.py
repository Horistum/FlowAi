import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "flow-adapter-jenkins/src/runtimeTest/install-plugins.sh"
FORBIDDEN = "Unable to retrieve JSON from https://updates.jenkins.io/plugin-versions.json: status code: 403, reason phrase: Forbidden"


class JenkinsPluginInstallTests(unittest.TestCase):
    def run_install(self, outcomes):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "outcomes.json").write_text(json.dumps(outcomes))
            installer = root / "jenkins-plugin-cli"
            installer.write_text(f"#!{sys.executable}\n" + '''import json, os, pathlib, sys
root = pathlib.Path(os.environ["FIXTURE_ROOT"])
calls = root / "calls.jsonl"
previous = calls.read_text().splitlines() if calls.exists() else []
with calls.open("a") as stream:
    stream.write(json.dumps(sys.argv[1:]) + "\\n")
code, message = json.loads((root / "outcomes.json").read_text())[len(previous)]
print(message, file=sys.stderr)
sys.exit(code)
''')
            installer.chmod(0o755)
            sleeper = root / "sleep"
            sleeper.write_text('#!/bin/sh\nprintf "%s\\n" "$1" >> "$FIXTURE_ROOT/sleeps"\n')
            sleeper.chmod(0o755)
            arguments = ["--latest=false", "--plugins", "git:5.10.1", "a value with spaces"]
            result = subprocess.run(["sh", str(SCRIPT), *arguments], capture_output=True, text=True,
                                    env=os.environ | {"PATH": str(root) + os.pathsep + os.environ["PATH"],
                                                      "FIXTURE_ROOT": str(root)}, timeout=5)
            calls = [json.loads(line) for line in (root / "calls.jsonl").read_text().splitlines()]
            self.assertTrue(all(call == arguments for call in calls))
            sleeps = (root / "sleeps").read_text().splitlines() if (root / "sleeps").exists() else []
            return result, calls, sleeps

    def test_success_is_not_retried(self):
        result, calls, sleeps = self.run_install([(0, "installed")])
        self.assertEqual(0, result.returncode)
        self.assertEqual(1, len(calls))
        self.assertEqual([], sleeps)

    def test_observed_metadata_failure_can_recover_with_original_arguments(self):
        update_center = FORBIDDEN.replace("plugin-versions.json", "update-center.json?version=2.580.1")
        result, calls, sleeps = self.run_install([(1, FORBIDDEN), (1, update_center), (0, "installed")])
        self.assertEqual(0, result.returncode)
        self.assertEqual(3, len(calls))
        self.assertEqual(["5", "10"], sleeps)

    def test_persistent_metadata_failure_preserves_exit_status_and_budget(self):
        result, calls, sleeps = self.run_install([(7, FORBIDDEN)] * 3)
        self.assertEqual(7, result.returncode)
        self.assertEqual(3, len(calls))
        self.assertEqual(["5", "10"], sleeps)

    def test_other_failures_are_not_retried(self):
        for message in ("Plugin dependency resolution failed", FORBIDDEN.replace("updates.jenkins.io", "other.example"),
                        FORBIDDEN.replace("403", "404")):
            with self.subTest(message=message):
                result, calls, sleeps = self.run_install([(9, message)])
                self.assertEqual(9, result.returncode)
                self.assertEqual(1, len(calls))
                self.assertEqual([], sleeps)
