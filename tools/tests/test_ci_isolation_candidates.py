"""Exercise real Git trees, not mocked equality or fabricated CI successes."""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "ci_isolation_candidates.py"
spec = importlib.util.spec_from_file_location("ci_isolation_candidates", SCRIPT)
selection = importlib.util.module_from_spec(spec)
spec.loader.exec_module(selection)


@unittest.skipUnless(shutil.which("git"), "Git required")
class IsolationCandidateTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="isolation-candidates-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.git("init", "--quiet", "--initial-branch=main")
        self.git("config", "user.name", "CI fixture")
        self.git("config", "user.email", "fixture@localhost")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.filemode", "true")
        self.base = self.write_commit("meaning.txt", "baseline\n")
        self.head = self.write_commit("meaning.txt", "candidate\n")
        self.merge = self.merge_tree(self.tree(self.head))
        self.git("checkout", "--quiet", "--detach", self.merge)

    def git(self, *args, input=None):
        return subprocess.check_output(["git", *args], cwd=self.root, text=True, input=input,
                                       stderr=subprocess.PIPE, timeout=15).strip()

    def write_commit(self, path, content):
        (self.root / path).parent.mkdir(parents=True, exist_ok=True)
        (self.root / path).write_text(content)
        self.git("add", "--", path)
        self.git("commit", "--quiet", "-m", "fixture change")
        return self.git("rev-parse", "HEAD")

    def tree(self, sha):
        return self.git("rev-parse", sha + "^{tree}")

    def merge_tree(self, tree, parents=None):
        args = ["commit-tree", tree, "-m", "synthetic merge"]
        for parent in parents or (self.base, self.head):
            args += ["-p", parent]
        return self.git(*args)

    def plan(self, **changes):
        args = dict(root=self.root, event="pull_request", event_sha=self.merge,
                    head_sha=self.head, base_sha=self.base)
        args.update(changes)
        return selection.plan(**args)

    def test_equal_trees_share_only_physical_isolation(self):
        result = self.plan()
        self.assertNotEqual(self.head, self.merge)
        self.assertEqual("physical-source-isolation-only", result["scope"])
        self.assertEqual("planned", result["status"])
        self.assertEqual([{"label": "exact-head", "revision": self.head,
                           "tree": self.tree(self.head), "covers": ["exact-head", "merge-candidate"]}],
                         result["matrix"]["include"])

    def test_different_tree_keeps_independent_proofs(self):
        changed = self.write_commit("base-addition.txt", "changed base content\n")
        merge = self.merge_tree(self.tree(changed))
        self.git("checkout", "--quiet", "--detach", merge)
        candidates = self.plan(event_sha=merge)["matrix"]["include"]
        self.assertEqual([self.head, merge], [entry["revision"] for entry in candidates])
        self.assertEqual([["exact-head"], ["merge-candidate"]], [entry["covers"] for entry in candidates])

    def test_identical_file_bytes_but_changed_mode_do_not_share(self):
        self.git("update-index", "--chmod=+x", "meaning.txt")
        merge = self.merge_tree(self.git("write-tree"))
        self.git("reset", "--hard", merge)
        self.assertEqual(2, len(self.plan(event_sha=merge)["matrix"]["include"]))

    def test_metadata_only_change_is_not_ignored(self):
        changed = self.write_commit("docs/report.md", "current validation metadata\n")
        merge = self.merge_tree(self.tree(changed))
        self.git("checkout", "--quiet", "--detach", merge)
        self.assertEqual(2, len(self.plan(event_sha=merge)["matrix"]["include"]))

    def test_each_non_pr_event_has_exactly_one_immutable_candidate(self):
        for event in ("push", "workflow_dispatch"):
            with self.subTest(event=event):
                result = self.plan(event=event, head_sha="", base_sha="")
                self.assertEqual([{"label": "exact-head", "revision": self.merge,
                                   "tree": self.tree(self.merge), "covers": ["exact-head"]}],
                                 result["matrix"]["include"])

    def test_non_pr_event_rejects_extraneous_pr_identities(self):
        with self.assertRaisesRegex(ValueError, "Non-PR"):
            self.plan(event="push")

    def test_unknown_events_cannot_create_a_matrix(self):
        for event in ("", "pull_request_target", "schedule", "release"):
            with self.subTest(event=event), self.assertRaises(ValueError):
                self.plan(event=event)

    def test_all_sha_inputs_reject_refs_short_forms_and_shell_text(self):
        for argument in ("event_sha", "head_sha", "base_sha"):
            for sha in ("", "HEAD", "main", self.head[:12], self.head.upper(), "$(touch injected)", "--help"):
                with self.subTest(argument=argument, sha=sha), self.assertRaises(ValueError):
                    self.plan(**{argument: sha})
        self.assertFalse((self.root / "injected").exists())

    def test_validly_shaped_missing_commits_fail(self):
        for argument in ("event_sha", "head_sha", "base_sha"):
            with self.subTest(argument=argument), self.assertRaises(subprocess.SubprocessError):
                self.plan(**{argument: "0" * 40})

    def test_tree_or_blob_cannot_be_used_as_a_commit(self):
        for value in (self.tree(self.head), self.git("rev-parse", self.head + ":meaning.txt")):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.plan(head_sha=value)

    def test_checkout_must_match_event_sha(self):
        self.git("checkout", "--quiet", "--detach", self.head)
        with self.assertRaisesRegex(ValueError, "Checked-out"):
            self.plan()

    def test_synthetic_merge_must_have_exact_event_parents_in_order(self):
        for parents in ([self.head, self.base], [self.head], [self.base]):
            merge = self.merge_tree(self.tree(self.head), parents)
            self.git("checkout", "--quiet", "--detach", merge)
            with self.subTest(parents=parents), self.assertRaisesRegex(ValueError, "parents"):
                self.plan(event_sha=merge)

    def test_extra_merge_parent_is_not_silently_discarded(self):
        extra = self.git("commit-tree", self.tree(self.base), "-m", "independent root")
        merge = self.merge_tree(self.tree(self.head), [self.base, self.head, extra])
        self.git("checkout", "--quiet", "--detach", merge)
        with self.assertRaisesRegex(ValueError, "parents"):
            self.plan(event_sha=merge)

    def test_tracked_worktree_edits_cannot_borrow_committed_tree(self):
        (self.root / "meaning.txt").write_text("not committed\n")
        with self.assertRaisesRegex(ValueError, "Tracked"):
            self.plan()

    def test_staged_edits_cannot_borrow_committed_tree(self):
        (self.root / "meaning.txt").write_text("staged\n")
        self.git("add", "meaning.txt")
        with self.assertRaisesRegex(ValueError, "Tracked"):
            self.plan()

    def test_untracked_runner_output_does_not_change_source_identity(self):
        (self.root / "runner-output.txt").write_text("not a copied isolation input\n")
        self.assertEqual(1, len(self.plan()["matrix"]["include"]))

    def test_shallow_checkout_with_two_generations_is_sufficient(self):
        clone = self.root / "shallow"
        subprocess.run(["git", "clone", "--quiet", "--depth=2", "--no-local", str(self.root), str(clone)],
                       check=True, capture_output=True, timeout=15)
        self.assertTrue((clone / ".git/shallow").is_file())
        result = self.plan(root=clone)
        self.assertEqual(1, len(result["matrix"]["include"]))

    def test_git_replacement_cannot_substitute_a_different_tree(self):
        replacement = self.git("commit-tree", self.tree(self.base), "-m", "replacement",
                               "-p", self.base, "-p", self.head)
        self.git("replace", self.merge, replacement)
        # The committed merge tree still matches HEAD, despite the replacement.
        self.assertEqual(1, len(self.plan()["matrix"]["include"]))

    def execute(self, event_sha):
        output = self.root / "github-output"
        result = subprocess.run(
            ["python3", str(SCRIPT), "--root", str(self.root), "--event", "pull_request",
             "--event-sha", event_sha, "--head-sha", self.head, "--base-sha", self.base],
            env={**os.environ, "GITHUB_OUTPUT": str(output)}, capture_output=True, text=True, timeout=15,
        )
        return result, output

    def test_cli_emits_one_json_output_only_after_validation(self):
        result, output = self.execute(self.merge)
        self.assertEqual(0, result.returncode, result.stderr)
        parsed = json.loads(result.stdout)
        key, value = output.read_text().strip().split("=", 1)
        self.assertEqual("matrix", key)
        self.assertEqual(parsed["matrix"], json.loads(value))
        self.assertEqual(1, len(output.read_text().splitlines()))

    def test_cli_failure_does_not_emit_a_success_or_empty_matrix(self):
        result, output = self.execute("HEAD")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.exists())
        self.assertEqual("", result.stdout)


if __name__ == "__main__":
    unittest.main()
