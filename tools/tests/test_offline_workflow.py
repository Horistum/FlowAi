"""Offline workflow contracts and real shell entry-point regression tests.

Workflow helpers inspect this file's explicit indentation, not arbitrary YAML.
Shell tests replace Java/Gradle with local fixtures; they do not claim a build.
"""
from __future__ import annotations

import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/flow-offline-check.yml"
SCRIPT = ROOT / "tools/offline_gradle_build.sh"
JOBS = ("offline-exact-head",)


def job_block(workflow: str, name: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(name)}:\s*\n(.*?)(?=^  [\w-]+:\s*\n|\Z)", workflow
    )
    if match is None:
        raise ValueError(f"Missing offline job: {name}")
    return match.group(1)


def step_blocks(job: str) -> list[str]:
    return re.split(r"(?m)^      - ", job)[1:]


def phase_steps(job: str) -> list[tuple[str, str]]:
    phases = []
    for step in step_blocks(job):
        calls = re.findall(
            r"(?m)^\s*(?:run:\s*)?bash tools/offline_gradle_build\.sh\s+(\S+)", step
        )
        phases.extend((mode, step) for mode in calls)
    return phases


def validate_phase_budgets(job: str) -> None:
    phases = phase_steps(job)
    if [mode for mode, _ in phases] != ["prepare", "verify"]:
        raise ValueError("Prepare and verify must be ordered, separate bash phases")
    if phases[0][1] == phases[1][1]:
        raise ValueError("Build phases must not share a step timeout")
    limits = []
    for _, step in phases:
        match = re.search(r"(?m)^        timeout-minutes: (\d+)\s*$", step)
        if match is None or int(match.group(1)) < 20:
            raise ValueError("Each build phase needs an explicit 20-minute budget")
        limits.append(int(match.group(1)))
    parent = re.search(r"(?m)^    timeout-minutes: (\d+)\s*$", job)
    if parent is None or int(parent.group(1)) < sum(limits) + 5:
        raise ValueError("Job must also budget for setup and evidence upload")


class OfflineWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_requested_job_budgets_each_phase_and_job_overhead(self) -> None:
        for name in JOBS:
            with self.subTest(job=name):
                validate_phase_budgets(job_block(self.workflow, name))

    def test_combined_all_or_unknown_mode_is_rejected(self) -> None:
        for name in JOBS:
            for mode in ("all", "prefetch"):
                with self.subTest(job=name, mode=mode):
                    broken = job_block(self.workflow, name).replace(
                        "offline_gradle_build.sh prepare", f"offline_gradle_build.sh {mode}"
                    )
                    with self.assertRaisesRegex(ValueError, "separate bash phases"):
                        validate_phase_budgets(broken)

    def test_two_commands_cannot_share_one_step_budget(self) -> None:
        broken = """    timeout-minutes: 50
    steps:
      - name: Combined build
        timeout-minutes: 20
        run: |
          bash tools/offline_gradle_build.sh prepare
          bash tools/offline_gradle_build.sh verify
"""
        with self.assertRaisesRegex(ValueError, "share a step"):
            validate_phase_budgets(broken)

    def test_job_timeout_cannot_undercut_phase_budgets(self) -> None:
        for name in JOBS:
            with self.subTest(job=name):
                broken = re.sub(r"(?m)^    timeout-minutes: \d+$", "    timeout-minutes: 20",
                                job_block(self.workflow, name))
                with self.assertRaisesRegex(ValueError, "Job must also budget"):
                    validate_phase_budgets(broken)

    def test_missing_or_reduced_phase_timeout_is_rejected(self) -> None:
        for replacement in ("", "        timeout-minutes: 10"):
            with self.subTest(replacement=replacement):
                broken = job_block(self.workflow, JOBS[0]).replace(
                    "        timeout-minutes: 20", replacement, 1
                )
                with self.assertRaisesRegex(ValueError, "explicit 20-minute"):
                    validate_phase_budgets(broken)

    def test_verification_is_not_optional_and_failures_are_not_ignored(self) -> None:
        for name in JOBS:
            with self.subTest(job=name):
                job = job_block(self.workflow, name)
                self.assertNotIn("continue-on-error:", job)
                for _, step in phase_steps(job):
                    self.assertNotRegex(step, r"(?m)^        if:")
                    self.assertNotIn("|| true", step)

    def test_selected_revision_guard_is_unconditional(self) -> None:
        job = job_block(self.workflow, JOBS[0])
        self.assertIn("ref: ${{ inputs.revision || github.sha }}", job)
        self.assertIn("EXPECTED_SHA: ${{ inputs.revision || github.sha }}", job)
        guards = [step for step in step_blocks(job) if "EXPECTED_SHA:" in step]
        self.assertEqual(len(guards), 1)
        self.assertNotRegex(guards[0], r"(?m)^        if:")
        self.assertIn('test "$(git rev-parse HEAD)" = "$EXPECTED_SHA"', guards[0])
        self.assertIn('[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]]', guards[0])
        self.assertIn("persist-credentials: false", job)

    def test_manifest_is_uploaded_even_after_failure(self) -> None:
        for name in JOBS:
            with self.subTest(job=name):
                uploads = [step for step in step_blocks(job_block(self.workflow, name))
                           if "uses: actions/upload-artifact@" in step]
                self.assertEqual(len(uploads), 1)
                self.assertIn("if: ${{ !cancelled() }}", uploads[0])
                self.assertIn("path: .flow-offline/input-manifest.txt", uploads[0])
                self.assertIn("include-hidden-files: true", uploads[0])


