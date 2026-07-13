# v0.9.5.7.8 Target Expression and Unknown Target Safety

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
