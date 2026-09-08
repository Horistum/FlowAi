package org.flowlang.conformance

import org.flowlang.frontend.FrontendCompilerComposition

import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ClarificationSeverity
import org.flowlang.ai.normalization.IntentProposalDecision
import org.flowlang.ai.normalization.IntentProposalReview
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.artifacts.ReferenceIntentCorpusReport
import org.flowlang.artifacts.ReferenceIntentScenario
import org.flowlang.artifacts.StandardSurface
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentPolicyType
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.validator.FlowValidator

data class ReferenceCorpusScenarioExecution(
    val id: String,
    val expectedStatus: String,
    val actualStatus: String,
    val selectedPack: String,
    val capabilities: List<String>,
    val policyTypes: List<String>,
    val requiredClarifications: List<String>,
    val rejectionCodes: List<String>,
    val extractedEntities: Map<String, String>,
    val lowerable: Boolean,
    val loweredToAst: Boolean,
    val planned: Boolean,
    val failures: List<String>
)

data class ReferenceCorpusExecutionReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val harnessVersion: String = "1.0",
    val status: String,
    val scenarioCount: Int,
    val acceptedCount: Int,
    val blockedCount: Int,
    val lowerableAcceptedCount: Int,
    val blockedLoweringCount: Int,
    val scenarios: List<ReferenceCorpusScenarioExecution>,
    val failures: List<String>
)

/**
 * Executes the public reference intent corpus through the real standard path.
 *
 * This is intentionally not a target runtime. It is a deterministic standard
 * conformance harness: normalize user/AI text, review the normalized intent,
 * validate capabilities, lower accepted scenarios to AST, validate AST, and
 * plan a platform-neutral execution plan. The point is to catch drift between
 * corpus promises and normalizer behavior before it reaches downstream
 * conformance or target-generation checks.
 */
