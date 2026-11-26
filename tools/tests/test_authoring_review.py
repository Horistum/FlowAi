"""Coverage and publication boundaries for independent review of a saved candidate."""
from contextlib import redirect_stdout
import io
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
try:
    from authoring_host.contracts import HostError, canonical, digest
    from authoring_host.operation_expectations import expected_checks
    from authoring_host.target_output import export_result
finally:
    sys.path.pop(0)


class OperationCoverageTests(unittest.TestCase):
    catalog = [{"capability": name, "requiredParams": [], "optionalParams": ["resource", "scope"]}
               for name in ("VERIFY", "BACKUP", "NEW_OPERATION")]

    def test_all_occurrences_count_and_new_catalog_operations_are_excluded(self):
        checks = expected_checks([["verify", "resource=a"], ["VERIFY", "resource=b"], ["VERIFY", "resource=a"]],
                                 [], False, self.catalog, only=True)
        self.assertEqual([({"resource": "a"}, 2, 2), ({"resource": "b"}, 1, 1), ({}, 3, 3)],
                         [(r["selector"]["parameters"], r["minimum"], r["maximum"])
                          for r in checks if r["kind"] == "capability-count"])
        self.assertEqual(["BACKUP", "NEW_OPERATION"], [r["capability"] for r in checks if r["kind"] == "forbid-capability"])

    def test_one_node_cannot_satisfy_two_ambiguous_slots_or_order_itself(self):
        for terms, ordered in (([["VERIFY", "resource=a"], ["VERIFY", "scope=b"]], False),
                               ([["VERIFY", "resource=a"], ["VERIFY", "resource=a"]], True)):
            with self.subTest(terms=terms), self.assertRaises(HostError):
                expected_checks(terms, [], ordered, self.catalog, only=True)

    def test_generated_rules_count_towards_the_compiler_limit(self):
        catalog = self.catalog + [dict(self.catalog[0], capability=f"EXTRA_{i}") for i in range(256)]
        with self.assertRaisesRegex(HostError, "256 checks"):
            expected_checks([["BACKUP"]], [], False, catalog, only=True)

    def test_existing_partial_and_negative_only_checks_keep_their_meaning(self):
        self.assertEqual([{"kind": "forbid-capability", "capability": "VERIFY"}],
                         expected_checks([], ["VERIFY"], False, self.catalog))
        self.assertNotIn("maximum", expected_checks([["VERIFY"]], [], False, self.catalog)[0])
        with self.assertRaises(HostError):
            expected_checks([], ["VERIFY"], False, self.catalog, only=True)


class TargetOutputTests(unittest.TestCase):
    def test_inconsistent_receipts_do_not_publish_files(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "exports"
            name = "workflow.yml"
            (root / name).write_bytes(b"rendered file\n")
            receipt_path = root / "target-artifact-evidence.json"
            receipt = {"target": "target-a", "kind": "EXECUTABLE_TARGET", "renderMode": "EXECUTABLE",
                       "artifactFileName": name, "artifactSha256": digest((root / name).read_bytes())}
            review = {"semanticAcceptance": "accepted", "graphDigest": "graph", "target": "target-a", "renderRequested": True, "renderAuthorized": True, "exitCode": 0,
                      "requestFidelity": "requirements-satisfied", "artifacts": [receipt_path.name, name]}
            fidelity = {"state": "REQUIREMENTS_SATISFIED"}
            for bad in ({"kind": "REVIEW_EVIDENCE"}, {"artifactSha256": "0" * 64}, {"target": "target-b"},
                        {"artifactFileName": "../workflow.yml"}, {"renderMode": "REVIEW_ONLY"}):
                with self.subTest(bad=bad), self.assertRaises(HostError):
                    receipt_path.write_bytes(canonical({**receipt, **bad}))
                    export_result("target-a", output, root, review, fidelity)
                self.assertFalse(output.exists())
            receipt_path.write_bytes(canonical(receipt))
            with redirect_stdout(io.StringIO()):
                export_result("target-a", output, root, review, fidelity)
            self.assertEqual((root / name).read_bytes(), (output / name).read_bytes())
            with self.assertRaisesRegex(HostError, "already exists"):
                export_result("target-a", output, root, review, fidelity)
            self.assertEqual([output / name], list(output.iterdir()))
