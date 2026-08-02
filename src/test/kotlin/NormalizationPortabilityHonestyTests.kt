import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.ai.normalization.AiIntentContext
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.ai.normalization.TargetPortabilityEvidence
import org.flowlang.cli.Json
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentSystem
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry

class NormalizationPortabilityHonestyTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"))

    @Test
    fun normalizationDefersPortabilityToEvidenceBackedPostPlanningArtifacts() {
        val response = ScenarioPackIntentNormalizer().normalize(
            AiIntentRequest(
                userText = "Build and test the repository.",
                context = AiIntentContext(target = "jenkins")
            )
        )

        val portability = response.report.targetPortability
        assertEquals(
            TargetPortabilityEvidence.deferred("jenkins"),
            portability
        )
        assertEquals(TargetPortabilityEvidence.DEFERRED, portability[TargetPortabilityEvidence.STATUS_KEY])
        assertEquals("jenkins", portability[TargetPortabilityEvidence.REQUESTED_TARGET_KEY])
        assertEquals(
            TargetPortabilityEvidence.authorityArtifacts,
            portability.filterKeys { it in TargetPortabilityEvidence.authorityArtifacts }
        )

        val json = Json.mapper.writeValueAsString(response.report)
        assertFalse(json.contains("likely-full"))
        assertFalse(json.contains("depends-on-environment-gates"))
        assertFalse(json.contains("check-approval-and-rollback-capabilities"))
    }

    @Test
    fun genericContainerRegistryRemainsGenericAcrossIntentAndAstEvidence() {
        val intent = unboundIntent("containerRegistry")
        val validation = IntentCapabilityValidator(registry).validate(intent)
        assertTrue(validation.valid, validation.issues.joinToString { it.code })

        val ast = IntentToAstPlanner(registry).plan(intent)
        assertEquals("containerRegistry", ast.flow.systems.single { it.name == "registry" }.systemType)
        assertEquals(
            "containerRegistry",
            ast.metadata.sourceIntent?.systems?.single { it.name == "registry" }?.canonicalType
        )
    }

    @Test
    fun explicitDockerRegistryLegacyAliasStillSelectsDockerBindingType() {
        val ast = IntentToAstPlanner(registry).plan(unboundIntent("dockerRegistry"))
        assertEquals("docker", ast.flow.systems.single { it.name == "registry" }.systemType)
        assertEquals("docker", ast.metadata.sourceIntent?.systems?.single { it.name == "registry" }?.canonicalType)
    }

    @Test
    fun dockerBindingCannotConsumeGenericContainerRegistryByInference() {
        val intent = IntentDocument(
            name = "generic-registry-binding",
            systems = listOf(IntentSystem("registry", "containerRegistry")),
            workflows = listOf(
                IntentWorkflow(
                    name = "main",
                    kind = IntentWorkflowKind.BUILD,
                    steps = listOf(
                        IntentStep(
                            id = "push",
                            capability = StandardCapability.PUSH_IMAGE,
                            uses = "docker.push",
                            params = mapOf(
                                "system" to IntentString("registry"),
                                "image" to IntentString("example/app:1")
                            )
                        )
                    )
                )
            )
        )

        val report = IntentCapabilityValidator(registry).validate(intent)
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "BINDING_SYSTEM_TYPE_MISMATCH" })
        assertEquals("containerRegistry", report.bindings.single().systemType)
    }

    private fun unboundIntent(systemType: String): IntentDocument = IntentDocument(
        name = "registry-type-preservation",
        systems = listOf(IntentSystem("registry", systemType)),
        workflows = listOf(
            IntentWorkflow(
                name = "main",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(IntentStep(id = "custom", capability = StandardCapability.CUSTOM))
            )
        )
    )
}
