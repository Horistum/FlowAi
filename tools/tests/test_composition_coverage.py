import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import composition_coverage as coverage
from retry_composition_oracle import expectation
from test_github_workspace_composition import workspace_files, compiled_files, api_jobs, ROOT


CONTEXT = dict(sourceRevision="a" * 40, workflowRevision="b" * 40, runId="123", attempt="1")
IMAGE = "sha256:" + "c" * 64


def proof_files(extra=None):
    files = {"image-identity.log": (IMAGE + "\n").encode(), "runtime/runtime.json": b'{"status":"completed"}'}
    if extra:
        files.update(extra)
    proof = CONTEXT | {"status": "passed", "imageId": IMAGE, "publicSupportPromoted": False,
                       "portableExecution": False, "cases": [], "claim": "bounded observation", "limits": ["Fixture only"],
                       "files": {name: {"sha256": coverage.sha(data), "bytes": len(data)} for name, data in files.items()}}
    return files | {"proof.json": coverage.encode(proof)}


def signed_files():
    portfolio = coverage.encode(dict(sourceRevision=CONTEXT["sourceRevision"], publicSupportPromoted=False, portableExecution=False))
    targets = ["jenkins", "github-actions", "unimplemented<&|`target"]
    constructs = ["CONDITIONS", "RETRY", "LOOPS", "MATCH", "PARALLELISM", "VALUE_STATE_CONTINUITY", "ARTIFACTS_WORKSPACES"]
    rows = [{"target": t, "construct": c, "status": "NO_BEHAVIORAL_EVIDENCE_IN_PORTFOLIO", "scenarioIds": []}
            for t in targets for c in constructs]
    rows[0].update(status="BOUNDED_SCENARIO_EVIDENCE", scenarioIds=["signed-condition"])
    doc = dict(sourceRevision=CONTEXT["sourceRevision"], portfolioSha256=coverage.sha(portfolio),
               publicSupportPromoted=False, portableExecution=False, targets=[{"target": t} for t in targets], rows=rows)
    files = {"portfolio.json": portfolio, "target-coverage.json": coverage.encode(doc),
             "portfolio.md": b"signed portfolio", "target-coverage.md": b"signed coverage"}
    publication = {"formatVersion": 1, "sourceRevision": CONTEXT["sourceRevision"],
                   "files": {name: {"sha256": coverage.sha(data), "sizeBytes": len(data)} for name, data in files.items()}}
    return files | {"publication.json": coverage.encode(publication)}


def composition_results():
    return [{"artifact": name, "construct": construct, "targets": list(targets), "claim": "bounded", "limits": ["No global support"]}
            for name, (_, construct, targets) in sorted(coverage.COMPOSITIONS.items())]


