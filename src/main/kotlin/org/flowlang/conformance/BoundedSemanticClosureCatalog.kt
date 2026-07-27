package org.flowlang.conformance

/**
 * Frozen identities of every check executed before v0.9.7.10 closure.
 *
 * This catalog was reconciled against the actual ConformanceRunner composition
 * after the first closure run proved the older vector-index helper had drifted.
 * It is intentionally finite: adding or removing a check requires reopening the
 * roadmap decision instead of silently changing closure scope.
 */
object BoundedSemanticClosureCatalog {
    const val CLOSURE_CHECK_ID: String = "v0.9.7.10.bounded-semantic-closure"

    val declaredPriorChecks: List<String> = listOf(
        "intent.valid.build-test-deploy",
        "intent.invalid.argocd-missing-config",
        "target.strict.tekton-approval-unsupported",
        "generator.manifest.jenkins",
        "generator.manifest.github-actions",
        "generator.manifest.tekton.partial",
        "intent.yaml.flow-style",
        "snapshots.e2e.files-exist",
        "snapshots.e2e.content",

        "snapshots.rendered.standard-version",
        "standard.catalog.coverage",
        "standard.capability-contracts",
        "intent.canonical-meaning.inventory-independent",
        "intent.effects.universal-state-transition-model",
        "intent.controls.universal-policy-requirements",
        "planning.topology.abstract-execution-model",
        "intent.lowering.diagnostic-honesty",
        "flow.environment-safety.production-integration",
        "ai.normalization.scenario-negation-token-boundary-honesty",
        "intent.design-report",
        "architecture.core-no-jackson-imports",
        "architecture.modules-do-not-own-target-rendering",
        "ai.normalization.deployment",
        "ai.normalization.full-pipeline-validation",
        "ai.normalization.required-question",

        "schemas.public-outputs",
        "scenario-packs.catalog",

        "scenario-packs.normalization.full-pipelines",
        "scenario-packs.regression-coverage",
        "v0.3.1.scenario-packs",
        "v0.3.1.capability-negotiation",
        "v0.3.2.execution-plan.canonical",
        "v0.3.2.safety-policy-validation",

        "v0.3.4.capability-module-contracts",
        "v0.3.5.intent-decision-model",
        "v0.3.6.execution-plan-portability",
        "v0.3.7.execution-readiness",
        "v0.3.8.target-selection",
        "planning.provider-backed-approval-topology-identity",
        "governance.derived-model-integrity",

        "v0.3.9.target-decision-trace",
        "v0.3.10.public-artifact-bundle",
        "v0.3.11.conformance-manifest",
        "v0.3.12.target-adapter-contract",
        "v0.3.13.standard-diagnostic-catalog",

        "v0.3.14.diagnostic-coverage-report",
        "v0.3.15.artifact-integrity-report",
        "v0.3.16.standard-contract-index",
        "v0.3.17.standard-release-profile",
        "v0.3.18.artifact-evidence-report",
        "v0.3.19.standard-compliance-report",
        "v0.3.20.standard-freeze-report",
        "v0.3.21.compatibility-policy",
        "v0.3.22.reference-corpus",
        "v0.3.23.negative-conformance-corpus",
        "v0.4.2.target-conformance-profile",
        "v0.4.0.public-standard-draft",

        "v0.4.1.semantic-correctness-hardening",
        "v0.4.2.standard-boundary-no-sdk-runtime",
        "v0.4.3.architecture-governance-guardrails",
        "v0.4.4.ai-proposal-review",
        "v0.4.4.condition-expression-readiness",
        "v0.4.4.no-silent-condition-fallback",

        "v0.4.4.behavioral-generator-equivalence",
        "v0.4.5.standard-surface-freeze",
        "v0.4.6.compatibility-migration-policy",
        "v0.4.7.reference-intent-corpus",

        "v0.4.8.target-semantics-matrix",
        "v0.4.9.standard-export-bundle",

        "v0.5.0.standard-export-manifest",
        "v0.5.3.standard-bundle-verifier",

        "cli.release.diagnostic-honesty",
        "governance.target-selection-provenance-cli-status-integrity",
        "governance.closure-blocking-safety-diagnostic-integrity",
        "v0.5.4.data-driven-conformance-index",

        "v0.6.1.intent-corpus-expansion",
        "v0.6.2.required-clarification-contract",
        "v0.6.3.safety-policy-matrix",
        "v0.6.4.target-semantics-negative-corpus",
        "v0.6.5.execution-plan-semantic-invariants",

        "v0.6.6.ai-input-trust-boundary",
        "v0.6.7.standard-example-bundle",
        "v0.6.8.compatibility-promise",
        "v0.7.0.reference-corpus-execution-harness",

        "v0.7.1.architecture-debt-cleanup-and-drift-enforcement",
        "v0.7.3.standard-model-projection-coherence",

        "v0.7.4.architecture-delta-analyzer",
        "v0.7.5.purpose-coverage-ratio",

        ConformanceQualityGateNames.CORE_CONTRACT_CHECK,
        ConformanceQualityGateNames.SCENARIO_PACK_QUALITY
    )

    val retainedReferenceChecks: List<String> = listOf(
        "generator.manifest.jenkins",
        "snapshots.e2e.files-exist",
        "snapshots.e2e.content",
        "v0.4.7.reference-intent-corpus",
        "v0.7.0.reference-corpus-execution-harness",
        "governance.target-selection-provenance-cli-status-integrity",
        "governance.closure-blocking-safety-diagnostic-integrity"
    )
}
