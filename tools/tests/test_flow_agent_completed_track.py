from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from flow_agent_lifecycle import (
    resolve_primary_roadmap_focus,
    validate_roadmap_structure_with_completed_track,
)


COMPLETED_CORE_ITEM = '''status: completed
stream: core
items:
  - version: "0.9.7.10"
    name: "Bounded Semantic Closure Gate"
    type: "architecture-closure"
    status: completed
    purpose: "Verify declared evidence with a finite checklist."
    universalInvariant: "The track closes by falsifiable evidence."
    forbiddenScope:
      - "No new requirement introduced during closure."
    completionEvidence:
      - "Exact-head and merge-candidate evidence pass."
'''


class FlowAgentCompletedTrackTests(unittest.TestCase):
    def _write_repository(
        self,
        root: Path,
        core: str,
        closure_status: str = "completed",
        include_next_projection: bool = False,
    ) -> Path:
        agent = root / ".flow-agent"
        agent.mkdir()
        next_projection = (
            '  nextCoreItem: "0.9.7.10"\n'
            '  nextCoreItemName: "Bounded Semantic Closure Gate"\n'
            '  nextCoreItemStatus: "next"\n'
            if include_next_projection
            else ""
        )
        main = agent / "roadmap.yaml"
        main.write_text(
            'primaryRoadmapStream: core\n'
            'roadmapIndex:\n'
            '  activeRoadmaps:\n'
            '    core: ".flow-agent/roadmap-core.yaml"\n'
            '    adapters: ".flow-agent/roadmap-adapters.yaml"\n'
            '    conformance: ".flow-agent/roadmap-conformance.yaml"\n'
            'currentDecision:\n'
            '  completedItem: "0.9.7.10"\n'
            '  completedItemName: "Bounded Semantic Closure Gate"\n'
            '  activeCorrectionWorkPackage: ""\n'
            '  closureItem: "0.9.7.10"\n'
            '  closureItemName: "Bounded Semantic Closure Gate"\n'
            f'  closureItemStatus: "{closure_status}"\n'
            + next_projection,
            encoding="utf-8",
        )
        (agent / "roadmap-core.yaml").write_text(core, encoding="utf-8")
        (agent / "roadmap-adapters.yaml").write_text(
            'stream: adapters\nitems:\n'
            '  - version: "A0.1"\n'
            '    status: planned\n'
            '    dependsOnCore: "0.9.7.10"\n',
            encoding="utf-8",
        )
        (agent / "roadmap-conformance.yaml").write_text(
            'stream: conformance\nitems:\n'
            '  - version: "C0.1"\n'
            '    status: planned\n'
            '    dependsOnCore: "0.9.7.10"\n',
            encoding="utf-8",
        )
        return main

    def test_accepts_structurally_completed_primary_track(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._write_repository(root, COMPLETED_CORE_ITEM)

            validate_roadmap_structure_with_completed_track(root, main)
            focus = resolve_primary_roadmap_focus(root, main)

            self.assertEqual("completed", focus.lifecycle)
            self.assertEqual("0.9.7.10", focus.item.version)
            self.assertEqual("Bounded Semantic Closure Gate", focus.item.name)

    def test_rejects_missing_next_when_track_is_not_completed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._write_repository(
                root,
                COMPLETED_CORE_ITEM.replace("status: completed\nstream", "status: active\nstream", 1),
            )

            with self.assertRaisesRegex(RuntimeError, "track status: completed"):
                validate_roadmap_structure_with_completed_track(root, main)

    def test_rejects_completed_track_with_incomplete_core_item(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._write_repository(
                root,
                COMPLETED_CORE_ITEM.replace("    status: completed", "    status: planned"),
            )

            with self.assertRaisesRegex(RuntimeError, "non-completed items"):
                validate_roadmap_structure_with_completed_track(root, main)

    def test_rejects_completed_track_without_completed_closure_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._write_repository(root, COMPLETED_CORE_ITEM, closure_status="next")

            with self.assertRaisesRegex(RuntimeError, "closureItemStatus: completed"):
                validate_roadmap_structure_with_completed_track(root, main)

    def test_rejects_completed_track_with_next_projection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._write_repository(
                root,
                COMPLETED_CORE_ITEM,
                include_next_projection=True,
            )

            with self.assertRaisesRegex(RuntimeError, "must not retain nextCoreItem metadata"):
                validate_roadmap_structure_with_completed_track(root, main)

    def test_rejects_completed_track_with_stale_completed_item(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._write_repository(root, COMPLETED_CORE_ITEM)
            main.write_text(
                main.read_text(encoding="utf-8").replace(
                    'completedItem: "0.9.7.10"',
                    'completedItem: "0.9.7.9"',
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "same completedItem and closureItem"):
                validate_roadmap_structure_with_completed_track(root, main)


if __name__ == "__main__":
    unittest.main()