class CompositionCoverageTests(unittest.TestCase):
    def test_context_requires_exact_revisions_run_and_attempt(self):
        env = dict(zip(("EXPECTED_SHA", "WORKFLOW_REVISION", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"), CONTEXT.values()))
        self.assertEqual(CONTEXT, coverage.context(env))
        for key in env:
            with self.subTest(key=key), self.assertRaises(ValueError):
                coverage.context(env | {key: "0"})

    def test_ambiguous_json_is_rejected(self):
        for raw in ('{"status":"failed","status":"passed"}', '{"a":{"x":1,"x":2}}', '{"n":NaN}', '{} {}'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                coverage.decode(raw)

    def test_snapshot_rejects_symbolic_links_special_files_and_byte_budget(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "file").write_bytes(b"abc")
            frozen = coverage.snapshot(root)
            (root / "file").write_bytes(b"changed")
            self.assertEqual(b"abc", frozen["file"])
            (root / "alias").symlink_to(root / "file")
            with self.assertRaisesRegex(ValueError, "symbolic"):
                coverage.snapshot(root)
            (root / "alias").unlink()
            os.mkfifo(root / "pipe")
            with self.assertRaisesRegex(ValueError, "regular"):
                coverage.snapshot(root)
            (root / "pipe").unlink()
            with patch.object(coverage, "MAX_FILE", 2), self.assertRaisesRegex(ValueError, "budget"):
                coverage.snapshot(root)

    def test_receipt_binds_every_byte_image_and_no_promotion_flags(self):
        name = "retry-composition-runtime"
        files = proof_files()
        self.assertEqual(coverage.sha(files["proof.json"]), coverage.receipt(name, files, CONTEXT)["sha256"])
        with self.assertRaisesRegex(ValueError, "hash or size"):
            coverage.receipt(name, files | {"runtime/runtime.json": b"changed"}, CONTEXT)
        with self.assertRaisesRegex(ValueError, "inventory"):
            coverage.receipt(name, files | {"extra": b"unexpected"}, CONTEXT)
        for field, value in (("imageId", "sha256:" + "d" * 64), ("portableExecution", True),
                             ("publicSupportPromoted", "false"), ("status", "failed")):
            proof = coverage.decode(files["proof.json"]) | {field: value}
            with self.subTest(field=field), self.assertRaises(ValueError):
                coverage.receipt(name, files | {"proof.json": coverage.encode(proof)}, CONTEXT)

    def test_complete_same_attempt_jobs_admit_but_stale_failed_and_forged_inputs_do_not(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs, needs = {}, {}
            for name in sorted(set(coverage.COMPOSITIONS) | {coverage.PORTFOLIO}):
                files = signed_files() if name == coverage.PORTFOLIO else workspace_files() if name == "github-workspace-composition" else proof_files()
                inputs[name] = root / name
                coverage.write_tree(inputs[name], files)
                needs[name] = {"result": "success", "outputs": {"receipt": json.dumps(coverage.receipt(name, files, CONTEXT))}}
            admitted, pinned = coverage.admit(inputs, needs, CONTEXT)
            self.assertEqual(set(inputs), set(admitted))
            self.assertEqual(set(inputs), set(pinned))
            for field in CONTEXT:
                with self.subTest(field=field), self.assertRaises(ValueError):
                    coverage.admit(inputs, needs, CONTEXT | {field: "different"})
            for result in ("failure", "cancelled", "skipped"):
                bad = copy.deepcopy(needs)
                bad[coverage.PORTFOLIO]["result"] = result
                with self.assertRaisesRegex(ValueError, "did not succeed"):
                    coverage.admit(inputs, bad, CONTEXT)
            for bad in ({}, needs | {"invented": {}}, {k: v for k, v in needs.items() if k != coverage.PORTFOLIO}):
                with self.assertRaisesRegex(ValueError, "inventory"):
                    coverage.admit(inputs, bad, CONTEXT)
            path = inputs["retry-composition-runtime"] / "proof.json"
            path.write_bytes(path.read_bytes() + b"\n")
            with self.assertRaisesRegex(ValueError, "pinned"):
                coverage.admit(inputs, needs, CONTEXT)

    def test_compiler_bytes_include_names_source_and_projection(self):
        expected = {"source.flow": b"flow a", "manifest.json": b"{}", "Jenkinsfile": b"native"}
        coverage.same_inputs(expected, dict(expected))
        for bad in ({}, expected | {"extra": b""}, expected | {"Jenkinsfile": b"other"}, expected | {"source.flow": b"flow b"}):
            with self.assertRaisesRegex(ValueError, "compiler output"):
                coverage.same_inputs(bad, expected)

    def replay_fixture(self, mutate=None):
        # Compiler preparation is separately checked byte-for-byte. Exercise the new
        # consumer against the real existing native oracle with a minimal contract.
        action = {"type": "Action", "module": "git", "action": "checkout", "result": {"name": "work"},
                  "params": {"branch": {"type": "StringLiteral", "value": "d" * 40}}}
        expected = expectation({"flow": {"steps": [action]}}, {})
        artifacts = {"baseline.Jenkinsfile": b"native", "omitted.Jenkinsfile": b"omitted", "runs.json": b"[]"}
        variants = {name: {"case": "case", "variant": name, "text": text.decode()}
                    for name, text in (("baseline", b"native"), ("omitted", b"omitted"))}
        inventory = [{"id": name} for name in variants]
        observation = dict(task="task", node="1", result="success", revision="d" * 40, start=1, end=2, error=None)
        native = dict(status="completed", jenkins="2.580.1", java="25", plugins={"git": "5.10.1"}, runs=[
            dict(id=name, finished=True, inputs={}, artifactSha256=coverage.sha(variant["text"].encode()),
                 result="SUCCESS", observations=[observation] if name == "baseline" else []) for name, variant in variants.items()])
        from retry_composition_runtime import assess
        cases = assess({"case": expected}, {"case": {"task": "work"}}, variants, inventory, native)
        compiled = {"source.flow": b"authored"}
        files = proof_files({"inputs/source.flow": compiled["source.flow"], "runtime/runtime.json": coverage.encode(native)} |
                            {"artifacts/" + name: data for name, data in artifacts.items()})
        proof = coverage.decode(files["proof.json"]) | {"cases": cases}
        files["proof.json"] = coverage.encode(proof)
        if mutate:
            mutate(files)

        def prepare(inputs, output):
            coverage.write_tree(output / "artifacts", artifacts)
            return {"case": expected}, {"case": {"task": "work"}}, variants, inventory

        with tempfile.TemporaryDirectory() as temporary, patch("retry_composition_runtime.prepare", side_effect=prepare):
            return coverage.replay("retry-composition-runtime", files, compiled, Path(temporary), CONTEXT, None)

    def test_replay_accepts_native_baseline_and_rejected_mutant(self):
        result = self.replay_fixture()
        self.assertEqual("CI_BOUND_COMPOSITION_OBSERVATIONS", result["evidenceClass"])
        self.assertIsNone(result["cases"][0]["rejection"])
        self.assertIsNotNone(result["cases"][1]["rejection"])

    def test_asserted_pass_cannot_hide_native_baseline_failure_or_surviving_mutant(self):
        def change(files, baseline):
            native = coverage.decode(files["runtime/runtime.json"])
            if baseline:
                native["runs"][0]["observations"] = []
            else:
                native["runs"][1]["observations"] = native["runs"][0]["observations"]
            files["runtime/runtime.json"] = coverage.encode(native)
        for baseline in (True, False):
            with self.subTest(baseline=baseline), self.assertRaisesRegex(ValueError, "Baseline failed or native mutant survived"):
                self.replay_fixture(lambda files: change(files, baseline))

    def test_replay_rejects_changed_envelope_and_claimed_case(self):
        with self.assertRaisesRegex(ValueError, "envelope or mutant"):
            self.replay_fixture(lambda files: files.update({"artifacts/baseline.Jenkinsfile": b"changed"}))
        def change(files):
            proof = coverage.decode(files["proof.json"])
            proof["cases"] = []
            files["proof.json"] = coverage.encode(proof)
        with self.assertRaisesRegex(ValueError, "independent native replay"):
            self.replay_fixture(change)

    def test_reconciliation_preserves_signed_rows_and_all_missing_composition_gaps(self):
        files = signed_files()
        before = copy.deepcopy(files)
        report = coverage.derive(files, composition_results(), {}, CONTEXT)
        self.assertEqual(before, files)
        self.assertEqual(21, len(report["rows"]))
        self.assertEqual(7, sum(bool(row["compositionIds"]) for row in report["rows"]))
        for row in report["rows"]:
            self.assertFalse(row["targetWideCertificationEstablished"])
            if row["construct"] == "VALUE_STATE_CONTINUITY" or row["target"].startswith("unimplemented"):
                self.assertEqual("NO_COMPOSITION_EVIDENCE", row["compositionStatus"])
                self.assertEqual([], row["compositionIds"])
            if row["construct"] == "ARTIFACTS_WORKSPACES":
                self.assertEqual(["github-workspace-composition"] if row["target"] == "github-actions" else [], row["compositionIds"])
        original = coverage.decode(files["target-coverage.json"])["rows"]
        self.assertEqual([(r["status"], r["scenarioIds"]) for r in original],
                         [(r["signedPortfolioStatus"], r["signedScenarioIds"]) for r in report["rows"]])
        text = coverage.markdown(report)
        self.assertIn("&lt;&amp;&#124;&#96;", text)
        self.assertNotIn("unimplemented<&|`target", text)
        self.assertFalse(report["publicSupportPromoted"])
        self.assertFalse(report["portableExecution"])

    def test_coverage_cannot_borrow_another_portfolio_or_missing_producer(self):
        files = signed_files()
        with self.assertRaisesRegex(ValueError, "another portfolio"):
            coverage.derive(files | {"portfolio.json": files["portfolio.json"] + b"\n"}, composition_results(), {}, CONTEXT)
        with self.assertRaisesRegex(ValueError, "inventory"):
            coverage.derive(files, composition_results()[:-1], {}, CONTEXT)
        results = composition_results()
        results[0]["targets"] = ["invented"]
        with self.assertRaisesRegex(ValueError, "declared target"):
            coverage.derive(files, results, {}, CONTEXT)

    def test_publication_is_atomic_and_preserves_signed_bytes_and_receipts(self):
        files = signed_files()
        report = coverage.derive(files, composition_results(), {}, CONTEXT)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "published"
            coverage.publish(output, report, files)
            frozen = coverage.snapshot(output)
            manifest = coverage.decode(frozen["publication.json"])
            coverage.verify_inventory(frozen, manifest, "publication.json", "bytes")
            for name, data in files.items():
                self.assertEqual(data, frozen["signed-publication.json" if name == "publication.json" else name])
            with self.assertRaisesRegex(ValueError, "fresh"):
                coverage.publish(output, report, files)
            self.assertEqual(frozen, coverage.snapshot(output))
            original = Path.write_bytes
            def fail(path, data):
                if path.name == "composition-coverage.md":
                    raise OSError("injected failure")
                return original(path, data)
            with patch.object(Path, "write_bytes", fail), self.assertRaises(OSError):
                coverage.publish(root / "incomplete", report, files)
            self.assertEqual([output], list(root.iterdir()))

    def test_workspace_receipt_binds_native_job_contract_without_a_container_identity(self):
        files = workspace_files()
        pinned = coverage.receipt("github-workspace-composition", files, CONTEXT)
        self.assertEqual(coverage.sha(files["proof.json"]), pinned["sha256"])
        for field, value in (("formatVersion", 0), ("formatVersion", True), ("runtimeKind", "container"), ("imageId", IMAGE),
                             ("status", "PASS"), ("publicSupportPromoted", True), ("attempt", "2")):
            proof = coverage.decode(files["proof.json"]) | {field: value}
            with self.subTest(field=field), self.assertRaises(ValueError):
                coverage.receipt("github-workspace-composition", files | {"proof.json": coverage.encode(proof)}, CONTEXT)
        for name in files:
            if name == "proof.json": continue
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "hash or size"):
                coverage.receipt("github-workspace-composition", files | {name: files[name] + b"\n"}, CONTEXT)
            with self.subTest(missing=name), self.assertRaisesRegex(ValueError, "inventory"):
                coverage.receipt("github-workspace-composition", {k: v for k, v in files.items() if k != name}, CONTEXT)
        with self.assertRaisesRegex(ValueError, "inventory"):
            coverage.receipt("github-workspace-composition", files | {"extra": b""}, CONTEXT)

    def replay_workspace(self, mutate=None, jobs=None):
        files = workspace_files()
        if mutate: mutate(files)
        # Rehash and repin even forged archives: replay must still detect semantic drift.
        proof = coverage.decode(files["proof.json"])
        proof["files"] = {name: {"sha256": coverage.sha(data), "bytes": len(data)} for name, data in files.items() if name != "proof.json"}
        files["proof.json"] = coverage.encode(proof)
        coverage.receipt("github-workspace-composition", files, CONTEXT)
        with tempfile.TemporaryDirectory() as temporary:
            return coverage.replay("github-workspace-composition", files, compiled_files(), Path(temporary), CONTEXT,
                                   lambda: api_jobs() if jobs is None else jobs, ROOT)

    def test_workspace_replay_uses_current_compiler_and_same_attempt_api(self):
        result = self.replay_workspace()
        self.assertEqual("ARTIFACTS_WORKSPACES", result["construct"])
        self.assertEqual(["github-actions"], result["targets"])
        self.assertEqual("github-actions-jobs", result["runtimeKind"])
        self.assertNotIn("imageId", result)
        self.assertEqual(5, len(result["compilerInputDigests"]))
        self.assertEqual(10, len(result["cases"]))

    def test_workspace_replay_rejects_rehashed_compiler_and_envelope_drift(self):
        for name in compiled_files():
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "compiler output"):
                self.replay_workspace(lambda files: files.update({name: files[name] + b"\n"}))

    def test_workspace_replay_rejects_claimed_pass_with_lost_bytes_or_wrong_selection(self):
        for mutation in ("bytes", "selection", "mutant"):
            def change(files):
                results = coverage.decode(files["observations.json"])
                if mutation == "bytes":
                    inventory = coverage.decode(results["docker_build_1"]["outputs"]["inventory"])
                    del inventory[".hidden"]
                    results["docker_build_1"]["outputs"]["inventory"] = json.dumps(inventory)
                elif mutation == "selection": results["docker_build_3"] = copy.deepcopy(results["docker_build_1"])
                else: results["missing-transfer-mutant"]["result"] = "skipped"
                files["observations.json"] = coverage.encode(results)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError): self.replay_workspace(change)

    def test_workspace_replay_rejects_rehashed_job_records_and_asserted_scope(self):
        def change_jobs(files):
            rows = coverage.decode(files["github-jobs.json"])
            rows[0]["id"] += 100
            files["github-jobs.json"] = coverage.encode(rows)
        with self.assertRaisesRegex(ValueError, "same-attempt API"): self.replay_workspace(change_jobs)
        for field, value in (("cases", []), ("scope", {}), ("claim", "all workspaces supported"), ("limits", [])):
            def change(files):
                files["proof.json"] = coverage.encode(coverage.decode(files["proof.json"]) | {field: value})
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "independent replay"):
                self.replay_workspace(change)
        jobs = api_jobs(); jobs[0]["run_attempt"] = 2
        with self.assertRaisesRegex(ValueError, "provenance"): self.replay_workspace(jobs=jobs)

    def test_workspace_replay_rejects_rewritten_expected_inventory(self):
        def change(files):
            prepared = coverage.decode(files["expected.json"])
            prepared["inventory"]["payload.txt"]["sha256"] = "0" * 64
            files["expected.json"] = coverage.encode(prepared)
        with self.assertRaisesRegex(ValueError, "Prepared evidence"):
            self.replay_workspace(change)


if __name__ == "__main__":
    unittest.main()
