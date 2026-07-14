#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one occurrence in {path}, found {count}: {old}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


def update_report() -> None:
    replace_once("REPORT.md", "Current scoped correction item: `0.9.5.7.8`", "Current scoped correction item: `0.9.5.7.9`")
    replace_once("REPORT.md", "Next scoped correction item: `0.9.5.7.9`", "Next scoped correction item: `0.9.5.8`")
    replace_once("REPORT.md", "`0.9.5.7.8` is a roadmap correction identifier", "`0.9.5.7.9` is a roadmap correction identifier")
    replace_once(
        "REPORT.md",
        "- `0.9.5.7.8 Target Expression and Unknown Target Safety` requires explicit expression-support evidence, fails closed for unknown targets and removes renderer-side guard fallbacks.",
        "- `0.9.5.7.8 Target Expression and Unknown Target Safety` requires explicit expression-support evidence, fails closed for unknown targets and removes renderer-side guard fallbacks.\n- `0.9.5.7.9 Reference Scenario and Snapshot Honesty Reset` removes shell execution from the flagship reference path and classifies committed target snapshots by real render evidence."
    )
    replace_once(
        "REPORT.md",
        "The next scoped repair item is `0.9.5.7.9 Reference Scenario and Snapshot Honesty Reset`.",
        "The v0.9.5.7 repair track is complete. The next scoped correction item is `0.9.5.8 Policy-Driven Safety and Environment Classification`."
    )
    replace_once("REPORT.md", "- Current correction item: `0.9.5.7.8`", "- Current correction item: `0.9.5.7.9`")
    replace_once(
        "REPORT.md",
        "v0.9.5.7.8 changes target expression evidence, compatibility enforcement and fail-closed projection behavior.",
        "v0.9.5.7.9 changes reference scenario semantics, snapshot evidence and conformance assertions. It does not add renderer payload implementations, runtime execution, an SDK surface, a framework lifecycle, a shell generator or target-specific public Flow syntax."
    )


def update_release_state() -> None:
    replace_once(".flow-agent/release-state.yaml", 'currentCorrectionItem: "0.9.5.7.8"', 'currentCorrectionItem: "0.9.5.7.9"')
    replace_once(".flow-agent/release-state.yaml", 'validationSource: "GitHub Actions validation on the v0.9.5.7.8 pull request branch"', 'validationSource: "GitHub Actions v0.9.5.7.9 transactional validation run #39"')
    replace_once(".flow-agent/release-state.yaml", "The current scoped correction item is v0.9.5.7.8 Target Expression and Unknown Target Safety.", "The current scoped correction item is v0.9.5.7.9 Reference Scenario and Snapshot Honesty Reset.")
    replace_once(".flow-agent/release-state.yaml", "The next scoped correction item is v0.9.5.7.9 Reference Scenario and Snapshot Honesty Reset.", "The next scoped correction item is v0.9.5.8 Policy-Driven Safety and Environment Classification.")
    replace_once(".flow-agent/release-state.yaml", "Target expression support is derived from explicit target registry or notes evidence rather than target-name switches.", "The flagship reference build/test path uses semantic software.test intent instead of active shell.run.")
    replace_once(".flow-agent/release-state.yaml", "Unknown targets and missing expression declarations fail closed before projection.", "Committed target snapshots are explicitly REVIEW_ONLY and non-executable until complete materialization and renderer payload evidence exists.")
    replace_once(".flow-agent/release-state.yaml", "The v0.9.5.7.8 validation workflow passed Flow Agent structure, context generation, clean test and full conformance before persisting implementation changes.", "The v0.9.5.7.9 transactional validation generated snapshots from the real pipeline and passed Flow Agent tooling, structure, context generation, clean test and full conformance before persistence.")


