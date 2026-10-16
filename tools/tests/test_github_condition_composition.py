import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("composition", Path(__file__).parents[1] / "verify_github_condition_composition.py")
composition = importlib.util.module_from_spec(spec)
spec.loader.exec_module(composition)


class NativeCompositionObservationTests(unittest.TestCase):
    def results(self, enabled):
        result = {"prepare": {"result": "success", "outputs": {}},
                  "old-default-mutant": {"result": "skipped", "outputs": {}}}
        for name, executed in [(f"git_checkout_{n}", enabled if n in (2, 3) else not enabled if n == 4 else True)
                               for n in range(1, 8)] + [("inverted-guard-mutant", not enabled)]:
            result[name] = {"result": "success" if executed else "skipped",
                            "outputs": {"revision": composition.REVISION} if executed else {}}
        return result

    def test_both_native_branch_assignments(self):
        for enabled in (True, False):
            composition.verify(self.results(enabled), enabled)

    def test_failed_cancelled_missing_and_reversed_observations_fail(self):
        for enabled in (True, False):
            for name in self.results(enabled):
                for state in ("failure", "cancelled"):
                    results = self.results(enabled)
                    results[name]["result"] = state
                    with self.assertRaises(ValueError):
                        composition.verify(results, enabled)
                results = self.results(enabled)
                del results[name]
                with self.assertRaises(ValueError):
                    composition.verify(results, enabled)
            for name in ("git_checkout_5", "git_checkout_6"):
                results = self.results(enabled)
                results[name] = {"result": "skipped", "outputs": {}}
                with self.assertRaises(ValueError):
                    composition.verify(results, enabled)
            results = self.results(enabled)
            results["git_checkout_1"]["outputs"]["revision"] = "a" * 40
            with self.assertRaises(ValueError):
                composition.verify(results, enabled)
