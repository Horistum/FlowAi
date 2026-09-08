"""Regression contracts for the repository's offline Actions workflow.

These helpers inspect its explicit job/step indentation, not arbitrary YAML.
No third-party parser is needed by the standard-library tooling test suite.
"""
from __future__ import annotations

import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/flow-offline-check.yml"
JOBS = ("check", "empty-home-proof")


def job_block(workflow: str, name: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(name)}:\s*\n(.*?)(?=^  [\w-]+:\s*\n|\Z)",
        workflow,
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
            r"(?m)^\s*(?:run:\s*)?\./tools/offline_gradle_build\.sh\s+(\S+)",
            step,
        )
        phases.extend((mode, step) for mode in calls)
    return phases


def validate_phase_budgets(job: str) -> None:
    phases = phase_steps(job)
    if [mode for mode, _ in phases] != ["prefetch", "verify"]:
        raise ValueError("Prefetch and verify must be ordered, separate phases")
    if phases[0][1] == phases[1][1]:
        raise ValueError("Build phases must not share a step timeout")
    limits = []
    for _, step in phases:
        match = re.search(r"(?m)^        timeout-minutes: (\d+)\s*$", step)
        if match is None or int(match.group(1)) < 20:
            raise ValueError("Each build phase needs an explicit 20-minute budget")
        limits.append(int(match.group(1)))
    parent = re.search(r"(?m)^    timeout-minutes: (\d+)\s*$", job)
    if parent is None or int(parent.group(1)) < sum(limits) + 10:
        raise ValueError("Job must also budget for setup and evidence upload")


class OfflineWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_both_jobs_budget_each_build_phase_and_job_overhead(self) -> None:
        for name in JOBS:
            with self.subTest(job=name):
                validate_phase_budgets(job_block(self.workflow, name))

    def test_combined_all_mode_cannot_reintroduce_the_timeout(self) -> None:
        for name in JOBS:
            with self.subTest(job=name):
                job = job_block(self.workflow, name)
                broken = job.replace("offline_gradle_build.sh prefetch", "offline_gradle_build.sh all")
                with self.assertRaisesRegex(ValueError, "separate phases"):
                    validate_phase_budgets(broken)

    def test_two_commands_cannot_share_one_step_budget(self) -> None:
        broken = """    timeout-minutes: 50
    steps:
      - name: Combined build
        timeout-minutes: 20
        run: |
          ./tools/offline_gradle_build.sh prefetch
          ./tools/offline_gradle_build.sh verify
"""
        with self.assertRaisesRegex(ValueError, "share a step"):
            validate_phase_budgets(broken)

    def test_job_timeout_cannot_undercut_its_phase_budgets(self) -> None:
        for name in JOBS:
            with self.subTest(job=name):
                job = job_block(self.workflow, name)
                broken = re.sub(r"(?m)^    timeout-minutes: \d+$", "    timeout-minutes: 20", job)
                with self.assertRaisesRegex(ValueError, "Job must also budget"):
                    validate_phase_budgets(broken)

    def test_missing_or_reduced_phase_timeout_is_rejected(self) -> None:
        job = job_block(self.workflow, "check")
        for replacement in ("", "        timeout-minutes: 10"):
            with self.subTest(replacement=replacement):
                broken = job.replace("        timeout-minutes: 20", replacement, 1)
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

    def test_empty_home_is_reset_only_before_prefetch_and_reused_for_verify(self) -> None:
        phases = phase_steps(job_block(self.workflow, "empty-home-proof"))
        self.assertEqual([mode for mode, _ in phases], ["prefetch", "verify"])
        prefetch, verify = (step for _, step in phases)
        for step in (prefetch, verify):
            self.assertIn('proof_root="$RUNNER_TEMP/flow-empty-home-proof"', step)
            self.assertIn('--prefetch-gradle-home "$proof_root/prepared-gradle-home"', step)
            self.assertIn('--verify-gradle-home "$proof_root/verified-gradle-home"', step)
        self.assertLess(prefetch.index('rm -rf "$proof_root"'), prefetch.index("./tools/offline_gradle_build.sh"))
        self.assertNotIn("rm -", verify)

    def test_cache_evidence_is_uploaded_even_after_failure(self) -> None:
        for name in JOBS:
            with self.subTest(job=name):
                uploads = [step for step in step_blocks(job_block(self.workflow, name))
                           if "uses: actions/upload-artifact@" in step]
                self.assertEqual(len(uploads), 1)
                self.assertIn("if: always()", uploads[0])
                self.assertIn("build/reports/offline-cache/*.json", uploads[0])
                self.assertIn("build/reports/offline-cache/*.txt", uploads[0])

    def test_empty_home_proof_keeps_scheduled_and_manual_activation(self) -> None:
        self.assertIn(
            "if: github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'",
            job_block(self.workflow, "empty-home-proof"),
        )


if __name__ == "__main__":
    unittest.main()
