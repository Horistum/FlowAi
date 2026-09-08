package org.flowlang.tests

import org.flowlang.frontend.FrontendCompilerComposition

import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Dependency-validation contract, asserted on structured error codes rather than
 * human-readable message prose.
 */
class FlowIntentDependencyValidationTests {

    private val validator = IntentCapabilityValidator(ModuleRegistry())

    private fun docWith(steps: List<IntentStep>): IntentDocument =
        IntentDocument(
            name = "dependency-test",
            workflows = listOf(IntentWorkflow(name = "ci", kind = IntentWorkflowKind.CUSTOM, steps = steps))
        )

    private fun hasError(doc: IntentDocument, code: String): Boolean =
        validator.validate(doc).issues.any { it.level == "error" && it.code == code }

    @Test
    fun unknownStepDependencyReportsErrorCode() {
        val doc = docWith(listOf(IntentStep("test", StandardCapability.TEST, requires = listOf("missing"))))
        assertTrue(hasError(doc, "UNKNOWN_STEP_DEPENDENCY"), "Validator must flag UNKNOWN_STEP_DEPENDENCY")
    }

    @Test
    fun unknownStepDependencyAbortsLoweringWithCode() {
        val doc = docWith(listOf(IntentStep("test", StandardCapability.TEST, requires = listOf("missing"))))
        val error = assertFailsWith<IllegalStateException> { FrontendCompilerComposition.intentPlanner().plan(doc) }
        assertTrue(
            (error.message ?: "").contains("UNKNOWN_STEP_DEPENDENCY"),
            "Lowering must abort with UNKNOWN_STEP_DEPENDENCY, got: ${error.message}"
        )
    }

    @Test
    fun cyclicStepDependencyReportsErrorCode() {
        val doc = docWith(
            listOf(
                IntentStep("a", StandardCapability.TEST, requires = listOf("b")),
                IntentStep("b", StandardCapability.TEST, requires = listOf("a"))
            )
        )
        assertTrue(hasError(doc, "CYCLIC_STEP_DEPENDENCY"), "Validator must flag CYCLIC_STEP_DEPENDENCY")
    }

    @Test
    fun cyclicStepDependencyAbortsLoweringWithCode() {
        val doc = docWith(
            listOf(
                IntentStep("a", StandardCapability.TEST, requires = listOf("b")),
                IntentStep("b", StandardCapability.TEST, requires = listOf("a"))
            )
        )
        val error = assertFailsWith<IllegalStateException> { FrontendCompilerComposition.intentPlanner().plan(doc) }
        assertTrue(
            (error.message ?: "").contains("CYCLIC_STEP_DEPENDENCY"),
            "Lowering must abort with CYCLIC_STEP_DEPENDENCY, got: ${error.message}"
        )
    }

    @Test
    fun validLinearDependencyChainHasNoDependencyErrors() {
        val doc = docWith(
            listOf(
                IntentStep("checkout", StandardCapability.CHECKOUT),
                IntentStep("test", StandardCapability.TEST, requires = listOf("checkout"))
            )
        )
        assertTrue(!hasError(doc, "UNKNOWN_STEP_DEPENDENCY"), "Valid chain must not raise UNKNOWN_STEP_DEPENDENCY")
        assertTrue(!hasError(doc, "CYCLIC_STEP_DEPENDENCY"), "Valid chain must not raise CYCLIC_STEP_DEPENDENCY")
        FrontendCompilerComposition.intentPlanner().plan(doc)
    }
}