@unittest.skipUnless(shutil.which("bash") and shutil.which("sha256sum"), "Unix shell tools required")
class OfflineBuildEntryPointTests(unittest.TestCase):
    def setUp(self) -> None:
        temp = tempfile.TemporaryDirectory(prefix="offline-contract-")
        self.addCleanup(temp.cleanup)
        self.root = pathlib.Path(temp.name)
        (self.root / "tools").mkdir()
        shutil.copyfile(SCRIPT, self.root / "tools/offline_gradle_build.sh")
        for name in ("gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar",
                     "flow-semantic-kernel/build.gradle.kts", "gradle/semantic-kernel-sources.txt",
                     "build.gradle.kts", "settings.gradle.kts"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture\n", encoding="utf-8")
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        java = bin_dir / "java"
        java.write_text('#!/usr/bin/env bash\nprintf \'openjdk version "%s"\\n\' "${TEST_JAVA_VERSION:-25}" >&2\n', encoding="utf-8")
        java.chmod(0o755)
        gradle = self.root / "gradlew"
        gradle.write_text('''#!/usr/bin/env bash
set -euo pipefail
printf '%s|%s\\n' "$GRADLE_USER_HOME" "$*" >> "$TEST_GRADLE_LOG"
if [[ " $* " == *" --offline "* ]]; then
  test -f "$GRADLE_USER_HOME/prepared-input"
  exit "${TEST_VERIFY_EXIT:-0}"
fi
mkdir -p "$GRADLE_USER_HOME"
printf 'compiler input\\n' > "$GRADLE_USER_HOME/prepared-input"
exit "${TEST_PREPARE_EXIT:-0}"
''', encoding="utf-8")
        gradle.chmod(0o755)
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith(("FLOW_OFFLINE_", "TEST_"))}
        self.env.update(PATH=f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
                        TEST_GRADLE_LOG=str(self.root / "gradle-calls.log"))

    def run_mode(self, mode: str, **environment: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["bash", "tools/offline_gradle_build.sh", mode], cwd=self.root,
                              env={**self.env, **environment}, text=True, capture_output=True,
                              timeout=15, check=False)

    def test_separate_processes_relocate_inputs_and_keep_full_offline_command(self) -> None:
        for mode in ("prepare", "verify"):
            result = self.run_mode(mode)
            self.assertEqual(result.returncode, 0, result.stderr)
        calls = (self.root / "gradle-calls.log").read_text().splitlines()
        self.assertEqual(len(calls), 2)
        prepared, verified = (line.split("|", 1) for line in calls)
        self.assertNotEqual(prepared[0], verified[0])
        self.assertNotIn("--offline", prepared[1])
        for argument in ("--offline", "--no-build-cache", "clean", "test", "run", "--args=conformance"):
            self.assertIn(argument, verified[1].split())
        self.assertTrue((self.root / ".flow-offline/input-manifest.txt").is_file())

    def test_prepare_failure_propagates_without_success_manifest(self) -> None:
        self.assertEqual(self.run_mode("prepare", TEST_PREPARE_EXIT="17").returncode, 17)
        self.assertFalse((self.root / ".flow-offline/input-manifest.txt").exists())

    def test_verify_failure_propagates(self) -> None:
        self.assertEqual(self.run_mode("prepare").returncode, 0)
        self.assertEqual(self.run_mode("verify", TEST_VERIFY_EXIT="19").returncode, 19)

    def test_verify_rejects_an_unprepared_home(self) -> None:
        self.assertEqual(self.run_mode("verify").returncode, 3)
        self.assertFalse((self.root / "gradle-calls.log").exists())

    def test_wrong_jdk_is_rejected_before_gradle(self) -> None:
        self.assertEqual(self.run_mode("prepare", TEST_JAVA_VERSION="17").returncode, 2)
        self.assertFalse((self.root / "gradle-calls.log").exists())


if __name__ == "__main__":
    unittest.main()
