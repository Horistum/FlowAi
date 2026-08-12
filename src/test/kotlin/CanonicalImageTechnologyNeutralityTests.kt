import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.intent.CanonicalIntentMeaningAuthority
import org.flowlang.intent.IntentBindingParameterSource
import org.flowlang.intent.IntentBindingStatus
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentSystem
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry

class CanonicalImageTechnologyNeutralityTests {
    private val dockerRegistry by lazy { ModuleRegistry.fromDirectory(File("modules")) }
    private val withoutDockerRegistry by lazy {
        ModuleRegistry.fromDescriptors(listOf(File("modules/standard.yaml").readText()))
    }

    @Test
    fun dockerInventoryCannotChangeCanonicalImageMeaning() {
        val intent = dockerBoundIntent(includeDockerfile = true)

        val withDocker = CanonicalIntentMeaningAuthority(dockerRegistry).resolve(intent)
        val withoutInventory = CanonicalIntentMeaningAuthority(withoutDockerRegistry).resolve(intent)

        assertEquals(withDocker.meaning, withoutInventory.meaning)
        assertEquals(IntentBindingStatus.RESOLVED, withDocker.bindings.single().status)
        assertEquals(IntentBindingStatus.INVALID, withoutInventory.bindings.single().status)
        val canonicalParams = withDocker.meaning.workflows.single().steps.single().params
        assertEquals(setOf("image", "path", "push"), canonicalParams.keys)
        assertFalse("dockerfile" in canonicalParams)
    }

    @Test
    fun dockerfileIsBindingOnlyForExplicitDockerBuild() {
        val resolution = CanonicalIntentMeaningAuthority(dockerRegistry).resolve(dockerBoundIntent(includeDockerfile = true))
        val binding = resolution.bindings.single()

        assertEquals(IntentBindingStatus.RESOLVED, binding.status, binding.issues.joinToString())
        assertTrue("dockerfile" in binding.bindingParameters)
        assertFalse("dockerfile" in binding.semanticParameters)
        assertEquals(IntentBindingParameterSource.BINDING, binding.parameterSources.getValue("dockerfile"))
        assertEquals(IntentBindingParameterSource.SEMANTIC, binding.parameterSources.getValue("image"))
        assertEquals(IntentBindingParameterSource.SEMANTIC, binding.parameterSources.getValue("path"))
        assertEquals(IntentBindingParameterSource.SEMANTIC, binding.parameterSources.getValue("push"))
    }

    @Test
    fun dockerfileWithoutExplicitDockerBindingFailsClosed() {
        val intent = IntentDocument(
            name = "unbound-image",
            workflows = listOf(
                IntentWorkflow(
                    "build",
                    IntentWorkflowKind.BUILD,
                    listOf(
                        IntentStep(
                            id = "image",
                            capability = StandardCapability.BUILD_IMAGE,
                            params = mapOf(
                                "image" to IntentString("acme/service:1"),
                                "dockerfile" to IntentString("Dockerfile.release")
                            )
                        )
                    )
                )
            )
        )

        val report = IntentCapabilityValidator(withoutDockerRegistry).validate(intent)

        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "UNKNOWN_STEP_PARAM" && "dockerfile" in it.message })
    }

    @Test
    fun dockerfileBindingDoesNotChangeCanonicalMeaning() {
        val withDockerfile = CanonicalIntentMeaningAuthority(dockerRegistry).resolve(dockerBoundIntent(includeDockerfile = true))
        val withoutDockerfile = CanonicalIntentMeaningAuthority(dockerRegistry).resolve(dockerBoundIntent(includeDockerfile = false))

        assertEquals(withoutDockerfile.meaning, withDockerfile.meaning)
        assertTrue(withDockerfile.bindings.single().bindingParameters.contains("dockerfile"))
    }

    private fun dockerBoundIntent(includeDockerfile: Boolean): IntentDocument {
        val params = linkedMapOf(
            "system" to IntentString("builder"),
            "image" to IntentString("acme/service:1"),
            "path" to IntentString("services/api"),
            "push" to org.flowlang.intent.IntentBoolean(false)
        )
        if (includeDockerfile) params["dockerfile"] = IntentString("services/api/Dockerfile.release")
        return IntentDocument(
            name = "image-neutrality",
            systems = listOf(IntentSystem("builder", "docker")),
            workflows = listOf(
                IntentWorkflow(
                    "build",
                    IntentWorkflowKind.BUILD,
                    listOf(
                        IntentStep(
                            id = "image",
                            capability = StandardCapability.BUILD_IMAGE,
                            uses = "docker.build",
                            params = params
                        )
                    )
                )
            )
        )
    }
}
