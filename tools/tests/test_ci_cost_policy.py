"""Cost controls must not remove either final PR validation boundary.

The structural checks target the workflows' explicit YAML layout. Shell tests
execute their actual run blocks with local tool fixtures, not real compilation.
No network access or third-party Python packages are needed.
"""
from __future__ import annotations
import fnmatch

import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest

from test_offline_workflow import job_block, step_blocks

ROOT = pathlib.Path(__file__).resolve().parents[2]
CI = ROOT / ".github/workflows/flow-agent-check.yml"
OFFLINE = ROOT / ".github/workflows/flow-offline-check.yml"
CI_JOBS = ("compile-test-conformance", "merge-candidate-compile-test-conformance")
ISOLATION_JOBS = ("isolation-candidates", "module-isolation")


def top_block(workflow: str, name: str) -> str:
    match = re.search(rf"(?ms)^{re.escape(name)}:\n(.*?)(?=^\S|\Z)", workflow)
    if match is None:
        raise ValueError(f"Missing workflow section: {name}")
    return match.group(1)


def named_step(job: str, name: str) -> str:
    matches = [step for step in step_blocks(job) if step.splitlines()[0] == f"name: {name}"]
    if len(matches) != 1:
        raise ValueError(f"Expected one step named {name}, found {len(matches)}")
    return matches[0]


def run_script(step: str) -> str:
    lines = step.splitlines(keepends=True)
    start = lines.index("        run: |\n") + 1
    body = []
    for line in lines[start:]:
        if line.strip() and not line.startswith("          "):
            break
        body.append(line)
    return textwrap.dedent("".join(body))


def build_steps(job: str) -> list[str]:
    return [step for step in step_blocks(job)
            if "./gradlew " in step or "python3 tools/verify_semantic_kernel_isolation.py " in step
            or "python3 tools/verify_compiler_isolation.py " in step
            or "python3 tools/verify_adapter_isolation.py " in step
            or "python3 tools/verify_product_isolation.py " in step
            or "./build/install/flow-core/bin/flow-core conformance" in step]


class CiCostPolicyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.ci = CI.read_text(encoding="utf-8")
        cls.offline = OFFLINE.read_text(encoding="utf-8")

    def test_branch_pushes_do_not_duplicate_pr_runs(self) -> None:
        triggers = top_block(self.ci, "on")
        push = triggers.split("  push:\n", 1)[1].split("  pull_request:\n", 1)[0]
        self.assertEqual(push.strip(), "branches:\n      - main")
        self.assertIn("  workflow_dispatch:", triggers)

    def test_draft_transitions_cancel_work_and_ready_transition_revalidates(self) -> None:
        triggers = top_block(self.ci, "on")
        match = re.search(r"types: \[([^]]+)\]", triggers)
        self.assertIsNotNone(match)
        self.assertEqual({value.strip() for value in match.group(1).split(",")},
                         {"opened", "synchronize", "reopened", "ready_for_review", "converted_to_draft"})
        exact = job_block(self.ci, CI_JOBS[0])
        merge = job_block(self.ci, CI_JOBS[1])
        self.assertIn("if: ${{ !cancelled() && (github.event_name != 'pull_request' || github.event.pull_request.draft == false) }}", exact)
        self.assertIn("if: ${{ !cancelled() && github.event_name == 'pull_request' && github.event.pull_request.draft == false }}", merge)
        self.assertIn("if: github.event_name != 'pull_request' || github.event.pull_request.draft == false",
                      job_block(self.ci, "isolation-candidates"))

    def test_metadata_changes_are_not_filtered_out_of_final_validation(self) -> None:
        self.assertNotRegex(top_block(self.ci, "on"), r"paths(?:-ignore)?:")

    def test_concurrency_cancels_obsolete_runs_without_combining_prs(self) -> None:
        normal = top_block(self.ci, "concurrency")
        self.assertIn("${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}", normal)
        self.assertNotIn("github.sha", normal)
        for workflow in (self.ci, self.offline):
            block = top_block(workflow, "concurrency")
            self.assertIn("${{ github.workflow }}", block)
            self.assertIn("cancel-in-progress: true", block)

    def test_required_job_identities_and_exact_revision_selection_survive(self) -> None:
        jobs = top_block(self.ci, "jobs")
        self.assertEqual(re.findall(r"(?m)^  ([\w-]+):$", jobs), list(ISOLATION_JOBS + CI_JOBS))
        exact = job_block(self.ci, CI_JOBS[0])
        merge = job_block(self.ci, CI_JOBS[1])
        self.assertIn("ref: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || github.sha }}", exact)
        self.assertIn("EXPECTED_SHA: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || github.sha }}", exact)
        self.assertIn("EXPECTED_SHA: ${{ github.sha }}", merge)
        self.assertNotIn("ref:", named_step(merge, "Checkout Merge Candidate"))
        for job in (exact, merge):
            self.assertIn("timeout-minutes: 20", job)
            self.assertIn('actual_sha="$(git rev-parse HEAD)"', job)
            self.assertIn('if [ "$actual_sha" != "$EXPECTED_SHA" ]; then', job)

    def test_cheap_checks_fail_before_jdk_and_gradle_setup(self) -> None:
        for name in CI_JOBS:
            with self.subTest(job=name):
                job = job_block(self.ci, name)
                for step in ("Test Flow Agent Tooling", "Validate Flow Agent Structure", "Generate Flow Agent Context"):
                    self.assertLess(job.index(f"name: {step}"), job.index("name: Set up JDK 25"))
                self.assertLess(job.index("name: Set up JDK 25"), job.index("name: Set up Gradle"))

    def test_full_test_isolation_and_standalone_conformance_steps_remain(self) -> None:
        for name in CI_JOBS:
            with self.subTest(job=name):
                steps = build_steps(job_block(self.ci, name))
                self.assertEqual(len(steps), 2)
                self.assertIn("--build-cache clean test installDist ", steps[0])
                self.assertIn("./build/install/flow-core/bin/flow-core conformance", steps[1])
                self.assertNotIn("./gradlew", steps[1])
                job = job_block(self.ci, name)
                self.assertIn("needs: [isolation-candidates, module-isolation]", job)
                steps += build_steps(job_block(self.ci, "module-isolation"))
                self.assertEqual(6, len(steps))
                for script in ("semantic_kernel", "compiler", "adapter", "product"):
                    self.assertTrue(any(f"python3 tools/verify_{script}_isolation.py " in step for step in steps))
                for step in steps:
                    self.assertNotRegex(step, r"(?m)^        if:")
                    self.assertNotIn("continue-on-error", step)
                    self.assertNotIn("|| true", step)
                    self.assertNotIn("--tests", step)
                    self.assertNotRegex(step, r"\s-x\s|--exclude-task")

    def test_isolation_has_its_own_budget_and_no_warm_cache_requirement(self) -> None:
        job = job_block(self.ci, "module-isolation")
        self.assertIn("timeout-minutes: 20", job)
        self.assertIn("needs: isolation-candidates", job)
        self.assertIn("matrix: ${{ fromJSON(needs.isolation-candidates.outputs.matrix) }}", job)
        self.assertIn("fail-fast: true", job)
        self.assertIn("ref: ${{ matrix.revision }}", job)
        self.assertIn("fetch-depth: 1", job)
        self.assertIn("EXPECTED_TREE: ${{ matrix.tree }}", job)
        for step in build_steps(job):
            self.assertNotIn("--offline", step)
            self.assertNotIn("--build-cache", step)
        for script in ("semantic_kernel", "compiler", "adapter", "product"):
            source = (ROOT / f"tools/verify_{script}_isolation.py").read_text()
            self.assertIn('"--no-build-cache"', source)
            self.assertIn("tempfile.TemporaryDirectory", source)

    def test_selection_is_cheap_and_uses_both_exact_event_parents(self) -> None:
        job = job_block(self.ci, "isolation-candidates")
        self.assertIn("timeout-minutes: 3", job)
        self.assertIn("fetch-depth: 2", job)
        self.assertIn("EVENT_SHA: ${{ github.sha }}", job)
        self.assertIn("HEAD_SHA: ${{ github.event.pull_request.head.sha }}", job)
        self.assertIn("BASE_SHA: ${{ github.event.pull_request.base.sha }}", job)
        self.assertIn("matrix: ${{ steps.candidates.outputs.matrix }}", job)
        self.assertIn("python3 tools/ci_isolation_candidates.py", job)
        for command in ("flow_agent_validate.py", "flow_agent_runner.py"):
            self.assertIn(f"python3 tools/{command}", job)
            self.assertLess(job.index(f"python3 tools/{command}"), job.index("name: Select Exact Isolation Trees"))
        self.assertNotIn("Set up Gradle", job)
        self.assertNotIn("Set up JDK", job)

    def test_required_jobs_do_not_silently_skip_failed_prerequisites(self) -> None:
        for name in CI_JOBS:
            job = job_block(self.ci, name)
            self.assertIn("!cancelled()", job.split("steps:")[0])
            self.assertNotIn("success()", job.split("steps:")[0])
            self.assertIn("needs: [isolation-candidates, module-isolation]", job)
            guard = named_step(job, "Require Successful Module Isolation")
            self.assertEqual(step_blocks(job)[0], guard)
            self.assertIn("SELECTION_RESULT: ${{ needs.isolation-candidates.result }}", guard)
            self.assertIn("ISOLATION_RESULT: ${{ needs.module-isolation.result }}", guard)
            self.assertIn('exit 1', guard)

    def test_same_repo_pr_caches_can_be_saved_but_forks_are_read_only(self) -> None:
        expected = ("cache-read-only: ${{ github.event_name == 'pull_request' && "
                    "github.event.pull_request.head.repo.full_name != github.repository }}")
        for name in CI_JOBS:
            self.assertIn(expected, named_step(job_block(self.ci, name), "Set up Gradle"))

    def test_build_cache_cannot_substitute_root_or_kernel_test_evidence(self) -> None:
        kernel = (ROOT / "flow-semantic-kernel/build.gradle.kts").read_text()
        block = re.search(r"(?ms)^tasks\.test \{\n(.*?)^\}", kernel)
        self.assertIsNotNone(block)
        self.assertIn("outputs.upToDateWhen { false }", block.group(1))
        self.assertIn("outputs.cacheIf { false }", block.group(1))
        # The former root suite now belongs to the verification module. Check
        # the actual owner and aggregate, not inert flags on a NO-SOURCE task.
        root = (ROOT / "build.gradle.kts").read_text()
        self.assertIn('kotlin.setSrcDirs(emptyList<String>())', root)
        self.assertIn('tasks.test { dependsOn(subprojects.map { "${it.path}:test" }) }', root)
        kit = (ROOT / "flow-conformance-kit/build.gradle.kts").read_text()
        self.assertIn('kotlin.srcDirs(rootProject.file("src/test/kotlin"), rootProject.file("tests"))', kit)
        self.assertIn('apply(from = rootProject.file("gradle/production-module.gradle.kts"))', kit)
        common = (ROOT / "gradle/production-module.gradle.kts").read_text()
        self.assertIn("outputs.upToDateWhen { false }", common)
        self.assertIn("outputs.cacheIf { false }", common)

    def test_new_module_suites_cannot_reuse_cached_results(self) -> None:
        shared = (ROOT / "gradle/production-module.gradle.kts").read_text()
        self.assertIn('tasks.named<Test>("test")', shared)
        self.assertIn("outputs.upToDateWhen { false }", shared)
        self.assertIn("outputs.cacheIf { false }", shared)
        for module in (project.parent.name for project in ROOT.glob("flow-*/build.gradle.kts")
                       if project.parent.name != "flow-semantic-kernel"):
            self.assertIn('apply(from = rootProject.file("gradle/production-module.gradle.kts"))',
                          (ROOT / module / "build.gradle.kts").read_text())

    def test_reports_survive_failures_but_not_obsolete_cancellations(self) -> None:
        for name in CI_JOBS:
            with self.subTest(job=name):
                uploads = [step for step in step_blocks(job_block(self.ci, name))
                           if "uses: actions/upload-artifact@" in step]
                self.assertEqual(len(uploads), 2)
                for step in uploads:
                    self.assertIn("if: ${{ !cancelled() }}", step)
                    self.assertIn("retention-days: 7", step)
                self.assertIn("build/test-results/test/*.xml", uploads[1])
                patterns = re.findall(r"(?m)^            (\S+)$", uploads[1])
                log_patterns = re.findall(r"(?m)^            (\S+)$", uploads[0])
                for project in sorted(ROOT.glob("flow-*/build.gradle.kts")):
                    module = project.parent.name
                    self.assertTrue(any(fnmatch.fnmatch(f"{module}/build/test-results/test/TEST-example.xml", pattern)
                                        for pattern in patterns), f"Missing JUnit upload for {module}")
                    boundary = "semantic-kernel-boundary" if module == "flow-semantic-kernel" else "production-module-boundary"
                    self.assertTrue(any(fnmatch.fnmatch(f"{module}/build/reports/{boundary}/classpath.txt", pattern)
                                        for pattern in log_patterns), f"Missing classpath upload for {module}")
                isolation_upload = named_step(job_block(self.ci, "module-isolation"), "Upload Isolation Evidence")
                for path in ("kernel", "compiler", "adapter", "product"):
                    self.assertIn(f"ci-logs/{path}-isolation/**", isolation_upload)
                self.assertIn("if: ${{ !cancelled() }}", isolation_upload)
                self.assertIn("retention-days: 7", isolation_upload)
                self.assertIn("if-no-files-found: error", isolation_upload)

    def test_offline_is_manual_only_and_has_one_revision_not_a_duplicate_matrix(self) -> None:
        triggers = top_block(self.offline, "on")
        self.assertEqual(re.findall(r"(?m)^  ([\w-]+):$", triggers), ["workflow_dispatch"])
        self.assertEqual(re.findall(r"(?m)^  ([\w-]+):$", top_block(self.offline, "jobs")), ["offline-exact-head"])
        self.assertNotIn("matrix:", self.offline)
        job = job_block(self.offline, "offline-exact-head")
        self.assertIn("ref: ${{ inputs.revision || github.sha }}", job)
        self.assertIn("EXPECTED_SHA: ${{ inputs.revision || github.sha }}", job)

    def test_new_boundary_uses_the_existing_tree_deduplicated_job(self) -> None:
        self.assertEqual(set(CI_JOBS + ISOLATION_JOBS), set(re.findall(r"(?m)^  ([\w-]+):$", top_block(self.ci, "jobs"))))
        for name in CI_JOBS:
            self.assertNotIn("verify_product_isolation.py", job_block(self.ci, name))
        step = named_step(job_block(self.ci, "module-isolation"), "Prove Installed Product Without Verification Sources")
        self.assertIn("python3 tools/verify_product_isolation.py", step)
        for name in CI_JOBS:
            self.assertIn("build/reports/module-ownership/**", job_block(self.ci, name))

    def test_product_and_verification_launchers_are_explicit_without_extra_run_selection(self) -> None:
        for module, run_task, entry in (
            ("flow-cli", "runProduct", "org.flowlang.cli.honest.HonestFlowCliKt"),
            ("flow-conformance-kit", "runVerification", "org.flowlang.verification.VerificationCliKt"),
        ):
            build = (ROOT / module / "build.gradle.kts").read_text()
            self.assertIn(f'extra["cliRunTaskName"] = "{run_task}"', build)
            self.assertIn(f'extra["cliMainClass"] = "{entry}"', build)
            self.assertNotRegex(build, r"\bapplication\s*[;}]|application\s*\{")
        common = (ROOT / "gradle/cli-application.gradle.kts").read_text()
        self.assertIn('tasks.register<JavaExec>(cliRunTaskName)', common)
        root = (ROOT / "build.gradle.kts").read_text()
        self.assertIn('tasks.jar { enabled = false }', root)
        self.assertIn('classpath = verificationRuntime', root)
        self.assertIn('classpath = productRuntime', root)

    def test_optimization_does_not_add_privileges_or_error_suppression(self) -> None:
        for workflow in (self.ci, self.offline):
            self.assertEqual(top_block(workflow, "permissions").strip(), "contents: read")
            self.assertNotIn("pull_request_target", workflow)
            self.assertNotIn("continue-on-error", workflow)
            self.assertNotIn("persist-credentials: true", workflow)


