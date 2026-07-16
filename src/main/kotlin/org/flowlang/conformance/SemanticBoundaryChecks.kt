package org.flowlang.conformance

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.ExecutionReadinessStatus
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.artifacts.FlowArtifactBundleAnalyzer
import org.flowlang.architecture.ArchitectureGovernanceAnalyzer
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ClarificationSeverity
import org.flowlang.ai.normalization.IntentProposalDecision
import org.flowlang.ai.normalization.IntentProposalReview
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.targets.builtin.TargetExpressionTranslator
import org.flowlang.targets.builtin.TargetExpressionTranslationException
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.TaskNode
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

internal class SemanticBoundaryChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkV041SemanticCorrectnessHardening(),
        checkV042StandardBoundary(),
        checkV043ArchitectureGovernanceGuardrails(),
        checkV044AiProposalReview(),
        checkV044ConditionExpressionReadiness(),
        checkV044NoSilentConditionFallback()
    )

    private fun checkV041SemanticCorrectnessHardening(): ConformanceCheck = runCheck("v0.4.1.semantic-correctness-hardening") {
        val normalizer = ScenarioPackIntentNormalizer()
        val requiredClarifications = listOf(
            normalizer.normalize(AiIntentRequest("Backup now.")) to "entities.backup.subject",
            normalizer.normalize(AiIntentRequest("Deploy now.")) to "entities.application.name",
            normalizer.normalize(AiIntentRequest("Run kubernetes maintenance daily.")) to "entities.kubernetes.scope",
            normalizer.normalize(AiIntentRequest("Sync from here to there.")) to "entities.source",
            normalizer.normalize(AiIntentRequest("Prune everything.")) to "entities.cleanup.resource"
        )
        requiredClarifications.forEach { (response, field) ->
            require(response.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == field }) {
                "Adversarial normalization must ask required clarification for $field."
            }
        }
        val secret = normalizer.normalize(AiIntentRequest("Rotate the api token now."))
        require(secret.report.classification.type == "secret-rotation") { "Token secret request must stay in secret-rotation pack." }
        require(secret.report.openQuestions.none { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.secret.name" }) {
            "Secret extractor should prefer the concrete token name before the noun over temporal adverbs after it."
        }
        val migration = normalizer.normalize(AiIntentRequest("Migrate the database now."))
        require(migration.report.classification.type == "database-migration") { "Tokenized matching must classify 'Migrate the database' as database-migration." }
        require(migration.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.database" }) {
            "Database migration without a concrete database must ask a required question."
        }
        val unsupportedGithub = runCatching {
            TargetExpressionTranslator.github(
                "environment matches 'prod.*'",
                listOf(TargetInput("environment")),
                targets.getValue("github-actions").expressionSupport
            )
        }
        require(unsupportedGithub.isFailure) { "Unsupported condition operators must not be translated to silent true/false." }
    }

    private fun checkV042StandardBoundary(): ConformanceCheck = runCheck("v0.4.2.standard-boundary-no-sdk-runtime") {
        require(!File(rootDir, "src/main/kotlin/org/flowlang/runtime").exists()) { "Active source must not expose an org.flowlang.runtime package." }
        require(!File(rootDir, "src/main/kotlin/org/flowlang/runtime/LocalRuntime.kt").exists()) { "LocalRuntime must not be part of the active standard path." }
        val bundle = FlowArtifactBundleAnalyzer().intentBundle("boundary", "jenkins", strict = false, hasManifest = true, renderedArtifact = "Jenkinsfile")
        require(bundle.requiredArtifacts.contains("target-conformance-profile.json")) { "Public bundle must contain target-conformance-profile.json." }
        require(!bundle.requiredArtifacts.contains("target-adapter-certification-profile.json")) { "Public bundle must not publish SDK-like certification artifact names." }
    }

    private fun checkV043ArchitectureGovernanceGuardrails(): ConformanceCheck = runCheck("v0.4.3.architecture-governance-guardrails") {
        val report = ArchitectureGovernanceAnalyzer(rootDir).analyze()
        require(report.status == "PASS") {
            "Architecture governance guardrails must pass: " + report.issues.joinToString { it.code + " at " + it.path + ": " + it.message }
        }
        val requiredPaths = report.files.map { it.path }.toSet()
        require(requiredPaths.contains("docs/ARCHITECTURE_CONSTITUTION.md")) { "Architecture constitution must be part of governance." }
        require(requiredPaths.contains("docs/adr/ADR_TEMPLATE.md")) { "ADR template must be part of governance." }
        require(requiredPaths.contains("standard/architecture/forbidden-directions.yaml")) { "Forbidden directions catalog must be part of governance." }
        require(requiredPaths.contains("standard/architecture/release-checklist.yaml")) { "Release checklist must be part of governance." }
        require(requiredPaths.contains("standard/architecture/drift-score.yaml")) { "Drift Score model must be part of governance." }
        require(report.forbiddenDirections.any { it.id == "runtime-executor" && it.documented }) { "Runtime executor drift must be forbidden." }
        require(report.forbiddenDirections.any { it.id == "sdk-framework" && it.documented }) { "SDK drift must be forbidden." }
        require(report.forbiddenDirections.any { it.id == "plugin-framework" && it.documented }) { "Plugin drift must be forbidden." }
        require(report.forbiddenDirections.any { it.id == "target-template-ownership" && it.documented }) { "Target template ownership drift must be forbidden." }
        require(report.driftScoreMinimum == 0) { "Governance must reject negative Drift Score proposals by default." }
    }

    private fun checkV044AiProposalReview(): ConformanceCheck = runCheck("v0.4.4.ai-proposal-review") {
        val review = IntentProposalReview()
        val clean = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Build and test the orders service."))
        require(review.review(clean) is IntentProposalDecision.Accepted) { "A clean deterministic proposal must be accepted." }
        val migrate = IntentDocument(
            name = "schema-change",
            workflows = listOf(IntentWorkflow("migrate", IntentWorkflowKind.CUSTOM, listOf(
                IntentStep("migrate", StandardCapability.DATABASE_MIGRATE, params = mapOf("database" to IntentString("orders")))
            )))
        )
        val decision = review.review(migrate)
        require(decision is IntentProposalDecision.Rejected && decision.violations.any { it.code == "SAFETY_REQUIRES_BACKUP" }) {
            "A migration that omits a mandated backup must be rejected with SAFETY_REQUIRES_BACKUP, independently of the provider's report."
        }
    }

    private fun checkV044ConditionExpressionReadiness(): ConformanceCheck = runCheck("v0.4.4.condition-expression-readiness") {
        val plan = ExecutionPlan(
            flowName = "guarded",
            nodes = listOf(ConditionNode(
                id = "gate", condition = "count > 1",
                then = listOf(TaskNode(id = "guarded-task", module = "standard", action = "execute", target = "all"))
            ))
        )
        val readiness = ExecutionReadinessAnalyzer(targets).analyze(plan, "tekton")
        require(readiness.readiness == ExecutionReadinessStatus.BLOCKED) { "An untranslatable Tekton guard must block readiness." }
        require(readiness.blockers.any { it.capability == "condition.expression" }) { "The blocker must identify condition.expression." }
        var refused = false
        try { CompatibilityAnalyzer(targets).analyze(plan, "tekton").assertAllowed() } catch (_: IllegalStateException) { refused = true }
        require(refused) { "The generation gate must refuse an untranslatable guard." }
    }

    private fun checkV044NoSilentConditionFallback(): ConformanceCheck = runCheck("v0.4.4.no-silent-condition-fallback") {
        val supported = "env == 'prod'"
        val unsupported = "count > 1"
        val github = targets.getValue("github-actions")
        val tekton = targets.getValue("tekton")
        fun gitHubTranslatorOk(condition: String): Boolean = try {
            TargetExpressionTranslator.github(condition, emptyList(), github.expressionSupport); true
        } catch (_: TargetExpressionTranslationException) { false }
        require((TargetExpressionSupport.unsupportedReason(github, supported) == null) == gitHubTranslatorOk(supported)) {
            "GitHub support model and translator disagree on a supported condition."
        }
        require((TargetExpressionSupport.unsupportedReason(github, unsupported) == null) == gitHubTranslatorOk(unsupported)) {
            "GitHub support model and translator disagree on an unsupported condition."
        }
        require(TargetExpressionSupport.unsupportedReason(tekton, unsupported) != null) {
            "The model must report that Tekton cannot express the condition."
        }
        require(TargetExpressionTranslator.tektonWhen(unsupported, emptyList(), tekton.expressionSupport) == null) {
            "Tekton must not silently translate an unsupported condition into a passing guard."
        }
    }
}
