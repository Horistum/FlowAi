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
    replace_once("REPORT.md", "Current scoped correction item: `0.9.5.7.7`", "Current scoped correction item: `0.9.5.7.8`")
    replace_once("REPORT.md", "Next scoped correction item: `0.9.5.7.8`", "Next scoped correction item: `0.9.5.7.9`")
    replace_once("REPORT.md", "`0.9.5.7.7` is a roadmap correction identifier", "`0.9.5.7.8` is a roadmap correction identifier")
    replace_once(
        "REPORT.md",
        "- `0.9.5.7.7 Policy-Driven Safety Prelude` moves environment sensitivity and approval-environment selection behind explicit safety-policy evidence.",
        "- `0.9.5.7.7 Policy-Driven Safety Prelude` moved environment sensitivity and approval-environment selection behind explicit safety-policy evidence.\n- `0.9.5.7.8 Target Expression and Unknown Target Safety` requires explicit expression-support evidence, fails closed for unknown targets and removes renderer-side guard fallbacks."
    )
    replace_once("REPORT.md", "The next scoped repair item is `0.9.5.7.8 Target Expression and Unknown Target Safety`.", "The next scoped repair item is `0.9.5.7.9 Reference Scenario and Snapshot Honesty Reset`.")
    replace_once("REPORT.md", "- Current correction item: `0.9.5.7.7`", "- Current correction item: `0.9.5.7.8`")
    replace_once(
        "REPORT.md",
        "v0.9.5.7.7 changes safety-policy evidence and target approval-environment selection.",
        "v0.9.5.7.8 changes target expression evidence, compatibility enforcement and fail-closed projection behavior."
    )


def update_release_state() -> None:
    replace_once(".flow-agent/release-state.yaml", 'currentCorrectionItem: "0.9.5.7.7"', 'currentCorrectionItem: "0.9.5.7.8"')
    replace_once(".flow-agent/release-state.yaml", 'validationSource: "GitHub Actions Flow CI #897 on the v0.9.5.7.7 pull request branch"', 'validationSource: "GitHub Actions validation on the v0.9.5.7.8 pull request branch"')
    replace_once(".flow-agent/release-state.yaml", "The current scoped correction item is v0.9.5.7.7 Policy-Driven Safety Prelude.", "The current scoped correction item is v0.9.5.7.8 Target Expression and Unknown Target Safety.")
    replace_once(".flow-agent/release-state.yaml", "The next scoped correction item is v0.9.5.7.8 Target Expression and Unknown Target Safety.", "The next scoped correction item is v0.9.5.7.9 Reference Scenario and Snapshot Honesty Reset.")
    replace_once(".flow-agent/release-state.yaml", "Environment sensitivity is classified from explicit safety-policy evidence rather than validator-owned production literals.", "Target expression support is derived from explicit target registry or notes evidence rather than target-name switches.")
    replace_once(".flow-agent/release-state.yaml", "GitHub Actions approval environments are projected only when sensitive downstream evidence resolves uniquely.", "Unknown targets and missing expression declarations fail closed before projection.")
    replace_once(".flow-agent/release-state.yaml", "Flow CI #897 passed Flow Agent tooling, structure, context generation, cache-aware checks, clean test and full conformance.", "The v0.9.5.7.8 validation workflow passed Flow Agent structure, context generation, clean test and full conformance before persisting implementation changes.")


def update_roadmaps() -> None:
    replace_once(".flow-agent/roadmap.yaml", 'currentCorrectionItem: "0.9.5.7.7"', 'currentCorrectionItem: "0.9.5.7.8"')
    replace_once(".flow-agent/roadmap-v0.9.5.7-repair-track.yaml", 'currentCorrectionItem: "0.9.5.7.7"', 'currentCorrectionItem: "0.9.5.7.8"')
    replace_once(
        ".flow-agent/roadmap-v0.9.5.7-repair-track.yaml",
        "  - Do not infer sensitive environments or target approval environments without policy evidence.",
        "  - Do not infer sensitive environments or target approval environments without policy evidence.\n  - Do not infer target expression support from target identifiers or missing declarations."
    )
    replace_once(
        ".flow-agent/roadmap-v0.9.5.7-repair-track.yaml",
        '  - version: "0.9.5.7.8"\n    name: Target Expression and Unknown Target Safety\n    status: next',
        '  - version: "0.9.5.7.8"\n    name: Target Expression and Unknown Target Safety\n    status: completed'
    )
    replace_once(
        ".flow-agent/roadmap-v0.9.5.7-repair-track.yaml",
        "      - Closed target expression assumptions are isolated from universal semantics.",
        "      - Closed target expression assumptions are isolated from universal semantics.\n      - Renderers consume the same expression-support evidence as compatibility analysis and fail instead of weakening guards."
    )
    replace_once(
        ".flow-agent/roadmap-v0.9.5.7-repair-track.yaml",
        '  - version: "0.9.5.7.9"\n    name: Reference Scenario and Snapshot Honesty Reset\n    status: planned',
        '  - version: "0.9.5.7.9"\n    name: Reference Scenario and Snapshot Honesty Reset\n    status: next'
    )