class ReferenceCorpusExecutionHarness(
    private val registry: ModuleRegistry = ModuleRegistry(),
    private val normalizer: ScenarioPackIntentNormalizer = ScenarioPackIntentNormalizer(),
    private val proposalReview: IntentProposalReview = IntentProposalReview(ModuleRegistry())
) {
    fun execute(corpus: ReferenceIntentCorpusReport = StandardSurface.referenceIntentCorpus()): ReferenceCorpusExecutionReport {
        val executions = corpus.scenarios.map { executeScenario(it) }
        val failures = executions.flatMap { execution -> execution.failures.map { "${execution.id}: $it" } }
        return ReferenceCorpusExecutionReport(
            status = if (failures.isEmpty()) "PASS" else "FAIL",
            scenarioCount = executions.size,
            acceptedCount = executions.count { it.expectedStatus == "ACCEPTED" },
            blockedCount = executions.count { it.expectedStatus == "BLOCKED" },
            lowerableAcceptedCount = executions.count { it.expectedStatus == "ACCEPTED" && it.lowerable },
            blockedLoweringCount = executions.count { it.expectedStatus == "BLOCKED" && !it.lowerable },
            scenarios = executions,
            failures = failures
        )
    }

    fun assertPass(report: ReferenceCorpusExecutionReport) {
        require(report.status == "PASS") {
            "Reference corpus execution harness failed:\n" + report.failures.joinToString("\n")
        }
    }

    private fun executeScenario(scenario: ReferenceIntentScenario): ReferenceCorpusScenarioExecution {
        val failures = mutableListOf<String>()
        return try {
            val response = normalizer.normalize(AiIntentRequest(scenario.inputText))
            val intent = response.normalizedIntent
            val selectedPack = response.report.scenarioSelection?.selectedPack ?: response.report.classification.type
            val capabilities = intent.workflows.flatMap { it.steps }.map { it.capability.name }.distinct().sorted()
            val policyTypes = intent.policies.map { it.type.name }.distinct().sorted()
            val requiredClarifications = response.report.openQuestions
                .filter { it.severity == ClarificationSeverity.REQUIRED }
                .map { it.field }
                .distinct()
                .sorted()
            val decision = proposalReview.review(response)
            val validationReport = IntentCapabilityValidator(registry).validate(intent)
            val reviewCodes = (decision as? IntentProposalDecision.Rejected)
                ?.violations
                ?.map { it.code }
                .orEmpty()
            val validationCodes = validationReport.issues
                .filter { it.level == "error" }
                .map { it.code }
            val rejectionCodes = (reviewCodes + validationCodes).distinct().sorted()
            val actualStatus = if (requiredClarifications.isNotEmpty() || decision is IntentProposalDecision.Rejected || !validationReport.valid) "BLOCKED" else "ACCEPTED"
            val lowering = evaluateLowering(response, decision, validationReport)

            if (actualStatus != scenario.expectedStatus) {
                failures += "pipeline produced $actualStatus, expected ${scenario.expectedStatus}"
            }
            val missingCapabilities = scenario.expectedCapabilities.toSet() - capabilities.toSet()
            if (missingCapabilities.isNotEmpty()) {
                failures += "missing capabilities $missingCapabilities"
            }
            val missingClarifications = scenario.expectedRequiredClarifications.toSet() - requiredClarifications.toSet()
            if (missingClarifications.isNotEmpty()) {
                failures += "missing required clarifications $missingClarifications"
            }
            val missingRejections = scenario.expectedRejectionCodes.toSet() - rejectionCodes.toSet()
            if (missingRejections.isNotEmpty()) {
                failures += "missing rejection codes $missingRejections"
            }
            scenario.expectedEntities.forEach { (key, value) ->
                val actual = response.report.entities[key]
                if (actual != value) failures += "expected entity '$key'='$value' but normalizer extracted '$actual'"
            }
            if (selectedPack == "custom") {
                failures += "reference scenario must not fall back to the custom pack"
            }
            if (scenario.expectedStatus == "ACCEPTED" && !lowering.lowerable) {
                failures += "accepted scenario did not lower: ${lowering.message ?: "unknown lowering failure"}"
            }
            if (scenario.expectedStatus == "BLOCKED" && lowering.lowerable) {
                failures += "blocked scenario was still lowerable"
            }
            if (scenario.expectedStatus == "BLOCKED" && scenario.expectedRequiredClarifications.isEmpty() && scenario.expectedRejectionCodes.isEmpty()) {
                failures += "blocked scenario must declare an expected clarification or rejection code"
            }
            applyRegressionAssertions(scenario, selectedPack, capabilities, policyTypes, failures)

            ReferenceCorpusScenarioExecution(
                id = scenario.id,
                expectedStatus = scenario.expectedStatus,
                actualStatus = actualStatus,
                selectedPack = selectedPack,
                capabilities = capabilities,
                policyTypes = policyTypes,
                requiredClarifications = requiredClarifications,
                rejectionCodes = rejectionCodes,
                extractedEntities = response.report.entities.toSortedMap(),
                lowerable = lowering.lowerable,
                loweredToAst = lowering.loweredToAst,
                planned = lowering.planned,
                failures = failures.toList()
            )
        } catch (t: Throwable) {
            ReferenceCorpusScenarioExecution(
                id = scenario.id,
                expectedStatus = scenario.expectedStatus,
                actualStatus = "ERROR",
                selectedPack = "error",
                capabilities = emptyList(),
                policyTypes = emptyList(),
                requiredClarifications = emptyList(),
                rejectionCodes = emptyList(),
                extractedEntities = emptyMap(),
                lowerable = false,
                loweredToAst = false,
                planned = false,
                failures = listOf(t.message ?: t::class.simpleName.orEmpty())
            )
        }
    }

    private fun evaluateLowering(
        response: org.flowlang.ai.normalization.AiIntentResponse,
        decision: IntentProposalDecision,
        validationReport: org.flowlang.intent.IntentValidationReport
    ): LoweringResult {
        if (decision is IntentProposalDecision.Rejected) {
            return LoweringResult(lowerable = false, loweredToAst = false, planned = false, message = "proposal review rejected intent")
        }
        val required = response.report.openQuestions.filter { it.severity == ClarificationSeverity.REQUIRED }
        if (required.isNotEmpty()) {
            return LoweringResult(lowerable = false, loweredToAst = false, planned = false, message = "required clarification: ${required.joinToString { it.field }}")
        }
        if (!validationReport.valid) {
            return LoweringResult(
                lowerable = false,
                loweredToAst = false,
                planned = false,
                message = validationReport.issues.filter { it.level == "error" }.joinToString { it.code + ": " + it.message }
            )
        }
        return try {
            response.assertUsableForLowering()
            val ast = FrontendCompilerComposition.intentPlanner(registry).plan(response.normalizedIntent)
            val validation = FrontendCompilerComposition.flowValidator(registry).validate(ast)
            if (!validation.valid) {
                return LoweringResult(lowerable = false, loweredToAst = true, planned = false, message = validation.issues.joinToString { it.code + ": " + it.message })
            }
            val plan = FlowPlanner(registry).plan(ast)
            if (plan.nodes.isEmpty()) {
                LoweringResult(lowerable = false, loweredToAst = true, planned = true, message = "execution plan is empty")
            } else {
                LoweringResult(lowerable = true, loweredToAst = true, planned = true, message = null)
            }
        } catch (t: Throwable) {
            LoweringResult(lowerable = false, loweredToAst = false, planned = false, message = t.message ?: t::class.simpleName.orEmpty())
        }
    }

    private fun applyRegressionAssertions(
        scenario: ReferenceIntentScenario,
        selectedPack: String,
        capabilities: List<String>,
        policyTypes: List<String>,
        failures: MutableList<String>
    ) {
        when (scenario.id) {
            "prod-deploy-without-approval" -> {
                if (StandardCapability.APPROVE.name in capabilities) failures += "production without approval must not synthesize APPROVE"
                if (IntentPolicyType.APPROVAL.name in policyTypes) failures += "production without approval must not synthesize APPROVAL policy"
            }
            "rollback-with-verification" -> {
                if (StandardCapability.DEPLOY.name in capabilities) failures += "rollback scenario must not synthesize DEPLOY"
                if (selectedPack != "rollback") failures += "rollback scenario must select rollback pack, not '$selectedPack'"
            }
            "secret-rotation-with-audit" -> {
                if (StandardCapability.VERIFY.name !in capabilities) failures += "secret rollout verification must use VERIFY"
            }
            "portable-build-test-deploy" -> {
                if (selectedPack != "deployment") failures += "build/test/deploy scenario must route to deployment pack, not '$selectedPack'"
            }
        }
    }

    private data class LoweringResult(
        val lowerable: Boolean,
        val loweredToAst: Boolean,
        val planned: Boolean,
        val message: String?
    )
}
