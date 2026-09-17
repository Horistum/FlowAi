import kotlin.test.*
import org.flowlang.ai.normalization.*
import org.flowlang.scenarios.KubernetesMaintenanceScenarioPack

class MaintenanceEnvironmentBoundaryTests {
    private fun normalize(text: String, environment: String? = null) = AiIntentRequest(
        text, context = AiIntentContext(defaultEnvironment = environment)
    ).let { request -> KubernetesMaintenanceScenarioPack.normalize(request,
        KubernetesMaintenanceScenarioPack.match(request.userText, request.context)) }

    @Test fun productAndReproducibleAreNotProductionDeclarations() {
        listOf("product", "reproducible").forEach { word ->
            val result = normalize("Run Kubernetes maintenance in namespace $word with dry-run.", "dev")
            assertFalse(result.openQuestions.any { it.field == "safety.maintenance.window" })
            assertFalse(result.openQuestions.any { it.field == "safety.environment" })
        }
    }

    @Test fun productionAndLiveContextRequireThePolicySensitiveWindow() {
        listOf("prod", "production", "live").forEach { value ->
            val result = normalize("Run Kubernetes maintenance in namespace payments with dry-run.", value)
            assertTrue(result.openQuestions.any { it.field == "safety.maintenance.window" }, value)
        }
    }

    @Test fun unknownExplicitEnvironmentRequiresClarification() {
        val result = normalize("Run Kubernetes maintenance in namespace payments with dry-run.", "prod-eu")
        assertTrue(result.openQuestions.any { it.field == "safety.environment" && it.severity == ClarificationSeverity.REQUIRED })
        assertTrue(result.intent.inputs.any { it.default?.toString()?.contains("prod-eu") == true })
    }

    @Test fun contextCannotSilentlyOverrideAnExplicitProductionRequest() {
        val result = normalize("Run Kubernetes maintenance in production namespace payments with dry-run.", "dev")
        assertTrue(result.openQuestions.any { it.id == "conflicting-maintenance-environment" })
        assertTrue(result.openQuestions.any { it.field == "safety.maintenance.window" })
    }
}
