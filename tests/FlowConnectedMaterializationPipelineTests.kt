import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.targets.builtin.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetMaterializationResolver
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.materialization.MaterializationNegotiationValidator
import org.flowlang.materialization.MaterializationStatus
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode
import org.flowlang.projection.TargetProjectionArtifactKind
import org.flowlang.projection.TargetProjectionPlanValidator
import org.flowlang.targets.TargetRegistryYamlLoader

class FlowConnectedMaterializationPipelineTests {
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
    private fun rules(target: String) = targets.getValue(target).projectionRules
    @Test
    fun standardTaskAdvancesThroughNotesBackedMaterializationPath() {
        val task = TaskNode(
            id = "standard_execute_1",
            module = "standard",
            action = "execute",
            target = "flow",
            semanticCapability = "standard.execute",
            params = mapOf("operation" to "test")
        )

        val authorization = testCompatibilityAuthorization(
            ExecutionPlan(flowName = "resolver-fixture", nodes = listOf(task)),
            "test:resolver:jenkins:${task.id}"
        )
        val resolution = TargetMaterializationResolver.resolve(authorization, task, "jenkins", rules("jenkins"))

        assertEquals(TargetMaterializationStatus.NOTES_PROJECTED, resolution.materialization.status)
        assertEquals(MaterializationStatus.MATERIALIZABLE, resolution.negotiation.decisions.single().status)
        assertEquals(TargetProjectionArtifactKind.NOTES_BACKED, resolution.artifact.kind)
        assertEquals("standard.execute", resolution.artifact.notesReference)
        assertTrue(MaterializationNegotiationValidator(resolution.notesPackages).validate(resolution.negotiation).valid)
        assertTrue(TargetProjectionPlanValidator(resolution.notesPackages).validate(resolution.projectionPlan).valid)
    }

    @Test
    fun adapterRequiredTaskStillCarriesSemanticNegotiationAndProjectionEvidence() {
        val task = TaskNode(
            id = "git_checkout_1",
            module = "git",
            action = "checkout",
            target = "repo",
            semanticCapability = "git.checkout",
            params = mapOf("branch" to "main")
        )

        val authorization = testCompatibilityAuthorization(
            ExecutionPlan(flowName = "resolver-fixture", nodes = listOf(task)),
            "test:resolver:local:${task.id}"
        )
        val resolution = TargetMaterializationResolver.resolve(authorization, task, "local", rules("local"))

        assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, resolution.materialization.status)
        assertEquals(MaterializationStatus.ADAPTER_REQUIRED, resolution.negotiation.decisions.single().status)
        assertEquals(TargetProjectionArtifactKind.ADAPTER_BOUNDARY, resolution.artifact.kind)
        assertEquals("git.checkout", resolution.obligationGraph.nodes.single().declaration)
        assertNotNull(resolution.materialization.requirements["projectionPlan"])
        assertTrue(MaterializationNegotiationValidator(resolution.notesPackages).validate(resolution.negotiation).valid)
        assertTrue(TargetProjectionPlanValidator(resolution.notesPackages).validate(resolution.projectionPlan).valid)
    }

    @Test
    fun blockedManualRuntimeActionIsPreservedAsReviewRecordWithoutUniversalRuntimeMeaning() {
        val task = TaskNode(
            id = "manual_runtime_1",
            module = "shell",
            action = "run",
            target = "local",
            semanticCapability = "manual.runtime.action",
            params = mapOf("command" to "./gradlew clean test")
        )

        val authorization = testCompatibilityAuthorization(
            ExecutionPlan(flowName = "resolver-fixture", nodes = listOf(task)),
            "test:resolver:jenkins:${task.id}"
        )
        val resolution = TargetMaterializationResolver.resolve(authorization, task, "jenkins", rules("jenkins"))

        assertEquals(TargetMaterializationStatus.BLOCKED, resolution.materialization.status)
        assertEquals(MaterializationStatus.BLOCKED, resolution.negotiation.decisions.single().status)
        assertEquals(TargetProjectionArtifactKind.REVIEW_RECORD, resolution.artifact.kind)
        assertEquals("manual.runtime.action", resolution.obligationGraph.nodes.single().declaration)
        assertTrue(MaterializationNegotiationValidator(resolution.notesPackages).validate(resolution.negotiation).valid)
        assertTrue(TargetProjectionPlanValidator(resolution.notesPackages).validate(resolution.projectionPlan).valid)
    }

    @Test
    fun manifestGenerationUsesResolverMetadataInsteadOfLocalSwitchOnly() {
        val plan = ExecutionPlan(
            flowName = "resolver-wiring",
            nodes = listOf(
                TaskNode(
                    id = "standard_execute_1",
                    module = "standard",
                    action = "execute",
                    target = "flow",
                    semanticCapability = "standard.execute",
                    params = mapOf("operation" to "test")
                )
            )
        )
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "jenkins")

        val manifest = JenkinsManifestGenerator().generate(plan, compatibility)
        val step = manifest.jobs.single().steps.single()

        assertEquals(TargetMaterializationStatus.NOTES_PROJECTED, step.materialization.status)
        assertEquals("flow.semantic.standard-execute-1", step.metadata["semanticGraph"])
        assertEquals("flow.materialization.standard-execute-1", step.metadata["materializationNegotiation"])
        assertEquals("flow.projection.standard-execute-1", step.metadata["projectionPlan"])
        assertEquals("NOTES_BACKED", step.metadata["projectionArtifactKind"])
    }
}
