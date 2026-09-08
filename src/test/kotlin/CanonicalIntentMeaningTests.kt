import org.flowlang.frontend.FrontendCompilerComposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.ast.ActionNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.intent.CanonicalIntentMeaningAuthority
import org.flowlang.intent.IntentBindingStatus
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentSystem
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.CanonicalModuleLoader
import org.flowlang.modules.ModuleRegistry

class CanonicalIntentMeaningTests {
    @Test
    fun equivalentIntentMeaningIsStableAcrossDifferentExplicitBindings() {
        val argoRegistry = ModuleRegistry.fromDescriptors(listOf(moduleDescriptor(
            module = "argo",
            systemType = "argocd",
            action = "sync",
            capability = "DEPLOY",
            bindingParam = "app"
        )))
        val kubeRegistry = ModuleRegistry.fromDescriptors(listOf(moduleDescriptor(
            module = "kube",
            systemType = "kubernetes",
            action = "deploy",
            capability = "DEPLOY",
            bindingParam = "name"
        )))

        val argoIntent = deployIntent(
            system = IntentSystem("delivery", "argocd"),
            uses = "argo.sync",
            bindingParam = "app"
        )
        val kubeIntent = deployIntent(
            system = IntentSystem("delivery", "kubernetes"),
            uses = "kube.deploy",
            bindingParam = "name"
        )

        val argo = CanonicalIntentMeaningAuthority(argoRegistry).resolve(argoIntent)
        val kube = CanonicalIntentMeaningAuthority(kubeRegistry).resolve(kubeIntent)

        assertEquals(argo.meaning, kube.meaning)
        assertEquals(IntentBindingStatus.RESOLVED, argo.bindings.single().status)
        assertEquals(IntentBindingStatus.RESOLVED, kube.bindings.single().status)
        assertEquals(listOf("environment", "target"), argo.bindings.single().semanticParameters)
        assertEquals(listOf("app", "system"), argo.bindings.single().bindingParameters)
        assertEquals(listOf("name", "system"), kube.bindings.single().bindingParameters)
    }

    @Test
    fun unboundCapabilityLowersToSemanticStandardAction() {
        val intent = IntentDocument(
            name = "semantic-checkout",
            workflows = listOf(IntentWorkflow(
                name = "main",
                kind = IntentWorkflowKind.BUILD,
                steps = listOf(IntentStep("checkout", StandardCapability.CHECKOUT))
            ))
        )

        val ast = FrontendCompilerComposition.intentPlanner().plan(intent)
        val action = ast.flow.steps.single() as ActionNode

        assertEquals("standard", action.module)
        assertEquals("execute", action.action)
        assertEquals("checkout", (action.params.getValue("operation") as StringLiteralNode).value)
        assertTrue(ast.flow.systems.any { it.name == "standard" && it.systemType == "standard" })
        assertFalse(ast.flow.systems.any { it.systemType == "git" })
    }

    @Test
    fun explicitBindingRequiresDeclaredCapabilityImplementation() {
        val registry = ModuleRegistry.fromDescriptors(listOf(moduleDescriptor(
            module = "argo",
            systemType = "argocd",
            action = "sync",
            capability = null,
            bindingParam = "app"
        )))
        val report = IntentCapabilityValidator(registry).validate(
            deployIntent(IntentSystem("delivery", "argocd"), "argo.sync", "app")
        )

        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "BINDING_CAPABILITY_NOT_IMPLEMENTED" })
    }

    @Test
    fun semanticTargetNeverSelectsTheBindingSystem() {
        val registry = ModuleRegistry.fromDescriptors(listOf(
            """
            kind: FlowModule
            name: release
            version: "1.0"
            description: Release rollback adapter
            systemTypes:
              release:
                input: {}
            actions:
              rollback:
                kind: action
                implements: [ROLLBACK]
                targetTypes: [release]
                input:
                  target: { type: text }
                output:
                  ok: { type: boolean }
                effects:
                  updates: [release.state]
                safety:
                  destructive: false
            """.trimIndent()
        ))
        val intent = IntentDocument(
            name = "rollback",
            systems = listOf(IntentSystem("release-api", "release")),
            workflows = listOf(IntentWorkflow(
                "main",
                IntentWorkflowKind.DEPLOY,
                listOf(IntentStep(
                    id = "rollback",
                    capability = StandardCapability.ROLLBACK,
                    uses = "release.rollback",
                    params = mapOf(
                        "system" to IntentString("release-api"),
                        "target" to IntentString("previous-version")
                    )
                ))
            ))
        )

        val action = FrontendCompilerComposition.intentPlanner(registry).plan(intent).flow.steps.single() as ActionNode

        assertEquals("release-api", action.target.path.single())
        assertEquals("previous-version", (action.params.getValue("target") as StringLiteralNode).value)
    }

    @Test
    fun bindingMetadataWithoutUsesFailsClosed() {
        val intent = IntentDocument(
            name = "ambiguous",
            workflows = listOf(IntentWorkflow(
                "main",
                IntentWorkflowKind.BUILD,
                listOf(IntentStep(
                    "checkout",
                    StandardCapability.CHECKOUT,
                    params = mapOf("system" to IntentString("source"))
                ))
            ))
        )

        val report = IntentCapabilityValidator(ModuleRegistry()).validate(intent)
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "BINDING_HINT_REQUIRES_USES" })
    }

    @Test
    fun moduleDescriptorsRejectUnknownImplementedCapability() {
        val invalid = moduleDescriptor(
            module = "invalid",
            systemType = "invalid",
            action = "run",
            capability = "NOT_A_STANDARD_CAPABILITY",
            bindingParam = "value"
        )
        assertFailsWith<CanonicalModuleLoader.ContractException> {
            CanonicalModuleLoader.loadText(invalid)
        }
    }

    private fun deployIntent(system: IntentSystem, uses: String, bindingParam: String): IntentDocument = IntentDocument(
        name = "deploy-service",
        systems = listOf(system),
        workflows = listOf(IntentWorkflow(
            name = "delivery",
            kind = IntentWorkflowKind.DEPLOY,
            steps = listOf(IntentStep(
                id = "deploy",
                capability = StandardCapability.DEPLOY,
                uses = uses,
                params = mapOf(
                    "system" to IntentString(system.name),
                    "target" to IntentString("production"),
                    "environment" to IntentString("prod"),
                    bindingParam to IntentString("billing")
                )
            ))
        ))
    )

    private fun moduleDescriptor(
        module: String,
        systemType: String,
        action: String,
        capability: String?,
        bindingParam: String
    ): String = """
        kind: FlowModule
        name: $module
        version: "1.0"
        description: Test binding module
        systemTypes:
          $systemType:
            input: {}
        actions:
          $action:
            kind: action
            ${capability?.let { "implements: [$it]" } ?: ""}
            targetTypes: [$systemType]
            input:
              target: { type: text }
              environment: { type: text }
              $bindingParam: { type: text, required: true }
            output:
              ok: { type: boolean }
            effects:
              updates: [test.resource]
            safety:
              destructive: false
    """.trimIndent()
}
