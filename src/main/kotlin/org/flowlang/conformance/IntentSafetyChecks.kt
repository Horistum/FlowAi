package org.flowlang.conformance

import org.flowlang.artifacts.PublicStandardDraft
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlanCanonicalizer
import java.io.File

internal class IntentSafetyChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkV061IntentCorpusExpansion(),
        checkV062RequiredClarificationContract(),
        checkV063SafetyPolicyMatrix(),
        checkV064TargetSemanticsNegativeCorpus(),
        checkV065ExecutionPlanSemanticInvariants()
    )

    private fun checkV061IntentCorpusExpansion(): ConformanceCheck = runCheck("v0.6.1.intent-corpus-expansion") {
        val corpus = StandardSurface.referenceIntentCorpus()
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.1.intent-corpus-expansion"
        val requiredScenarios = setOf(
            "deploy-with-approval-and-rollback",
            "database-migration-with-backup",
            "certificate-renewal-with-window",
            "secret-rotation-with-audit",
            "kubernetes-maintenance-dry-run",
            "cleanup-with-retention",
            "rollback-with-verification",
            "portable-build-test-deploy",
            "database-migration-without-backup",
            "kubernetes-maintenance-without-dry-run"
        )
        val scenarioIds = corpus.scenarios.map { it.id }.toSet()

        require(activeStandardAtLeast(0, 6, 1)) {
            "Intent corpus expansion must carry standardVersion 0.6.1 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the intent corpus expansion gate."
        }
        require(scenarioIds.containsAll(requiredScenarios)) {
            "Reference intent corpus is missing required scenarios: ${requiredScenarios - scenarioIds}"
        }
        require(corpus.scenarios.count { it.expectedStatus == "ACCEPTED" } >= 10) {
            "Reference intent corpus must include at least ten accepted scenarios."
        }
        require(corpus.negativeScenarioIds.containsAll(listOf("database-migration-without-backup", "kubernetes-maintenance-without-dry-run", "prod-deploy-without-approval"))) {
            "Reference intent corpus must include safety-blocked negative scenarios."
        }
    }

    private fun checkV062RequiredClarificationContract(): ConformanceCheck = runCheck("v0.6.2.required-clarification-contract") {
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.2.required-clarification-contract"
        val rules = StandardSurface.requiredClarificationContract()
        val corpus = StandardSurface.referenceIntentCorpus()
        val requiredFields = rules.filter { it.blocking }.map { it.field }.toSet()
        val corpusClarifications = corpus.scenarios.flatMap { it.expectedRequiredClarifications }.toSet()

        require(activeStandardAtLeast(0, 6, 2)) {
            "Required clarification contract must carry standardVersion 0.6.2 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the required-clarification contract gate."
        }
        require(rules.all { it.blocking }) {
            "Every required clarification rule in the contract must be blocking."
        }
        require(requiredFields.containsAll(listOf("entities.application.name", "entities.environment", "safety.backup", "safety.cleanup.retention", "approval.owner", "safety.maintenance.window", "entities.secret.name", "entities.certificate"))) {
            "Required clarification contract is missing one or more critical fields."
        }
        require(corpusClarifications.containsAll(requiredFields - "safety.backup")) {
            "Reference intent corpus must contain blocked examples for required clarification fields: ${(requiredFields - "safety.backup") - corpusClarifications}"
        }
        require(corpus.scenarios.any { it.expectedRejectionCodes.contains("SAFETY_REQUIRES_BACKUP") }) {
            "Backup requirement may be represented as a safety rejection when migration intent is explicit."
        }
    }

    private fun checkV063SafetyPolicyMatrix(): ConformanceCheck = runCheck("v0.6.3.safety-policy-matrix") {
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.3.safety-policy-matrix"
        val matrix = StandardSurface.safetyPolicyMatrix()
        val negativeCases = PublicStandardDraft.negativeCorpus().cases
        val diagnostics = negativeCases.map { it.expectedDiagnostic }.toSet()

        require(activeStandardAtLeast(0, 6, 3)) {
            "Safety policy matrix must carry standardVersion 0.6.3 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the safety-policy matrix gate."
        }
        require(matrix.map { it.capability }.toSet().containsAll(setOf("DATABASE_MIGRATE", "CLEANUP", "CLUSTER_MAINTENANCE", "SECRET_ROTATE", "DEPLOY", "CERTIFICATE_RENEW"))) {
            "Safety policy matrix must cover destructive and high-risk standard capabilities."
        }
        require(matrix.all { it.requiredMitigations.isNotEmpty() && it.blockingDiagnostic.isNotBlank() }) {
            "Every safety policy matrix entry must specify mitigations and a blocking diagnostic."
        }
        require(diagnostics.containsAll(matrix.map { it.blockingDiagnostic }.toSet())) {
            "Negative conformance corpus must cover every safety matrix blocking diagnostic: ${matrix.map { it.blockingDiagnostic }.toSet() - diagnostics}"
        }
    }

    private fun checkV064TargetSemanticsNegativeCorpus(): ConformanceCheck = runCheck("v0.6.4.target-semantics-negative-corpus") {
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.4.target-semantics-negative-corpus"
        val matrix = org.flowlang.distribution.reference.ReferenceStandardArtifacts.targetSemanticsMatrix()
        val features = matrix.entries.associateBy { it.feature }
        val negativeDiagnostics = PublicStandardDraft.negativeCorpus().cases.map { it.expectedDiagnostic }.toSet()

        require(activeStandardAtLeast(0, 6, 4)) {
            "Target semantics negative corpus must carry standardVersion 0.6.4 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the target-semantics negative corpus gate."
        }
        require(features["strict-manual-approval"]?.requiredDiagnosticWhenUnsupported == "approval.strict") {
            "Target semantics matrix must explicitly model strict manual approval portability."
        }
        require(features["conditions"]?.requiredDiagnosticWhenUnsupported == "condition.expression") {
            "The provider-backed condition feature must retain its unsupported-expression diagnostic."
        }
        require("unsupported-condition-fallback" !in features && "rollback-portability" !in features) {
            "The public matrix must not publish feature rows without an implemented evidence resolver."
        }
        require(negativeDiagnostics.containsAll(setOf("approval.strict", "condition.expression"))) {
            "Negative conformance corpus must cover provider-backed target semantics degradation diagnostics."
        }

        val unsupportedCondition = listOf(
            "name matches '^prod-'",
            "region in ['eu', 'us']"
        ).firstOrNull { expression ->
            TargetExpressionSupport.unsupportedReason(targets.getValue("tekton"), expression) != null
        }
        require(unsupportedCondition != null) {
            "Tekton reference evidence must retain at least one unsupported condition expression."
        }
        require(TargetExpressionSupport.unsupportedReason(targets.getValue("jenkins"), unsupportedCondition) == null) {
            "The same reference condition must remain natively expressible by Jenkins."
        }
    }

    private fun checkV065ExecutionPlanSemanticInvariants(): ConformanceCheck = runCheck("v0.6.5.execution-plan-semantic-invariants") {
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.5.execution-plan-semantic-invariants"
        val invariants = StandardSurface.executionPlanSemanticInvariants()
        val artifacts = buildPipeline("jenkins", strict = false)
        val plan = artifacts.plan
        val canonical = ExecutionPlanCanonicalizer.canonicalize(plan)
        val canonicalNodes = flattenCanonical(canonical.nodes)
        val ids = canonicalNodes.map { it.id }
        val dependencies = canonicalNodes.flatMap { node -> node.dependencies.map { node.id to it } }
        val targetSyntax = listOf("Jenkinsfile", "github-actions.yml", "tekton-pipeline.yaml", "pipeline {", "kind: Pipeline")

        require(activeStandardAtLeast(0, 6, 5)) {
            "Execution plan semantic invariants must carry standardVersion 0.6.5 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the execution-plan semantic invariants gate."
        }
        require(invariants.map { it.id }.toSet().containsAll(setOf("plan.node-ids-unique", "plan.dependencies-known", "plan.no-cycles", "plan.no-target-specific-leakage"))) {
            "Execution plan semantic invariant catalog is incomplete."
        }
        require(ids.size == ids.toSet().size) {
            "Canonical execution-plan node ids must be unique."
        }
        require(dependencies.all { (_, dep) -> dep in ids.toSet() }) {
            "Canonical execution-plan dependencies must reference known nodes: ${dependencies.filterNot { (_, dep) -> dep in ids.toSet() }}"
        }
        require(!hasDependencyCycle(dependencies)) {
            "Canonical execution-plan dependencies must not contain cycles."
        }
        require(targetSyntax.none { canonical.toString().contains(it) }) {
            "Canonical execution plan must not contain rendered target syntax."
        }
    }
}
