from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from flow_agent_roadmap import (
    find_unique_next_roadmap_item,
    next_items_by_stream,
    validate_roadmap_structure,
)


VALID_CORE_ITEM = '''items:
  - version: "0.9.7.1"
    name: "Canonical Intent Meaning"
    type: "semantic-contract"
    status: next
    purpose: "Separate universal meaning from provider binding."
    universalInvariant: "Equivalent intent has one canonical meaning."
    forbiddenScope:
      - "No concrete implementation selection."
    requiredOutcome:
      - "Provider binding remains explicit."
    completionEvidence:
      - "Negative tests reject implicit provider selection."
'''


class FlowAgentRoadmapTests(unittest.TestCase):
    def _write_split_roadmaps(self, root: Path, core: str = VALID_CORE_ITEM) -> Path:
        agent = root / ".flow-agent"
        agent.mkdir()
        main = agent / "roadmap.yaml"
        main.write_text(
            'primaryRoadmapStream: core\n'
            'roadmapIndex:\n'
            '  activeRoadmaps:\n'
            '    core: ".flow-agent/roadmap-core.yaml"\n'
            '    adapters: ".flow-agent/roadmap-adapters.yaml"\n'
            '    conformance: ".flow-agent/roadmap-conformance.yaml"\n',
            encoding="utf-8",
        )
        (agent / "roadmap-core.yaml").write_text("stream: core\n" + core, encoding="utf-8")
        (agent / "roadmap-adapters.yaml").write_text(
            'stream: adapters\nitems:\n  - version: "A0.1"\n    status: planned\n',
            encoding="utf-8",
        )
        (agent / "roadmap-conformance.yaml").write_text(
            'stream: conformance\nitems:\n  - version: "C0.1"\n    status: planned\n',
            encoding="utf-8",
        )
        return main

    def test_resolves_next_item_from_primary_core_stream(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._write_split_roadmaps(root)

            item = find_unique_next_roadmap_item(root, main)

            self.assertEqual("0.9.7.1", item.version)
            self.assertEqual("core", item.stream)
            self.assertEqual((root / ".flow-agent/roadmap-core.yaml").resolve(), item.source)

    def test_allows_independent_next_item_per_stream(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._write_split_roadmaps(root)
            adapter = root / ".flow-agent/roadmap-adapters.yaml"
            adapter.write_text(
                'stream: adapters\nitems:\n  - version: "A0.1"\n    status: next\n',
                encoding="utf-8",
            )

            items = next_items_by_stream(root, main)

            self.assertEqual({"core", "adapters"}, set(items))
            self.assertEqual("0.9.7.1", find_unique_next_roadmap_item(root, main).version)

    def test_rejects_multiple_next_items_within_one_stream(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            core = VALID_CORE_ITEM + VALID_CORE_ITEM.replace('"0.9.7.1"', '"0.9.7.2"')
            main = self._write_split_roadmaps(root, core)

            with self.assertRaisesRegex(RuntimeError, "Multiple roadmap items"):
                validate_roadmap_structure(root, main)

    def test_rejects_active_roadmap_outside_repository(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            agent = root / ".flow-agent"
            agent.mkdir()
            main = agent / "roadmap.yaml"
            main.write_text(
                'primaryRoadmapStream: core\nroadmapIndex:\n  activeRoadmaps:\n'
                '    core: "../outside.yaml"\n'
                '    adapters: ".flow-agent/a.yaml"\n'
                '    conformance: ".flow-agent/c.yaml"\n',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "inside the repository"):
                validate_roadmap_structure(root, main)

    def test_rejects_missing_primary_next_item(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._write_split_roadmaps(root, VALID_CORE_ITEM.replace("status: next", "status: planned"))

            with self.assertRaisesRegex(RuntimeError, "No roadmap item"):
                validate_roadmap_structure(root, main)

    def test_rejects_missing_required_stream(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            agent = root / ".flow-agent"
            agent.mkdir()
            main = agent / "roadmap.yaml"
            main.write_text(
                'primaryRoadmapStream: core\nroadmapIndex:\n  activeRoadmaps:\n'
                '    core: ".flow-agent/roadmap-core.yaml"\n'
                '    adapters: ".flow-agent/roadmap-adapters.yaml"\n',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "missing streams: conformance"):
                validate_roadmap_structure(root, main)

    def test_rejects_concrete_target_or_tool_in_core_scope(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            core = VALID_CORE_ITEM.replace(
                "Separate universal meaning from provider binding.",
                "Lower canonical meaning directly to Jenkins.",
            )
            main = self._write_split_roadmaps(root, core)

            with self.assertRaisesRegex(RuntimeError, "concrete target or tool scope"):
                validate_roadmap_structure(root, main)

    def test_rejects_core_item_without_required_governance_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            core = '''items:
  - version: "0.9.7.1"
    name: "Canonical Intent Meaning"
    type: "semantic-contract"
    status: next
    purpose: "Separate universal meaning from provider binding."
'''
            main = self._write_split_roadmaps(root, core)

            with self.assertRaisesRegex(RuntimeError, "missing required fields"):
                validate_roadmap_structure(root, main)

    def test_adapter_roadmap_may_contain_concrete_target_names(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._write_split_roadmaps(root)
            adapter = root / ".flow-agent/roadmap-adapters.yaml"
            adapter.write_text(
                'stream: adapters\nitems:\n  - version: "A0.1"\n'
                '    name: "Jenkins adapter reassessment"\n    status: planned\n',
                encoding="utf-8",
            )

            validate_roadmap_structure(root, main)


if __name__ == "__main__":
    unittest.main()