def update_repair_track() -> None:
    replace_once(".flow-agent/roadmap-v0.9.5.7-repair-track.yaml", "status: active", "status: completed")
    replace_once(".flow-agent/roadmap-v0.9.5.7-repair-track.yaml", 'currentCorrectionItem: "0.9.5.7.8"', 'currentCorrectionItem: "0.9.5.7.9"')
    replace_once(
        ".flow-agent/roadmap-v0.9.5.7-repair-track.yaml",
        "  - Do not infer target expression support from target identifiers or missing declarations.",
        "  - Do not infer target expression support from target identifiers or missing declarations.\n  - Do not label review-only snapshots as executable or end-to-end execution evidence."
    )
    replace_once(
        ".flow-agent/roadmap-v0.9.5.7-repair-track.yaml",
        '  - version: "0.9.5.7.9"\n    name: Reference Scenario and Snapshot Honesty Reset\n    status: next',
        '  - version: "0.9.5.7.9"\n    name: Reference Scenario and Snapshot Honesty Reset\n    status: completed'
    )
    replace_once(
        ".flow-agent/roadmap-v0.9.5.7-repair-track.yaml",
        "      - End-to-end examples stop claiming support when they only document unresolved materialization.",
        "      - End-to-end examples stop claiming support when they only document unresolved materialization.\n      - Snapshot indexes record capability compatibility, effective compatibility, materialization readiness, projection readiness, render mode and executable state.\n      - Legacy executable-looking snapshot file names are rejected until executable renderer evidence exists."
    )


def update_main_roadmap() -> None:
    replace_once(".flow-agent/roadmap.yaml", 'currentCorrectionItem: "0.9.5.7.8"', 'currentCorrectionItem: "0.9.5.7.9"')
    replace_once(
        ".flow-agent/roadmap.yaml",
        '  activeRepairTrack: ".flow-agent/roadmap-v0.9.5.7-repair-track.yaml"\n  nextUmbrellaItemAfterRepair: "0.9.5.8"',
        '  completedRepairTrack: ".flow-agent/roadmap-v0.9.5.7-repair-track.yaml"\n  nextUmbrellaItem: "0.9.5.8"'
    )
    replace_once(
        ".flow-agent/roadmap.yaml",
        '  - version: "0.9.5.8"\n    name: "Policy-Driven Safety and Environment Classification"\n    purpose: "Replace hardcoded production string guessing with declarative safety and environment notes."\n    type: "safety-policy"\n    status: "planned"',
        '  - version: "0.9.5.8"\n    name: "Policy-Driven Safety and Environment Classification"\n    purpose: "Replace hardcoded production string guessing with declarative safety and environment notes."\n    type: "safety-policy"\n    status: "next"'
    )


