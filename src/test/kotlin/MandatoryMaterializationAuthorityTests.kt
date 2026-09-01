import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.InvalidPlanningEvidenceException
import org.flowlang.generators.manifest.UnresolvedExecutionTopologyException
import org.flowlang.generators.manifest.ReconciledTargetManifestGenerator
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetManifestRenderer
import org.flowlang.generators.manifest.TargetMaterializationEvidenceAuthority
import org.flowlang.generators.manifest.TargetMaterializationResolver
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.TargetProjectionAuthorization
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode
import org.flowlang.targets.builtin.BuiltInNativeProjectionCatalogs
import org.flowlang.targets.builtin.BuiltInTargetProjections

class MandatoryMaterializationAuthorityTests {
    @Test
    fun invalidPlanningEvidenceCannotReachGenerator() {
        var invoked = false
        val target = "future-orchestrator"
        val generator = syntheticGenerator(target) { invoked = true }
        val targets = mapOf(target to testTargetCapability(target, "Synthetic target"))
        val pipeline = TargetManifestGenerationPipeline(
            targets = targets,
            projections = TargetProjectionRegistry.of(
                TargetProjectionProvider(generator, syntheticRenderer(target))
            )
        )
        val invalid = ExecutionPlan(
            flowName = "",
            nodes = listOf(
                TaskNode(id = "duplicate", module = "git", action = "checkout", target = "repository"),
                TaskNode(id = "duplicate", module = "docker", action = "build", target = "image")
            )
        )

        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            pipeline.generate(testMaterializationRequest(invalid, target, targets))
        }

        assertFalse(invoked, "Invalid planning evidence must fail before a target generator is invoked.")
        assertTrue(failure.issues.any { it.code == "planning.flow-name.missing" })
        assertTrue(failure.issues.any { it.code == "planning.node.id.duplicate" })
    }

    @Test
    fun futureTargetStillComposesThroughTheMandatoryAuthority() {
        var invoked = false
        val target = "future-orchestrator"
        val targets = mapOf(target to testTargetCapability(target, "Synthetic target"))
        val pipeline = TargetManifestGenerationPipeline(
            targets = targets,
            projections = TargetProjectionRegistry.of(
                TargetProjectionProvider(syntheticGenerator(target) { invoked = true }, syntheticRenderer(target))
            )
        )

        val manifest = pipeline.generate(testMaterializationRequest(ExecutionPlan(flowName = "future-flow"), target, targets))

        assertTrue(invoked)
        assertEquals(target, manifest.target)
        assertEquals("future-flow", manifest.flowName)
    }


    @Test
    fun unsupportedTargetRequiresExplicitDiagnosticAuthorization() {
        val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        val pipeline = BuiltInTargetProjections.pipeline(targets)
        val plan = ExecutionPlan(
            flowName = "diagnostic-only",
            nodes = listOf(ApprovalNode(id = "approve"))
        )

        assertFailsWith<UnresolvedExecutionTopologyException> {
            pipeline.generate(testMaterializationRequest(plan, "tekton", targets))
        }

        val diagnostic = pipeline.generateDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, "tekton", targets))

        assertTrue(diagnostic.compatibility.hasErrors)
        assertFalse(diagnostic.compatibility.executable)
        assertEquals("tekton", diagnostic.target)
    }

    @Test
    fun generatedMaterializationEvidenceIsValidatedBeforeItCanBeConsumed() {
        val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        val jenkins = targets.getValue("jenkins")
        val task = TaskNode(
            id = "checkout",
            module = "git",
            action = "checkout",
            target = "repository",
            semanticCapability = "git.checkout",
            params = mapOf("url" to "https://example.invalid/repository.git", "branch" to "main")
        )
        val authorization = testCompatibilityAuthorization(
            ExecutionPlan(flowName = "materialization-evidence", nodes = listOf(task)),
            "test:materialization-evidence"
        )
        val valid = TargetMaterializationResolver.resolve(
            authorization = authorization,
            task = task,
            targetName = "jenkins",
            projectionRules = jenkins.projectionRules,
            nativeProjections = BuiltInNativeProjectionCatalogs.jenkins
        )
        val duplicateNode = valid.obligationGraph.nodes.single().copy()
        val invalidGraph = valid.obligationGraph.copy(nodes = valid.obligationGraph.nodes + duplicateNode)
        val invalidNegotiation = valid.negotiation.copy(graph = invalidGraph)
        val invalidProjection = valid.projectionPlan.copy(negotiation = invalidNegotiation)

        val failure = assertFailsWith<IllegalArgumentException> {
            TargetMaterializationEvidenceAuthority.requireValid(
                valid.copy(
                    obligationGraph = invalidGraph,
                    negotiation = invalidNegotiation,
                    projectionPlan = invalidProjection
                )
            )
        }

        assertTrue(failure.message.orEmpty().contains("semantic.node.id.duplicate"))
    }

    @Test
    fun productionSourcesExposeNoRawPlanAndCompatibilityGenerationBypass() {
        val providerSource = File(
            "src/main/kotlin/org/flowlang/generators/manifest/TargetProjectionProvider.kt"
        ).readText()
        val productionSources = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        assertTrue(providerSource.contains("fun generate(authorization: TargetProjectionAuthorization)"))
        assertFalse(providerSource.contains("fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport)"))
        assertFalse(File("src/main/kotlin/org/flowlang/generators/manifest/TargetManifestProjection.kt").exists())
        assertFalse(File("src/main/kotlin/org/flowlang/planner/PlannerCapabilityConstraints.kt").exists())

        val directProviderBypasses = productionSources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (line.contains("requireProvider(") && line.contains(".generate(")) {
                    "${file.path}:${index + 1}: ${line.trim()}"
                } else null
            }
        }
        assertTrue(directProviderBypasses.isEmpty(), directProviderBypasses.joinToString())
    }

    private fun syntheticGenerator(target: String, invoked: () -> Unit) =
        object : ReconciledTargetManifestGenerator() {
            override val target: String = target

            override fun buildManifest(authorization: TargetProjectionAuthorization): TargetManifest {
                invoked()
                val plan = authorization.plan
                val compatibility = authorization.compatibility
                return TargetManifest(
                    target = target,
                    flowName = plan.flowName,
                    compatibility = compatibility,
                    metadata = mapOf(
                        "sourcePlanVersion" to plan.planVersion,
                        "generator" to "SyntheticGenerator",
                        "standardVersion" to "0.8.0"
                    )
                )
            }
        }

    private fun syntheticRenderer(target: String) = object : TargetManifestRenderer {
        override val target: String = target
        override val artifactFileName: String = "$target.yaml"
        override fun render(manifest: TargetManifest): String = manifest.target
    }
}
