import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.ast.ActionNode
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.FlowNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.effects.CanonicalIntentEffectAuthority
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.effects.ModuleEffectCanonicalizer
import org.flowlang.effects.ResourceState
import org.flowlang.effects.SemanticEffect
import org.flowlang.generators.manifest.InvalidPlanningEvidenceException
import org.flowlang.generators.manifest.MandatoryMaterializationAuthority
import org.flowlang.intent.CanonicalIntentMeaningAuthority
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.Effects
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.DataOpNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.TaskNode

class UniversalEffectStateTransitionModelTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }

    @Test
    fun threeBoundedDomainsShareOneTypedEffectModel() {
        val effects = listOf(
            StandardCapability.BUILD_IMAGE,
            StandardCapability.DATA_TRANSFORM,
            StandardCapability.PROVISION
        ).flatMap(CanonicalIntentEffectAuthority::effectsFor)

        assertTrue(effects.map { it.domain }.toSet().containsAll(setOf(
            EffectDomain.SOFTWARE_DELIVERY,
            EffectDomain.DATA_TRANSFORMATION,
            EffectDomain.INFRASTRUCTURE_STATE
        )))
    }

    @Test
    fun resourceStateTransitionsAreOperationDefined() {
        val create = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.BUILD_IMAGE).single()
        assertEquals(EffectOperation.CREATE, create.operation)
        assertEquals(ResourceState.ABSENT, create.transition?.from)
        assertEquals(ResourceState.PRESENT, create.transition?.to)

        val reconcile = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.DEPLOY).single()
        assertEquals(EffectOperation.UPSERT, reconcile.operation)
        assertEquals(ResourceState.UNKNOWN, reconcile.transition?.from)
        assertEquals(ResourceState.PRESENT, reconcile.transition?.to)

        val read = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.CHECKOUT).single()
        assertEquals(EffectOperation.READ, read.operation)
        assertNull(read.transition)
    }

    @Test
    fun canonicalEffectsRemainStableAcrossBindingInventories() {
        val intent = IntentDocument(
            name = "deploy",
            workflows = listOf(IntentWorkflow(
                name = "delivery",
                kind = IntentWorkflowKind.DEPLOY,
                steps = listOf(IntentStep(
                    id = "deploy",
                    capability = StandardCapability.DEPLOY,
                    params = mapOf("target" to IntentString("production"))
                ))
            ))
        )

        val alternativeInventory = ModuleRegistry.fromDescriptors(listOf(
            """
            kind: FlowModule
            name: unrelated
            version: "1.0"
            description: Unrelated inventory
            systemTypes:
              unrelated:
                input: {}
            actions:
              inspect:
                kind: action
                targetTypes: [unrelated]
                input: {}
                output:
                  ok: { type: boolean }
                effects:
                  reads: [unrelated.resource]
                safety:
                  destructive: false
            """.trimIndent()
        ))
        val alternative = CanonicalIntentMeaningAuthority(alternativeInventory).resolve(intent)
        val canonicalInventory = CanonicalIntentMeaningAuthority(modules).resolve(intent)

        assertEquals(alternative.meaning, canonicalInventory.meaning)
        assertEquals(
            CanonicalIntentEffectAuthority.effectsFor(StandardCapability.DEPLOY),
            canonicalInventory.meaning.workflows.single().steps.single().effects
        )
    }

    @Test
    fun boundAndUnboundIntentCarryTheSameSemanticEffectsIntoExecutionPlan() {
        val bound = IntentYamlLoader.load(File("examples/intent/checkout-build-image.intent.yaml"))
        val boundBuild = FlowPlanner(modules)
            .plan(IntentToAstPlanner(modules).plan(bound))
            .tasks.single { it.semanticCapability == StandardCapability.BUILD_IMAGE.name }

        val unbound = IntentDocument(
            name = "unbound-image-build",
            workflows = listOf(IntentWorkflow(
                name = "build",
                kind = IntentWorkflowKind.BUILD,
                steps = listOf(IntentStep(
                    id = "build-image",
                    capability = StandardCapability.BUILD_IMAGE,
                    params = mapOf("image" to IntentString("example/image:latest"))
                ))
            ))
        )
        val unboundBuild = FlowPlanner(modules)
            .plan(IntentToAstPlanner(modules).plan(unbound))
            .tasks.single()

        assertEquals("docker", boundBuild.module)
        assertEquals("standard", unboundBuild.module)
        assertEquals(boundBuild.effectModel, unboundBuild.effectModel)
        assertEquals(listOf("software.image"), boundBuild.effects)
    }

    @Test
    fun legacyModuleBucketsUseTheTypedShapeWithoutInferringDomainFromImplementationNames() {
        val effects = ModuleEffectCanonicalizer.canonicalize(Effects(
            reads = listOf("git.repository"),
            creates = listOf("docker.image"),
            filesystem = listOf("workspace.write")
        ))

        assertEquals(
            listOf(EffectOperation.READ, EffectOperation.CREATE, EffectOperation.UPSERT),
            effects.map { it.operation }
        )
        assertTrue(effects.all { it.domain == EffectDomain.UNKNOWN })
        assertTrue(effects.single { it.resource == "workspace.write" }.external.not())
    }

    @Test
    fun rawFlowActionsAndDataOperationsEnterTheSameExecutionPlanModel() {
        val action = ActionNode(
            module = "git",
            action = "checkout",
            target = ReferenceNode(path = listOf("source"))
        )
        val plan = FlowPlanner(modules).plan(FlowDocument(flow = FlowNode(name = "raw", steps = listOf(action))))
        val task = plan.tasks.single()

        assertEquals(listOf("git.repository", "workspace.write"), task.effects)
        assertEquals(EffectDomain.UNKNOWN, task.effectModel.first().domain)

        val dataPlan = FlowPlanner(modules).plan(
            FlowDocument(flow = FlowNode(
                name = "data",
                steps = listOf(org.flowlang.ast.TransformNode(
                    source = ReferenceNode(path = listOf("source")),
                    target = "result",
                    select = emptyMap()
                ))
            ))
        )
        val data = dataPlan.nodes.single() as DataOpNode
        assertEquals(StandardCapability.TRANSFORM.name, data.semanticCapability)
        assertTrue(data.effectModel.any { it.domain == EffectDomain.DATA_TRANSFORMATION })
    }

    @Test
    fun materializationRejectsOmittedOrForgedCanonicalEffectEvidence() {
        val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        val authority = MandatoryMaterializationAuthority(targets, modules)
        val expected = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.BUILD_IMAGE)
        val base = TaskNode(
            id = "build",
            module = "standard",
            action = "execute",
            target = "standard",
            semanticCapability = StandardCapability.BUILD_IMAGE.name,
            effectModel = expected,
            params = mapOf("operation" to "build-image", "capability" to "build-image")
        )

        authority.authorizeDiagnosticEvidence(testDiagnosticMaterializationRequest(ExecutionPlan(flowName = "valid", nodes = listOf(base)), "jenkins", targets))

        val omitted = base.copy(effectModel = emptyList(), effects = emptyList())
        val omittedFailure = assertFailsWith<InvalidPlanningEvidenceException> {
            authority.authorizeDiagnosticEvidence(testDiagnosticMaterializationRequest(ExecutionPlan(flowName = "omitted", nodes = listOf(omitted)), "jenkins", targets))
        }
        assertTrue(omittedFailure.issues.any { it.code == "planning.effect.evidence.invalid" })

        val forgedEffect = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.CHECKOUT)
        val forged = base.copy(
            effectModel = forgedEffect,
            effects = forgedEffect.map(SemanticEffect::resource)
        )
        val forgedFailure = assertFailsWith<InvalidPlanningEvidenceException> {
            authority.authorizeDiagnosticEvidence(testDiagnosticMaterializationRequest(ExecutionPlan(flowName = "forged", nodes = listOf(forged)), "jenkins", targets))
        }
        assertTrue(forgedFailure.issues.any { it.code == "planning.effect.evidence.invalid" })
    }
}