def update_version_test() -> None:
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", 'private val currentCorrectionItem = "0.9.5.7.8"', 'private val currentCorrectionItem = "0.9.5.7.9"')
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", 'private val nextCorrectionItem = "0.9.5.7.9"', 'private val nextCorrectionItem = "0.9.5.8"')
    replace_once(
        "src/test/kotlin/VersionConsistencyTests.kt",
        'assertFileContains(".flow-agent/roadmap.yaml", "activeRepairTrack: \\".flow-agent/roadmap-v0.9.5.7-repair-track.yaml\\"")',
        'assertFileContains(".flow-agent/roadmap.yaml", "completedRepairTrack: \\".flow-agent/roadmap-v0.9.5.7-repair-track.yaml\\"")\n        assertFileContains(".flow-agent/roadmap.yaml", "nextUmbrellaItem: \\"0.9.5.8\\"")'
    )
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", 'assertTrue(repairTrack.contains("name: Target Expression and Unknown Target Safety"))', 'assertTrue(repairTrack.contains("name: Reference Scenario and Snapshot Honesty Reset"))')
    replace_once(
        "src/test/kotlin/VersionConsistencyTests.kt",
        'assertTrue(repairTrack.contains("version: \\"$nextCorrectionItem\\""))\n        assertTrue(repairTrack.contains("name: Reference Scenario and Snapshot Honesty Reset"))\n        assertTrue(repairTrack.contains("status: next"))',
        'assertTrue(repairTrack.contains("status: completed"))\n        assertFileContains(".flow-agent/roadmap.yaml", "version: \\"$nextCorrectionItem\\"")\n        assertFileContains(".flow-agent/roadmap.yaml", "name: \\"Policy-Driven Safety and Environment Classification\\"")\n        assertFileContains(".flow-agent/roadmap.yaml", "status: \\"next\\"")'
    )
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", "bump the public Flow standard version to 0.9.5.7.8", "bump the public Flow standard version to 0.9.5.7.9")
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", "docs/V0_9_5_7_8_TARGET_EXPRESSION_AND_UNKNOWN_TARGET_SAFETY.md", "docs/V0_9_5_7_9_REFERENCE_SCENARIO_AND_SNAPSHOT_HONESTY_RESET.md")
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", "v0.9.5.7.8 documentation must exist.", "v0.9.5.7.9 documentation must exist.")
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", ".flow-agent/reports/v0.9.5.7.8-target-expression-and-unknown-target-safety.md", ".flow-agent/reports/v0.9.5.7.9-reference-scenario-and-snapshot-honesty-reset.md")
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", "v0.9.5.7.8 correction report must exist.", "v0.9.5.7.9 correction report must exist.")


def update_changelog() -> None:
    replace_once(
        "CHANGELOG.md",
        "- Added evidence-driven target expression profiles with registry or notes provenance and fail-closed support decisions.",
        "- Added evidence-driven target expression profiles with registry or notes provenance and fail-closed support decisions.\n- Added a reference snapshot evidence index that distinguishes semantic-only, review-only, fail-fast and executable states."
    )
    replace_once(
        "CHANGELOG.md",
        "- Removed target-name expression assumptions and renderer fallbacks that weakened unsupported conditions to false or unenforced comments.",
        "- Removed target-name expression assumptions and renderer fallbacks that weakened unsupported conditions to false or unenforced comments.\n- Replaced the flagship shell.run test path with semantic software.test intent.\n- Renamed committed target snapshots to state-specific review-only files and removed stale compatibility and manifest snapshots.\n- Tightened reference adapter expectations so review-only no longer accepts executable or blocked output."
    )
    replace_once("CHANGELOG.md", "- `0.9.5.7.8` Target Expression and Unknown Target Safety", "- `0.9.5.7.8` Target Expression and Unknown Target Safety\n- `0.9.5.7.9` Reference Scenario and Snapshot Honesty Reset")