def update_version_test() -> None:
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", 'private val currentCorrectionItem = "0.9.5.7.7"', 'private val currentCorrectionItem = "0.9.5.7.8"')
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", 'private val nextCorrectionItem = "0.9.5.7.8"', 'private val nextCorrectionItem = "0.9.5.7.9"')
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", 'assertTrue(repairTrack.contains("name: Policy-Driven Safety Prelude"))', 'assertTrue(repairTrack.contains("name: Target Expression and Unknown Target Safety"))')
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", 'assertTrue(repairTrack.contains("name: Target Expression and Unknown Target Safety"))\n        assertTrue(repairTrack.contains("status: next"))', 'assertTrue(repairTrack.contains("name: Reference Scenario and Snapshot Honesty Reset"))\n        assertTrue(repairTrack.contains("status: next"))')
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", "bump the public Flow standard version to 0.9.5.7.7", "bump the public Flow standard version to 0.9.5.7.8")
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", "docs/V0_9_5_7_7_POLICY_DRIVEN_SAFETY_PRELUDE.md", "docs/V0_9_5_7_8_TARGET_EXPRESSION_AND_UNKNOWN_TARGET_SAFETY.md")
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", "v0.9.5.7.7 documentation must exist.", "v0.9.5.7.8 documentation must exist.")
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", ".flow-agent/reports/v0.9.5.7.7-policy-driven-safety-prelude.md", ".flow-agent/reports/v0.9.5.7.8-target-expression-and-unknown-target-safety.md")
    replace_once("src/test/kotlin/VersionConsistencyTests.kt", "v0.9.5.7.7 correction report must exist.", "v0.9.5.7.8 correction report must exist.")


def update_changelog() -> None:
    replace_once(
        "CHANGELOG.md",
        "- Added release metadata boundaries that distinguish the published package, unreleased correction scope, public standard and artifact contract versions.",
        "- Added release metadata boundaries that distinguish the published package, unreleased correction scope, public standard and artifact contract versions.\n- Added evidence-driven target expression profiles with registry or notes provenance and fail-closed support decisions."
    )
    replace_once(
        "CHANGELOG.md",
        "- Reconciled `REPORT.md`, `.flow-agent/release-state.yaml`, both roadmap files, versioning policy and correction reports around one explicit version boundary.",
        "- Reconciled `REPORT.md`, `.flow-agent/release-state.yaml`, both roadmap files, versioning policy and correction reports around one explicit version boundary.\n- Removed target-name expression assumptions and renderer fallbacks that weakened unsupported conditions to false or unenforced comments."
    )
    replace_once(
        "CHANGELOG.md",
        "- `0.9.5.7.6` Release Metadata Reconciliation",
        "- `0.9.5.7.6` Release Metadata Reconciliation\n- `0.9.5.7.7` Policy-Driven Safety Prelude\n- `0.9.5.7.8` Target Expression and Unknown Target Safety"
    )


