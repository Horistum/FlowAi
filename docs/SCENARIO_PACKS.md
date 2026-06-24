# Flow Scenario Packs v0.3.0-rc1.8.3

Scenario Packs are the standardization layer between human/AI text and the portable Flow Standard Intent Model.

They exist so the normalizer does not become a giant if/else block and so users do not need to learn Jenkins, GitHub Actions, Tekton, Argo Workflows, Azure DevOps or other target-specific syntaxes.

## Current Packs

- `deployment`
- `backup-restore`
- `data-sync`
- `secret-rotation`
- `incident-runbook`

Each pack defines:

- trigger vocabulary
- required and optional entities
- standard capabilities
- risks
- example requests
- normalization rules
- conformance expectations

## Invariant

A scenario pack must produce a `IntentDocument` that either:

1. has required open questions and therefore must not be lowered in strict mode, or
2. passes the full pipeline:

```text
Scenario Pack
  -> Normalized Intent
  -> IntentCapabilityValidator
  -> IntentToAstPlanner
  -> FlowValidator
  -> FlowPlanner
```

No pack is allowed to emit target-specific syntax. Target-specific details remain in target manifest generators/renderers.

## Commands

```bash
./gradlew run --args="scenarios"
./gradlew run --args="scenarios --markdown"
./gradlew run --args="scenario deployment --examples"
```
