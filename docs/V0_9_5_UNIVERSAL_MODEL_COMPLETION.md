# Flow 0.9.5 Universal Model Completion

## Purpose

Flow 0.9.5 promotes the v0.9.5.x architecture repair into a coherent package line. The release removes split generation truth, eliminates dormant placebo output, introduces first-class trigger semantics, removes target assumptions from neutral lowering and makes target-native projection depend on explicit declarative evidence.

## Manifest generation invariant

Every public manifest generator inherits from `ReconciledTargetManifestGenerator`. Concrete generators can only build an unreconciled internal value; the final public `generate` method always applies `reconcileCompatibilityReadiness`. `TargetManifestGenerationPipeline` is the canonical target-selection boundary used by the CLI.

## Projection evidence

`TargetProjectionRule` is loaded from the versioned target registry. A rule declares a module/action match, projection mode, evidence reference and optional structured renderer payload. Missing evidence is `ADAPTER_REQUIRED`. Raw `shell.run` remains blocked globally. A target-native artifact is executable only when capability support, native materialization, payload kind, payload reference and evidence reference all agree.

The built-in Jenkins `git.checkout` rule is the first ordinary native path. It renders the Jenkins `git` step from structured parameters. This is deliberately narrow evidence, not a claim that all common actions are executable.

## Trigger model

Triggers are top-level intent semantics, not workflow work steps. Intent 2.0 supports `MANUAL`, `SCHEDULE`, `EVENT` and `WEBHOOK`. Schedules support `CRON`, `INTERVAL` and `CALENDAR`. The AST, ExecutionPlan, compatibility analyzer and TargetManifest preserve the trigger contract. For example, “renew certificate every 30 days” becomes an interval schedule with expression `P30D`.

## Neutral lowering

Generic `DEPLOY` and `VERIFY` capabilities lower to semantic `standard.execute` actions. Kubernetes is used only when the intent explicitly declares `uses: kubernetes.<action>`. No default cluster, namespace, selector or Kubernetes deployment action is invented by the neutral planner.

## Extensible target semantics

`TargetSemanticsEntry` stores `semanticsByTarget: Map<String, String>`. Target ids are loaded from the target registry. Adding a target no longer requires adding a vendor field to a public data class.

## Non-goals

Flow 0.9.5 does not add a runtime executor, SDK lifecycle, plugin framework, shell projection or target-specific public Flow syntax. Review-only and fail-fast remain valid, required outcomes when evidence is incomplete.
