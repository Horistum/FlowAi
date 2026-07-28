import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.binding.AdapterCapabilityBindingAuthority
import org.flowlang.adapters.binding.AdapterCapabilityBindingLoader
import org.flowlang.ast.ActionNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.intent.CanonicalIntentMeaningAuthority
import org.flowlang.intent.IntentBindingParameterSource
import org.flowlang.intent.IntentBindingStatus
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentSecretRef
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentSystem
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner

class AdapterCapabilityBindingAuthorityTests {
    @Test
    fun builtInBindingEvidenceMatchesEveryImplementationClaim() {
        val report = AdapterCapabilityBindingAuthority().analyze()

        assertEquals("PASS", report.status, report.findings.joinToString())
        assertEquals(10, report.recordCount)
        assertEquals(report.recordCount, report.implementationClaimCount)
    }

    @Test
    fun missingBindingEvidenceFailsClosed() {
        val document = AdapterCapabilityBindingLoader.load()
        val report = AdapterCapabilityBindingAuthority().analyze(
            document.copy(records = document.records.dropLast(1))
        )

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "BINDING_EVIDENCE_MISSING" })
    }

    @Test
    fun unsupportedRequiredCanonicalParameterFailsClosed() {
        val document = AdapterCapabilityBindingLoader.load()
        val rest = document.records.single { it.id == "rest.call#CALL_API" }
        val mutated = rest.copy(
            mappedSemanticParameters = rest.mappedSemanticParameters - "path",
            unsupportedSemanticParameters = rest.unsupportedSemanticParameters +
                ("path" to "invented unsupported required parameter")
        )
        val report = AdapterCapabilityBindingAuthority().analyze(
            document.copy(records = document.records.map { if (it.id == rest.id) mutated else it })
        )

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "REQUIRED_CANONICAL_PARAMETER_UNSUPPORTED" })
    }

    @Test
    fun bindingManifestRejectsUnknownFields() {
        val root = createTempDirectory("flow-a03-binding").toFile()
        val file = File(root, AdapterCapabilityBindingLoader.PATH)
        file.parentFile.mkdirs()
        file.writeText(
            """
            version: "1.0"
            invented: true
            bindings: []
            """.trimIndent()
        )

        assertFailsWith<IllegalArgumentException> {
            AdapterCapabilityBindingLoader.load(root)
        }
    }

    @Test
    fun descriptorDefaultsAreResolvedOnceBeforeAstLowering() {
        val registry = ModuleRegistry.fromDescriptors(
            listOf(
                """
                kind: FlowModule
                name: delivery
                version: "1.0"
                description: Delivery test binding
                systemTypes:
                  delivery:
                    input: {}
                actions:
                  deploy:
                    kind: action
                    implements: [DEPLOY]
                    targetTypes: [delivery]
                    input:
                      app: { type: text, required: true, default: demo }
                    output:
                      ok: { type: boolean }
                    effects:
                      updates: [delivery.application]
                    safety:
                      destructive: false
                """.trimIndent()
            )
        )
        val intent = IntentDocument(
            name = "default-binding",
            systems = listOf(IntentSystem("delivery-api", "delivery")),
            workflows = listOf(
                IntentWorkflow(
                    "main",
                    IntentWorkflowKind.DEPLOY,
                    listOf(
                        IntentStep(
                            id = "deploy",
                            capability = StandardCapability.DEPLOY,
                            uses = "delivery.deploy",
                            params = mapOf("system" to IntentString("delivery-api"))
                        )
                    )
                )
            )
        )

        val binding = CanonicalIntentMeaningAuthority(registry).resolve(intent).bindings.single()
        assertEquals(IntentBindingStatus.RESOLVED, binding.status)
        assertEquals(IntentBindingParameterSource.DEFAULT, binding.parameterSources.getValue("app"))
        assertEquals(IntentString("demo"), binding.resolvedParameters.getValue("app"))

        val action = IntentToAstPlanner(registry).plan(intent).flow.steps.single() as ActionNode
        assertEquals("demo", (action.params.getValue("app") as StringLiteralNode).value)
        assertEquals(setOf("app"), action.params.keys)
    }

    @Test
    fun unsupportedCanonicalParameterRejectsExplicitBinding() {
        val registry = ModuleRegistry()
        val intent = IntentDocument(
            name = "argocd-namespace",
            systems = listOf(
                IntentSystem(
                    name = "argo",
                    type = "argocd",
                    config = mapOf(
                        "url" to IntentString("https://argo.example"),
                        "token" to IntentSecretRef("ARGO_TOKEN")
                    )
                )
            ),
            workflows = listOf(
                IntentWorkflow(
                    "delivery",
                    IntentWorkflowKind.DEPLOY,
                    listOf(
                        IntentStep(
                            id = "deploy",
                            capability = StandardCapability.DEPLOY,
                            uses = "argocd.sync",
                            params = mapOf(
                                "system" to IntentString("argo"),
                                "app" to IntentString("billing"),
                                "namespace" to IntentString("production")
                            )
                        )
                    )
                )
            )
        )

        val report = IntentCapabilityValidator(registry).validate(intent)
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "BINDING_SEMANTIC_PARAM_UNSUPPORTED" })
    }

    @Test
    fun removedArgoSyncClaimCannotBindCanonicalSync() {
        val registry = ModuleRegistry()
        val intent = IntentDocument(
            name = "invalid-sync",
            systems = listOf(IntentSystem("argo", "argocd")),
            workflows = listOf(
                IntentWorkflow(
                    "sync",
                    IntentWorkflowKind.SYNC,
                    listOf(
                        IntentStep(
                            id = "sync",
                            capability = StandardCapability.SYNC,
                            uses = "argocd.sync",
                            params = mapOf(
                                "system" to IntentString("argo"),
                                "source" to IntentString("git"),
                                "destination" to IntentString("cluster"),
                                "app" to IntentString("billing")
                            )
                        )
                    )
                )
            )
        )

        val report = IntentCapabilityValidator(registry).validate(intent)
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "BINDING_CAPABILITY_NOT_IMPLEMENTED" })
    }

    @Test
    fun canonicalMeaningEffectsAndSelectionProvenanceSurviveBinding() {
        val registry = ModuleRegistry()
        val intent = IntentYamlLoader.load(File("examples/intent/checkout-build-image.intent.yaml"))
        val resolution = CanonicalIntentMeaningAuthority(registry).resolve(intent)
        val ast = IntentToAstPlanner(registry).plan(intent)
        val plan = FlowPlanner(registry).plan(ast)
        val meaningByStep = resolution.meaning.workflows.flatMap { it.steps }.associateBy { it.id }
        val bindingByStep = resolution.bindings.associateBy { it.stepId }

        assertTrue(resolution.bindings.all { it.status == IntentBindingStatus.RESOLVED })
        plan.tasks.forEach { task ->
            val sourceId = requireNotNull(task.sourceId)
            val meaning = meaningByStep.getValue(sourceId)
            val binding = bindingByStep.getValue(sourceId)
            assertEquals(meaning.capability.name, task.semanticCapability)
            assertEquals(meaning.effects, task.effectModel)
            assertEquals(sourceId, task.sourceId)
            assertEquals(binding.module, task.module)
            assertEquals(binding.action, task.action)
            assertEquals(binding.system, task.target)
        }
    }

    @Test
    fun missingExplicitBindingRemainsUnboundSemanticWork() {
        val intent = IntentDocument(
            name = "unbound",
            workflows = listOf(
                IntentWorkflow(
                    "main",
                    IntentWorkflowKind.BUILD,
                    listOf(IntentStep("checkout", StandardCapability.CHECKOUT))
                )
            )
        )

        val resolution = CanonicalIntentMeaningAuthority(ModuleRegistry()).resolve(intent)
        assertEquals(IntentBindingStatus.UNBOUND, resolution.bindings.single().status)
        val action = IntentToAstPlanner().plan(intent).flow.steps.single() as ActionNode
        assertEquals("standard", action.module)
        assertEquals("execute", action.action)
    }
}
