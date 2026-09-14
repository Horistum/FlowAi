#!/usr/bin/env python3
"""Small black-box verification for the standalone git-opts reference example."""

from __future__ import annotations

import json
import sys
from pathlib import Path

EXPECTED_STEPS = [
    "checkout",
    "create-branch",
    "update-readme",
    "commit",
    "push",
    "open-pull-request",
]

EXPECTED_ACTIONS = {
    "checkout": "checkout",
    "create-branch": "createBranch",
    "update-readme": "writeFile",
    "commit": "commit",
    "push": "push",
    "open-pull-request": "openPullRequest",
}

REQUIRED_CAPABILITIES = {
    "git.checkout",
    "git.branch.create",
    "workspace.file.write",
    "git.commit.create",
    "git.ref.publish",
    "scm.change-request.open",
}


def load(path: Path) -> dict:
    if not path.is_file():
        raise AssertionError(f"Missing generated artifact: {path.name}")
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    output = Path(sys.argv[1] if len(sys.argv) > 1 else "generated")

    intent = load(output / "normalized-intent.json")
    validation = load(output / "intent-capability-validation-report.json")
    plan = load(output / "execution-plan.json")
    canonical = load(output / "canonical-execution-plan.json")
    planning = load(output / "target-neutral-planning-report.json")

    workflows = intent.get("workflows", [])
    assert len(workflows) == 1, f"Expected one workflow, found {len(workflows)}"
    step_ids = [step["id"] for step in workflows[0].get("steps", [])]
    assert step_ids == EXPECTED_STEPS, f"Unexpected normalized step order: {step_ids}"

    assert validation.get("valid") is True, f"Intent validation failed: {validation.get('issues')}"

    nodes = [node for node in plan.get("nodes", []) if node.get("kind") == "task"]
    by_source = {node.get("sourceId"): node for node in nodes}
    missing = [step for step in EXPECTED_STEPS if step not in by_source]
    assert not missing, f"Execution plan lost intent steps: {missing}"

    for step_id, action in EXPECTED_ACTIONS.items():
        node = by_source[step_id]
        assert node.get("module") == "gitops", f"{step_id}: unexpected module {node.get('module')}"
        assert node.get("action") == action, f"{step_id}: unexpected action {node.get('action')}"

    actual_capabilities = set(plan.get("requiredCapabilities", []))
    missing_capabilities = sorted(REQUIRED_CAPABILITIES - actual_capabilities)
    assert not missing_capabilities, f"Plan lost module capabilities: {missing_capabilities}"

    canonical_sources = {
        node.get("sourceId")
        for node in canonical.get("nodes", [])
        if isinstance(node, dict)
    }
    missing_canonical = [step for step in EXPECTED_STEPS if step not in canonical_sources]
    assert not missing_canonical, f"Canonical plan lost source identities: {missing_canonical}"

    assert planning.get("targetSelected") is False, "Reference must remain target-neutral by default."

    print("git-opts reference verification: PASS")
    print(f"planned steps: {', '.join(EXPECTED_STEPS)}")
    print(f"module capabilities: {', '.join(sorted(REQUIRED_CAPABILITIES))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
