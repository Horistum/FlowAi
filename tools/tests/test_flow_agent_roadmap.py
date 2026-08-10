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
    name: "Governance Signal Integrity"
    type: "validation-honesty"
    status: next
    purpose: "Repair governance signal integrity."
    universalInvariant: "Governance outcomes have honest polarity."
    forbiddenScope:
      - "No reversed dependency direction."
    requiredOutcome:
      - "Signals match the property measured."
    completionEvidence:
      - "Behavioral tests verify analyzer outcomes."
'''

COMPLETED_CORE_ITEM = VALID_CORE_ITEM.replace("status: next", "status: completed")


class FlowAgentRoadmapTests(unittest.TestCase):
    def _write_split_roadmaps(
        self,
        root: Path,
        core: str = VALID_CORE_ITEM,
        adapters: str | None = None,
        conformance: str | None = None,
        architecture: str | None = None,
        primary_stream: str = "core",
    ) -> Path:
        agent = root / ".flow-agent"
        agent.mkdir()
        main = agent / "roadmap.yaml"
        main.write_text(
            f'primaryRoadmapStream: {primary_stream}\n'
            'roadmapIndex:\n'
            '  activeRoadmaps:\n'
            '    core: ".flow-agent/roadmap-core.yaml"\n'
            '    adapters: ".flow-agent/roadmap-adapters.yaml"\n'
            '    conformance: ".flow-agent/roadmap-conformance.yaml"\n'
            '    architecture: ".flow-agent/roadmap-architecture.yaml"\n',
            encoding="utf-8",
        )
        (agent / "roadmap-core.yaml").write_text("stream: core\n" + core, encoding="utf-8")
        (agent / "roadmap-adapters.yaml").write_text(
            adapters
            or 'stream: adapters\nitems:\n'
            '  - version: "A0.1"\n'
            '    status: planned\n'
            '    dependsOnCore: "0.9.7.1"\n',
            encoding="utf-8",
        )
        (agent / "roadmap-conformance.yaml").write_text(
            conformance
            or 'stream: conformance\nitems:\n'
            '  - version: "C0.1"\n'
            '    status: planned\n'
            '    dependsOnCore: "0.9.7.1"\n',
            encoding="utf-8",
        )
        (agent / "roadmap-architecture.yaml").write_text(
            architecture
            or 'stream: architecture\nitems:\n'
            '  - version: "AR0.1"\n'
            '    status: planned\n'
            '    dependsOnConformance: "C0.1"\n',
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

    def test_resolves_next_item_from_primary_adapter_stream_after_core_completion(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adapters = (
                'stream: adapters\nitems:\n  - version: "A0.1"\n'
                '    name: "Adapter Portfolio Reassessment"\n'
                '    type: "adapter-governance"\n'
                '    status: next\n'
                '    purpose: "Reassess existing adapter claims."\n'
                '    dependsOnCore: "0.9.7.1"\n'
            )
            main = self._write_split_roadmaps(
                root,
                core=COMPLETED_CORE_ITEM,
                adapters=adapters,
                primary_stream="adapters",
            )

            validate_roadmap_structure(root, main)
            item = find_unique_next_roadmap_item(root, main)

            self.assertEqual("A0.1", item.version)
            self.assertEqual("Adapter Portfolio Reassessment", item.name)
            self.assertEqual("adapters", item.stream)
            self.assertEqual((root / ".flow-agent/roadmap-adapters.yaml").resolve(), item.source)

    def test_allows_independent_next_item_per_stream(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adapters = (
                'stream: adapters\nitems:\n  - version: "A0.1"\n'
                '    status: next\n    dependsOnCore: "0.9.7.1"\n'
            )
            main = self._write_split_roadmaps(root, adapters=adapters)

            items = next_items_by_stream(root, main)

            self.assertEqual({"core", "adapters"}, set(items))
            self.assertEqual("0.9.7.1", find_unique_next_roadmap_item(root, main).version)

    def test_resolves_next_item_from_architecture_after_conformance_completion(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            conformance = (
                'stream: conformance\nitems:\n  - version: "C0.1"\n'
                '    status: completed\n    dependsOnCore: "0.9.7.1"\n'
            )
            architecture = (
                'stream: architecture\nitems:\n  - version: "AR0.1"\n'
                '    name: "Authority Responsibility Consolidation"\n'
                '    type: "cross-stream-architecture"\n'
                '    status: next\n'
                '    purpose: "Consolidate authority ownership without changing frozen contracts."\n'
                '    dependsOnConformance: "C0.1"\n'
            )
            main = self._write_split_roadmaps(
                root,
                core=COMPLETED_CORE_ITEM,
                conformance=conformance,
                architecture=architecture,
                primary_stream="architecture",
            )

            validate_roadmap_structure(root, main)
            item = find_unique_next_roadmap_item(root, main)

            self.assertEqual("AR0.1", item.version)
            self.assertEqual("architecture", item.stream)

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
                '    conformance: ".flow-agent/c.yaml"\n'
                '    architecture: ".flow-agent/ar.yaml"\n',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "inside the repository"):
                validate_roadmap_structure(root, main)

    def test_rejects_missing_primary_next_item(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._write_split_roadmaps(
                root,
                VALID_CORE_ITEM.replace("status: next", "status: planned"),
            )

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
                '    adapters: ".flow-agent/roadmap-adapters.yaml"\n'
                '    conformance: ".flow-agent/roadmap-conformance.yaml"\n',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "missing streams: architecture"):
                validate_roadmap_structure(root, main)

    def test_rejects_core_item_without_required_governance_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            core = '''items:
  - version: "0.9.7.1"
    name: "Governance Signal Integrity"
    type: "validation-honesty"
    status: next
    purpose: "Repair governance signal integrity."