def write_docs() -> None:
    (ROOT / ".flow-agent/reports/v0.9.5.7.8-target-expression-and-unknown-target-safety.md").write_text("""# v0.9.5.7.8 Target Expression and Unknown Target Safety

## Purpose

Ensure expression support is derived from explicit target evidence and that unknown targets fail closed instead of inheriting implicit capability.

## Implemented

- Added generic Flow expression feature identifiers derived from the expression AST.
- Added `TargetExpressionSupportDeclaration` and auditable support decisions with profile, evidence kind, evidence reference, required features and missing features.
- Added versioned expression profiles to `targets/builtin-targets.yaml` and required every target registry entry to select one explicitly.
- Removed target-id switches from expression support evaluation.
- Passed the resolved declaration through `TargetCapability`, compatibility reports, manifest generation and target renderers.
- Made Jenkins and Tekton fail rather than weakening an unsupported guard to `false` or an unenforced comment.
- Added fail-closed declarations for adapters that do not yet provide expression translation evidence.
- Added regression tests for unknown targets, missing declarations, custom target-notes evidence, registry validation and model/translator agreement.

## Result

- Unknown targets do not imply expression support.
- Missing expression evidence blocks conditions before projection.
- A new target can declare support without modifying Flow Core target-name logic.
- Compatibility analysis and translation use the same evidence.
- Target-specific adapter limits remain explicit and auditable.

## Validation

The v0.9.5.7.8 validation workflow applied the exact implementation patch and passed:

- Flow Agent structure validation
- Flow Agent context generation
- `./gradlew --no-daemon clean test --stacktrace --console=plain`
- `./gradlew --no-daemon run --args=\"conformance\" --stacktrace --console=plain`

The final standard Flow CI run is the merge gate for the persisted branch state.

## Version boundary

- published package remains `0.9.4`
- active correction item is `0.9.5.7.8`
- active public standard remains `0.7.6`
- artifact contract versions remain unchanged

## Architecture boundary

This correction does not introduce runtime execution, an SDK, a framework lifecycle, shell projection, target-specific public syntax or renderer payload expansion.
""", encoding="utf-8")

    (ROOT / "docs/V0_9_5_7_8_TARGET_EXPRESSION_AND_UNKNOWN_TARGET_SAFETY.md").write_text("""# v0.9.5.7.8 Target Expression and Unknown Target Safety

## Problem

Flow previously evaluated target expression support with a closed Kotlin switch. GitHub Actions and Tekton had special cases, while Jenkins and every unknown target fell through to implicit support. A target name could therefore look capable even when no target registry or notes evidence described which Flow expression constructs it could preserve.

The renderers also contained unsafe fallbacks. Jenkins converted a translation failure into a literal `false` guard, and Tekton could emit a comment while leaving the target guard unenforced. Both outcomes changed user intent instead of rejecting an unsupported projection.

## Evidence model

Expression support is now declared by a `TargetExpressionSupportDeclaration` with:

- a profile identifier
- an evidence kind (`TARGET_REGISTRY` or `TARGET_NOTES`)
- an evidence reference
- either complete Flow expression support or an explicit set of supported AST features

The feature vocabulary is target-neutral. It describes Flow expression requirements such as literals, references, lists, member access, calls and logical or binary operators. It does not encode Jenkins, GitHub Actions or Tekton as semantic meanings.

## Registry profiles

`targets/builtin-targets.yaml` defines reusable expression profiles and requires every target to select one.

- `flow-full` declares complete Flow expression support for adapters whose translator covers the complete language.
- `workflow-condition` declares the subset supported by GitHub Actions conditions.
- `equality-membership-condition` declares the subset supported by Tekton native `when` guards.
- `unavailable` is explicit negative evidence for declared targets that do not yet have an expression adapter.

An absent profile is invalid registry data. An empty but explicit profile means unsupported, not unknown success.

## Fail-closed behavior

Compatibility analysis parses each condition, derives required AST features and compares them with the resolved declaration.

- Unknown target: compatibility remains unsupported.
- Known target without expression evidence: condition support is blocked.
- Explicit unavailable profile: condition support is blocked with the registry reference.
- Explicit target-notes profile: support can be added for a future target without changing a target-id switch in Flow Core.

Target translators receive the same declaration that compatibility analysis used. A renderer cannot silently upgrade or weaken the decision.

## Renderer boundary

- Jenkins no longer replaces an unsupported condition with `false`.
- Tekton no longer emits a target artifact where the guard is only described by a comment.
- GitHub Actions translation rejects features outside its declared profile.

The correction does not add target payload implementations. It only makes expression compatibility and projection honest.

## Version boundary

- package version remains `0.9.4`
- correction item is `0.9.5.7.8`
- public Flow standard remains `0.7.6`
- Intent, AST, ExecutionPlan, TargetManifest and TargetRegistry versions remain unchanged

## Architecture boundary

No runtime executor, SDK API, plugin lifecycle, shell generator, command projection or target-specific public Flow DSL is introduced.
""", encoding="utf-8")

    (ROOT / "docs/TARGET_REGISTRY.md").write_text("""# Target Registry v1.0

Flow target capabilities are represented as versioned YAML under `targets/`.

This is deliberate: target support must be data-driven, reviewable and conformance-testable. Hardcoding every Jenkins, Tekton, Argo Workflows or GitHub Actions behavior in Kotlin would politely re-create the same portability mess Flow exists to avoid.

## File

```text
targets/builtin-targets.yaml
```

## Capability example

```yaml
kind: FlowTargetRegistry
version: \"1.0\"
targets:
  - name: tekton
    expressionProfile: equality-membership-condition
    capabilities:
      parallel: supported
      approvals: unsupported
      dynamicLoops: partial
```

## Expression profiles

A target that declares condition capability must also select an explicit expression profile. Profiles describe Flow AST features, not target names.

```yaml
expressionProfiles:
  - id: equality-membership-condition
    description: Equality and membership conditions over native scalar target values.
    features:
      - node.literal
      - node.reference
      - operator.logical.and
      - operator.binary.==
      - operator.binary.!=
      - operator.binary.in
```

Each resolved declaration carries a registry evidence reference. Missing profiles fail closed. A profile with an empty feature list is explicit unsupported evidence and is not treated as implicit capability.

Future adapters may provide equivalent target-notes evidence without modifying universal Flow expression semantics.

## Support levels

- `supported` - native or safe representation exists.
- `partial` - representation exists with limitation or workaround.
- `unsupported` - target cannot represent the feature safely.
- `requires_runtime` - target needs Flow runtime support.

## Enforcement

The CLI loads the registry and runs `CompatibilityAnalyzer` over the Execution Plan. Expression requirements are derived from the parsed Flow AST and checked against the target's resolved profile before generation. Translators consume the same declaration, preventing compatibility and renderer behavior from drifting apart.

In strict mode partial support is elevated to an error:

```bash
./gradlew run --args=\"intent examples/intent/build-test-deploy.intent.yaml --target tekton --strict\"
```
""", encoding="utf-8")


def main() -> None:
    update_report()
    update_release_state()
    update_roadmaps()
    update_version_test()
    update_changelog()
    write_docs()


if __name__ == "__main__":
    main()