@unittest.skipUnless(shutil.which("bash") and shutil.which("git"), "Unix shell and Git required")
class CiWorkflowShellTests(unittest.TestCase):
    def setUp(self) -> None:
        temp = tempfile.TemporaryDirectory(prefix="ci-policy-")
        self.addCleanup(temp.cleanup)
        self.root = pathlib.Path(temp.name)
        subprocess.run(["git", "init", "--quiet", "--initial-branch=main", str(self.root)], check=True)
        subprocess.run(["git", "-c", "user.name=CI test", "-c", "user.email=ci-test@localhost",
                        "commit", "--quiet", "--allow-empty", "-m", "fixture"], cwd=self.root, check=True)
        self.sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.root, text=True).strip()
        self.ci = CI.read_text(encoding="utf-8")
        self.offline = OFFLINE.read_text(encoding="utf-8")
        self.env = {**os.environ, "GITHUB_ENV": str(self.root / "github-env")}
        (self.root / "ci-logs").mkdir()
        (self.root / "tools").mkdir()
        wrapper = self.root / "gradlew"
        wrapper.write_text('#!/usr/bin/env bash\nprintf "Gradle fixture %s\\n" "$*"\nexit "${TEST_BUILD_EXIT:-0}"\n')
        wrapper.chmod(0o755)
        (self.root / "tools/verify_semantic_kernel_isolation.py").write_text(
            'import os, sys\nprint("Isolation fixture", sys.argv[1:])\nsys.exit(int(os.environ["TEST_BUILD_EXIT"]))\n')

        for kind in ("compiler", "adapter", "product"):
            shutil.copyfile(self.root / "tools/verify_semantic_kernel_isolation.py",
                            self.root / f"tools/verify_{kind}_isolation.py")

        launcher = self.root / "build/install/flow-core/bin/flow-core"
        launcher.parent.mkdir(parents=True)
        shutil.copyfile(wrapper, launcher)
        launcher.chmod(0o755)

    def execute(self, step: str, **env: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", run_script(step)],
                              cwd=self.root, env={**self.env, **env}, text=True,
                              capture_output=True, timeout=15, check=False)

    def guards(self) -> list[str]:
        return [named_step(job_block(self.ci, CI_JOBS[0]), "Verify Exact Checked-Out Revision"),
                named_step(job_block(self.ci, CI_JOBS[1]), "Verify Merge Candidate Revision"),
                named_step(job_block(self.offline, "offline-exact-head"), "Verify Exact Checked-Out Revision")]

    def test_all_revision_guards_accept_the_actual_commit(self) -> None:
        for guard in self.guards():
            with self.subTest(guard=guard.splitlines()[0]):
                result = self.execute(guard, EXPECTED_SHA=self.sha)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(self.sha, (self.root / "github-env").read_text())

    def test_all_guards_reject_wrong_empty_and_shell_shaped_values_without_evidence(self) -> None:
        for guard in self.guards():
            for expected in ("0" * 40, "", "$(touch injected)"):
                with self.subTest(guard=guard.splitlines()[0], expected=expected):
                    (self.root / "github-env").unlink(missing_ok=True)
                    self.assertNotEqual(self.execute(guard, EXPECTED_SHA=expected).returncode, 0)
                    self.assertFalse((self.root / "github-env").exists())
                    self.assertFalse((self.root / "injected").exists())

    def test_offline_guard_rejects_symbolic_refs(self) -> None:
        guard = self.guards()[-1]
        for ref in ("HEAD", "main", "refs/heads/main", self.sha[:12]):
            with self.subTest(ref=ref):
                self.assertNotEqual(self.execute(guard, EXPECTED_SHA=ref).returncode, 0)

    def test_every_unsuccessful_prerequisite_fails_required_checks(self) -> None:
        for name in CI_JOBS:
            guard = named_step(job_block(self.ci, name), "Require Successful Module Isolation")
            self.assertEqual(0, self.execute(guard, SELECTION_RESULT="success", ISOLATION_RESULT="success").returncode)
            for key in ("SELECTION_RESULT", "ISOLATION_RESULT"):
                for state in ("failure", "cancelled", "skipped", "", "pending"):
                    env = {"SELECTION_RESULT": "success", "ISOLATION_RESULT": "success", key: state}
                    with self.subTest(job=name, prerequisite=key, state=state):
                        self.assertNotEqual(0, self.execute(guard, **env).returncode)

    def test_isolation_checkout_rejects_wrong_tree_before_recording_evidence(self) -> None:
        guard = named_step(job_block(self.ci, "module-isolation"), "Verify Isolation Revision And Tree")
        tree = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=self.root, text=True).strip()
        env = {"EXPECTED_SHA": self.sha, "EXPECTED_TREE": tree, "COVERS": '["exact-head"]',
               "GITHUB_RUN_ID": "17", "GITHUB_RUN_ATTEMPT": "1"}
        self.assertEqual(0, self.execute(guard, **env).returncode)
        for key in ("EXPECTED_SHA", "EXPECTED_TREE"):
            for value in ("0" * 40, "", "HEAD", "$(touch injected)"):
                receipt = self.root / "ci-logs/isolation-revision.txt"
                receipt.unlink(missing_ok=True)
                self.assertNotEqual(0, self.execute(guard, **{**env, key: value}).returncode)
                self.assertFalse(receipt.exists())
        self.assertFalse((self.root / "injected").exists())

    def test_all_six_expensive_steps_propagate_failure_through_tee(self) -> None:
        for name in CI_JOBS + ("module-isolation",):
            for step in build_steps(job_block(self.ci, name)):
                with self.subTest(job=name, step=step.splitlines()[0]):
                    result = self.execute(step, TEST_BUILD_EXIT="37")
                    self.assertEqual(result.returncode, 37, result.stdout + result.stderr)

    def test_all_six_expensive_steps_accept_success(self) -> None:
        for name in CI_JOBS + ("module-isolation",):
            for step in build_steps(job_block(self.ci, name)):
                with self.subTest(job=name, step=step.splitlines()[0]):
                    result = self.execute(step, TEST_BUILD_EXIT="0")
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
