from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from flow_agent_roadmap import find_unique_next_roadmap_item, validate_roadmap_structure


CORE_BLOCKED = '''stream: core
items:
  - version: "0.9.7.9"
    name: "Intent Lowering and Diagnostic Honesty"
    status: correction-required
    purpose: "Preserve or diagnose every meaningful input."
    universalInvariant: "Accepted information never disappears silently."
    forbiddenScope:
      - "No silent loss."
    completionEvidence:
      - "Correction evidence is pending."
  - version: "0.9.7.10"
    name: "Bounded Semantic Closure Gate"
    status: blocked
    purpose: "Verify declared evidence."
    universalInvariant: "Closure is falsifiable."
    forbiddenScope:
      - "No closure while correction is active."
    completionEvidence:
      - "All corrections pass."
'''


class FlowAgentActiveCorrectionTests(unittest.TestCase):
    def _repository(self, root: Path, with_core_next: bool = False) -> Path:
        agent = root / ".flow-agent"
        packages = agent / "work-packages"
        packages.mkdir(parents=True)
        main = agent / "roadmap.yaml"
        main.write_text(
            'primaryRoadmapStream: core\n'
            'roadmapIndex:\n'
            '  activeRoadmaps:\n'
            '    core: ".flow-agent/roadmap-core.yaml"\n'
            '    adapters: ".flow-agent/roadmap-adapters.yaml"\n'
            '    conformance: ".flow-agent/roadmap-conformance.yaml"\n'
            '    architecture: ".flow-agent/roadmap-architecture.yaml"\n'
            'currentDecision:\n'
            '  activeCorrectionWorkPackage: ".flow-agent/work-packages/correction.yaml"\n',
            encoding="utf-8",
        )
        core = CORE_BLOCKED.replace("status: blocked", "status: next") if with_core_next else CORE_BLOCKED
        (agent / "roadmap-core.yaml").write_text(core, encoding="utf-8")
        (agent / "roadmap-adapters.yaml").write_text(
            'stream: adapters\nitems:\n  - version: "A0.1"\n    status: planned\n    dependsOnCore: "0.9.7.9"\n',
            encoding="utf-8",
        )
        (agent / "roadmap-conformance.yaml").write_text(
            'stream: conformance\nitems:\n  - version: "C0.1"\n    status: planned\n    dependsOnCore: "0.9.7.9"\n',
            encoding="utf-8",
        )
        (agent / "roadmap-architecture.yaml").write_text(
            'stream: architecture\nitems:\n  - version: "AR0.1"\n    status: planned\n    dependsOnConformance: "C0.1"\n',
            encoding="utf-8",
        )
        (packages / "correction.yaml").write_text(
            'version: "0.9.7.9.8"\n'
            'name: "Closure-Blocking Integrity"\n'
            'type: "bounded-correction"\n'
            'status: active\n'
            'parentCoreItem: "0.9.7.9"\n'
            'objective: "Repair closure-blocking evidence boundaries."\n',
            encoding="utf-8",
        )
        return main

    def test_active_correction_becomes_selected_work_item_while_core_gate_is_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._repository(root)

            validate_roadmap_structure(root, main)
            item = find_unique_next_roadmap_item(root, main)

            self.assertEqual("0.9.7.9.8", item.version)
            self.assertEqual("correction", item.stream)
            self.assertEqual("bounded-correction", item.item_type)

    def test_rejects_active_correction_and_core_next_item_at_same_time(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._repository(root, with_core_next=True)

            with self.assertRaisesRegex(RuntimeError, "must remain blocked"):
                validate_roadmap_structure(root, main)

    def test_rejects_completed_work_package_declared_as_active(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._repository(root)
            package = root / ".flow-agent/work-packages/correction.yaml"
            package.write_text(package.read_text().replace("status: active", "status: complete"), encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "status: active"):
                validate_roadmap_structure(root, main)


if __name__ == "__main__":
    unittest.main()
