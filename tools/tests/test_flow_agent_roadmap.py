from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from flow_agent_roadmap import find_unique_next_roadmap_item


class FlowAgentRoadmapTests(unittest.TestCase):
    def test_resolves_next_item_from_declared_active_repair_track(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            agent = root / ".flow-agent"
            agent.mkdir()
            main = agent / "roadmap.yaml"
            repair = agent / "repair.yaml"
            main.write_text(
                'project: Flow Core\nactiveRepairTrack: ".flow-agent/repair.yaml"\n'
                'roadmap:\n  - version: "1.0"\n    status: planned\n',
                encoding="utf-8",
            )
            repair.write_text(
                'items:\n  - version: "0.9.5.7.7"\n'
                '    name: Policy-Driven Safety Prelude\n'
                '    purpose: Replace hardcoded environment assumptions.\n'
                '    status: next\n',
                encoding="utf-8",
            )

            item = find_unique_next_roadmap_item(root, main)

            self.assertEqual("0.9.5.7.7", item.version)
            self.assertEqual("Policy-Driven Safety Prelude", item.name)
            self.assertEqual(repair.resolve(), item.source)

    def test_rejects_multiple_next_items_across_active_tracks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            agent = root / ".flow-agent"
            agent.mkdir()
            main = agent / "roadmap.yaml"
            repair = agent / "repair.yaml"
            main.write_text(
                'activeRepairTrack: ".flow-agent/repair.yaml"\n'
                'roadmap:\n  - version: "0.9.5.8"\n    status: next\n',
                encoding="utf-8",
            )
            repair.write_text(
                'items:\n  - version: "0.9.5.7.7"\n    status: next\n',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "Multiple roadmap items"):
                find_unique_next_roadmap_item(root, main)

    def test_rejects_active_repair_track_outside_repository(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            agent = root / ".flow-agent"
            agent.mkdir()
            main = agent / "roadmap.yaml"
            main.write_text(
                'activeRepairTrack: "../outside.yaml"\n',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "inside the repository"):
                find_unique_next_roadmap_item(root, main)

    def test_rejects_missing_next_item(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            agent = root / ".flow-agent"
            agent.mkdir()
            main = agent / "roadmap.yaml"
            main.write_text(
                'roadmap:\n  - version: "0.9.5.8"\n    status: planned\n',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "No roadmap item"):
                find_unique_next_roadmap_item(root, main)


if __name__ == "__main__":
    unittest.main()