'''
            main = self._write_split_roadmaps(root, core)

            with self.assertRaisesRegex(RuntimeError, "missing required fields"):
                validate_roadmap_structure(root, main)

    def test_rejects_core_dependency_on_adapter_stream(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            core = VALID_CORE_ITEM.replace(
                '    completionEvidence:\n',
                '    dependsOnAdapters: "A0.1"\n    completionEvidence:\n',
            )
            main = self._write_split_roadmaps(root, core)

            with self.assertRaisesRegex(RuntimeError, "reverses roadmap ownership direction"):
                validate_roadmap_structure(root, main)

    def test_rejects_unknown_core_dependency_from_adapter(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adapters = (
                'stream: adapters\nitems:\n  - version: "A0.1"\n'
                '    status: planned\n    dependsOnCore: "0.9.7.99"\n'
            )
            main = self._write_split_roadmaps(root, adapters=adapters)

            with self.assertRaisesRegex(RuntimeError, "references unknown Core item"):
                validate_roadmap_structure(root, main)

    def test_rejects_unknown_adapter_dependency_from_adapter(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adapters = (
                'stream: adapters\nitems:\n  - version: "A0.1"\n'
                '    status: planned\n'
                '    dependsOnCore: "0.9.7.1"\n'
                '    dependsOnAdapters: "A0.99"\n'
            )
            main = self._write_split_roadmaps(root, adapters=adapters)

            with self.assertRaisesRegex(RuntimeError, "references unknown adapter item"):
                validate_roadmap_structure(root, main)

    def test_rejects_unknown_conformance_dependency_from_architecture(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            architecture = (
                'stream: architecture\nitems:\n  - version: "AR0.1"\n'
                '    status: planned\n    dependsOnConformance: "C0.99"\n'
            )
            main = self._write_split_roadmaps(root, architecture=architecture)

            with self.assertRaisesRegex(RuntimeError, "references unknown conformance item"):
                validate_roadmap_structure(root, main)

    def test_rejects_adapter_dependency_on_later_architecture_stream(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adapters = (
                'stream: adapters\nitems:\n  - version: "A0.1"\n'
                '    status: planned\n'
                '    dependsOnCore: "0.9.7.1"\n'
                '    dependsOnArchitecture: "AR0.1"\n'
            )
            main = self._write_split_roadmaps(root, adapters=adapters)

            with self.assertRaisesRegex(RuntimeError, "reverses roadmap ownership direction"):
                validate_roadmap_structure(root, main)

    def test_adapter_roadmap_may_contain_concrete_target_names(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adapters = (
                'stream: adapters\nitems:\n  - version: "A0.1"\n'
                '    name: "Jenkins adapter reassessment"\n'
                '    status: planned\n'
                '    dependsOnCore: "0.9.7.1"\n'
            )
            main = self._write_split_roadmaps(root, adapters=adapters)

            validate_roadmap_structure(root, main)

    def test_allows_optional_semantic_integrity_successor_stream(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._write_split_roadmaps(root, core=COMPLETED_CORE_ITEM)
            agent = root / ".flow-agent"
            semantic = agent / "roadmap-semantic-integrity.yaml"
            semantic.write_text(
                'stream: semantic-integrity\nitems:\n'
                '  - version: "SI-01.1"\n'
                '    name: "Post-C1 Integrity Reconciliation"\n'
                '    type: "bounded-governance-and-integrity-correction"\n'
                '    status: next\n'
                '    purpose: "Reconcile post-C1 integrity."\n'
                '    dependsOnCore: "0.9.7.1"\n',
                encoding="utf-8",
            )
            text = main.read_text(encoding="utf-8")
            text = text.replace(
                'primaryRoadmapStream: core',
                'primaryRoadmapStream: semantic-integrity',
            ).replace(
                '    architecture: ".flow-agent/roadmap-architecture.yaml"\n',
                '    architecture: ".flow-agent/roadmap-architecture.yaml"\n'
                '    semantic-integrity: ".flow-agent/roadmap-semantic-integrity.yaml"\n',
            )
            main.write_text(text, encoding="utf-8")

            validate_roadmap_structure(root, main)
            item = find_unique_next_roadmap_item(root, main)

            self.assertEqual("SI-01.1", item.version)
            self.assertEqual("semantic-integrity", item.stream)

    def test_rejects_unknown_optional_stream_name(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = self._write_split_roadmaps(root)
            text = main.read_text(encoding="utf-8").replace(
                '    architecture: ".flow-agent/roadmap-architecture.yaml"\n',
                '    architecture: ".flow-agent/roadmap-architecture.yaml"\n'
                '    invented: ".flow-agent/roadmap-invented.yaml"\n',
            )
            main.write_text(text, encoding="utf-8")
            (root / ".flow-agent" / "roadmap-invented.yaml").write_text(
                "stream: invented\nitems: []\n", encoding="utf-8"
            )

            with self.assertRaisesRegex(RuntimeError, "unknown streams: invented"):
                validate_roadmap_structure(root, main)


if __name__ == "__main__":
    unittest.main()
