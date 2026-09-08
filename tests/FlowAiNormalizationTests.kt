package org.flowlang.tests

import org.flowlang.frontend.FrontendCompilerComposition

import kotlin.test.Test
import kotlin.test.assertTrue
import org.flowlang.ai.normalization.*
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.validator.FlowValidator
import java.io.File

class FlowAiNormalizationTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)

    @Test
    fun normalizesDeploymentWithApprovalRollbackAndHealthVerification() {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest(
            "Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure."
        ))
        val steps = response.normalizedIntent.workflows.flatMap { it.steps }
        assertTrue(steps.any { it.capability == StandardCapability.DEPLOY })
        assertTrue(steps.any { it.capability == StandardCapability.VERIFY })
        assertTrue(steps.any { it.capability == StandardCapability.ROLLBACK })
        assertTrue(response.normalizedIntent.policies.any { it.type.name == "APPROVAL" })
        assertTrue(IntentCapabilityValidator(registry).validate(response.normalizedIntent).valid)
    }

    @Test
    fun normalizedDeploymentPassesFullAstValidationPipeline() {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest(
            "Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure."
        ))
        IntentCapabilityValidator(registry).validate(response.normalizedIntent).assertValid()
        val ast = FrontendCompilerComposition.intentPlanner(registry).plan(response.normalizedIntent)
        val astValidation = FrontendCompilerComposition.flowValidator(registry).validate(ast)
        assertTrue(astValidation.valid, astValidation.issues.joinToString { it.code + ": " + it.message })
        val plan = FlowPlanner(registry).plan(ast)
        assertTrue(plan.nodes.isNotEmpty(), "Normalized deployment must lower to an execution plan.")
    }

    @Test
    fun asksRequiredQuestionWhenApplicationIsMissing() {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Deploy to Kubernetes with health verification."))
        assertTrue(response.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED })
    }
}

class FlowAiNormalizationRegressionTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)

    @Test
    fun interpolatesVersionInputInsteadOfLiteralString() {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest(
            "Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy."
        ))
        IntentCapabilityValidator(registry).validate(response.normalizedIntent).assertValid()
        val ast = FrontendCompilerComposition.intentPlanner(registry).plan(response.normalizedIntent)
        val plan = FlowPlanner(registry).plan(ast)
        val build = plan.tasks.first {
        it.module == "standard" &&
            it.action == "execute" &&
            it.params["operation"] == "\"build-image\""
    }
    assertTrue(
        build.params["image"]?.contains("${'$'}{version}") == true,
        "Semantic build intent must retain version as a template reference without selecting Docker implicitly."
    )
    }
}