def write_docs_and_report() -> None:
    (ROOT / "docs/V0_9_5_7_9_REFERENCE_SCENARIO_AND_SNAPSHOT_HONESTY_RESET.md").write_text("""# v0.9.5.7.9 Reference Scenario and Snapshot Honesty Reset

## Problem

The flagship build/test/deploy reference scenario still used `shell.run` and described the test step as the universal capability `command.run`. Its committed target outputs were named like executable Jenkins, GitHub Actions and Tekton artifacts even though the render policy classified them as review-only and non-executable. Adapter expectations were also permissive enough that a review-required scenario accepted supported, degraded or blocked results interchangeably.

## Semantic correction

The flagship test step now uses `standard.execute` with the universal `software.test` capability. The reference intent example and intent analyzer use the semantic `standard` system instead of assigning build and test meaning to shell execution.

Explicit shell intent remains supported only as preserved user intent that materializes to blocked and fail-fast evidence. It is no longer the flagship automation path.

## Exact adapter expectations

`ReferenceAdapterProjectionMatrix` now distinguishes:

- `EXECUTABLE`
- `REVIEW_ONLY`
- `BLOCKED`

Positive reference scenarios currently require exact `REVIEW_ONLY` render mode, `executable=false` and degraded evidence. Negative scenarios require exact `BLOCKED` outcomes. A review-only expectation no longer accepts executable or blocked output merely because both are non-crashing states.

## Snapshot evidence

`ReferenceSnapshotHonesty` defines a versioned snapshot index with:

- semantic artifact layer and state
- target projection state
- capability compatibility
- effective compatibility
- materialization readiness
- projection readiness
- render mode
- executable flag

The target files are committed as:

```text
jenkins.review.yaml
github-actions.review.yaml
tekton.review.yaml
```

Legacy executable-looking names are forbidden until complete materialization and renderer payload evidence exists.

## Conformance

Conformance now regenerates the normalized intent, Flow AST, canonical Execution Plan, snapshot index and rendered target artifacts through the real pipeline and compares them exactly with committed snapshots. It also rejects active shell projection in the flagship plan, stale snapshot files and documentation that claims end-to-end execution.

Snapshots were generated transactionally by the real parser, intent planner, Flow planner, compatibility analyzer, manifest generators and render policy. They were not edited to satisfy assertions.

## Validation

The v0.9.5.7.9 transactional validation workflow run `#39` passed:

- exact implementation patch checks
- snapshot generation from the real pipeline
- Flow Agent tooling tests
- Flow Agent structure validation
- Flow Agent context generation
- `./gradlew --no-daemon clean test --stacktrace --console=plain`
- `./gradlew --no-daemon run --args=\"conformance\" --stacktrace --console=plain`
- persistence of the fully validated implementation

The standard Flow CI on the final clean branch remains the merge gate.

## Version boundary

- package remains `0.9.4`
- correction item is `0.9.5.7.9`
- public Flow standard remains `0.7.6`
- artifact contract versions remain unchanged

## Architecture boundary

No runtime executor, SDK API, plugin lifecycle, framework lifecycle, shell projection, fabricated renderer payload or target-specific public Flow DSL is introduced.
""", encoding="utf-8")

    (ROOT / ".flow-agent/reports/v0.9.5.7.9-reference-scenario-and-snapshot-honesty-reset.md").write_text("""# v0.9.5.7.9 Reference Scenario and Snapshot Honesty Reset

## Purpose

Align reference scenarios, snapshots, examples and conformance expectations with the repaired materialization and readiness model.

## Implemented

- replaced the flagship `shell.run` test path with semantic `standard.execute` and `software.test` intent
- introduced exact executable, review-only and blocked adapter outcomes
- added a versioned snapshot evidence index backed by compatibility and readiness analysis
- renamed target snapshots to state-specific `.review.yaml` files
- removed stale compatibility-report and target-manifest snapshots
- added exact semantic and target snapshot comparisons to conformance
- rejected executable-looking snapshot names without executable renderer evidence
- removed end-to-end execution claims from snapshot documentation

## Validation

GitHub Actions v0.9.5.7.9 transactional validation run `#39` generated the snapshots from the real pipeline and passed Flow Agent tooling, structure, context generation, clean test, full conformance and persistence.

A final standard Flow CI run on the clean branch is required before merge.

## Result

The committed snapshot set is explicitly `REVIEW_ONLY_PROJECTION_SET` and `executable=false`. Jenkins, GitHub Actions and Tekton snapshots record `REVIEW_ONLY` render mode instead of masquerading as executable vendor artifacts.

## Version boundary

- package remains `0.9.4`
- active correction item is `0.9.5.7.9`
- public standard remains `0.7.6`
- artifact contract versions remain unchanged

## Architecture boundary

No runtime executor, SDK, framework lifecycle, shell projection, fabricated renderer payload or target-specific public Flow syntax is introduced.
""", encoding="utf-8")


def main() -> None:
    update_report()
    update_release_state()
    update_repair_track()
    update_main_roadmap()
    update_version_test()
    update_changelog()
    write_docs_and_report()


if __name__ == "__main__":
    main()
