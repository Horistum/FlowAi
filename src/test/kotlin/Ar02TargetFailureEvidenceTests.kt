import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.conformance.Ar02FailureProjectionEvidence
import org.flowlang.conformance.Ar02IntegratedSemanticClosureChecks
import org.flowlang.conformance.Ar02TargetMatrix
import org.flowlang.conformance.Ar02TargetScenario
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.targets.builtin.BuiltInTargetProjections

class Ar02TargetFailureEvidenceTests {
    @Test
    fun realProvidersPreserveThePolicyAtTheirOwnStructuralLevel() {
        manifests.forEach { (target, manifest) ->
            val errors = Ar02FailureProjectionEvidence.errors(manifest, policy)
            assertTrue(errors.isEmpty(), "$target: ${errors.joinToString()}")
            if (target == "jenkins") {
                assertTrue(manifest.jobs.none { it.metadata["workflowFailurePolicy"] == "true" })
            } else {
                assertEquals(1, manifest.jobs.count { it.metadata["workflowFailurePolicy"] == "true" })
            }
        }
    }

    @Test
    fun everyPolicyFieldMustMatchTheAuthorizedPolicy() {
        val mutations = mapOf(
            "workflowFailurePolicy" to "false",
            "workflowFailureDisposition" to "RECOVER",
            "workflowFailureErrorBinding" to "unrelatedError",
            "workflowFailurePriorSuccessfulValuesAvailable" to "true"
        )
        manifests.forEach { (target, manifest) ->
            mutations.forEach { (field, value) ->
                val changed = rewritePolicy(manifest) { it + (field to value) }
                val errors = Ar02FailureProjectionEvidence.errors(changed, policy)
                assertTrue(errors.isNotEmpty(), "$target silently accepted mutated $field.")
            }
        }
    }

    @Test
    fun policyMarkersOnNormalJobsCannotImpersonateHandlerMembership() {
        val manifest = manifests.getValue("github-actions")
        val handler = manifest.jobs.single { it.metadata["workflowFailurePolicy"] == "true" }
        val normal = manifest.jobs.single { it.id != handler.id }
        val changed = manifest.copy(jobs = manifest.jobs.map { job ->
            when (job.id) {
                handler.id -> job.copy(metadata = job.metadata - "workflowFailurePolicy")
                normal.id -> job.copy(metadata = job.metadata + handler.metadata)
                else -> job
            }
        })
        val errors = Ar02FailureProjectionEvidence.errors(changed, policy)
        assertTrue(errors.any { "exactly the authorized handler jobs" in it }, errors.joinToString())
    }

    @Test
    fun everyHandlerJobMustRetainPolicyEvidenceNotJustTheFirstOne() {
        val unit = closure.compileFlowSource(
            """
                flow "ar02e-two-handler-jobs" {
                  steps { approve manual { message: "Protected work" } -> approved }
                  on error {
                    approve manual { message: "First handler" } -> firstHandler
                    approve manual { message: "Second handler" } -> secondHandler
                  }
                }
            """.trimIndent(), "ar02e-two-handler-jobs"
        )
        val expected = unit.workflowPlanSet.workflows.single().failurePolicy
        listOf("github-actions", "tekton").forEach { target ->
            val manifest = pipeline.generateDiagnosticEvidence(
                TargetDiagnosticMaterializationRequest.fromCompilation(unit, testTargetSelection(target, targets))
            )
            assertTrue(Ar02FailureProjectionEvidence.errors(manifest, expected).isEmpty())
            val handlers = manifest.jobs.filter { it.metadata["workflowFailurePolicy"] == "true" }
            assertEquals(2, handlers.size)
            handlers.forEach { missing ->
                val changed = manifest.copy(jobs = manifest.jobs.map { job ->
                    if (job.id == missing.id) job.copy(metadata = job.metadata - "workflowFailurePolicy") else job
                })
                assertTrue(Ar02FailureProjectionEvidence.errors(changed, expected).isNotEmpty(), "$target: ${missing.id}")
            }
        }
    }

    @Test
    fun jobHandlersMustRemainGuardedByProtectedWorkflowJobs() {
        listOf("github-actions", "tekton").forEach { target ->
            val manifest = manifests.getValue(target)
            val changed = manifest.copy(jobs = manifest.jobs.map { job ->
                if (job.metadata["workflowFailurePolicy"] == "true") job.copy(dependsOn = emptyList()) else job
            })
            val errors = Ar02FailureProjectionEvidence.errors(changed, policy)
            assertTrue(errors.any { "not guarded" in it }, "$target: ${errors.joinToString()}")
        }
    }

    @Test
    fun jenkinsCannotDropTheHandlerWhileRetainingMatchingMetadata() {
        val manifest = manifests.getValue("jenkins")
        fun removeHandler(steps: List<TargetStep>): List<TargetStep> = steps.map { step ->
            if (step.metadata["workflowFailurePolicy"] == "true") {
                step.copy(children = step.children.filterNot { it.metadata["tryRole"] == "errorHandler" })
            } else step.copy(children = removeHandler(step.children))
        }
        val changed = manifest.copy(jobs = manifest.jobs.map { it.copy(steps = removeHandler(it.steps)) })
        val errors = Ar02FailureProjectionEvidence.errors(changed, policy)
        assertTrue(errors.any { "handler" in it }, errors.joinToString())
    }

    @Test
    fun executableFailureCandidateCannotHideBehindBlockedDiagnosticRendering() {
        val observations = closure.targetObservations
        listOf("github-actions", "tekton").forEach { target ->
            val changed = observations.map { row ->
                if (row.target == target && row.scenario == Ar02TargetScenario.WORKFLOW_FAILURE) {
                    row.copy(executableBlocked = false, renderBlocked = true)
                } else row
            }
            val errors = Ar02TargetMatrix.errors(changed, targets.keys, BuiltInTargetProjections.registry.targetIds)
            assertTrue(errors.any { "$target/WORKFLOW_FAILURE" in it }, errors.joinToString())
        }
    }

    @Test
    fun mergeClosureMustPreserveItsWorkflowFailurePolicyAsWell() {
        val changed = closure.targetObservations.map { row ->
            if (row.target == "jenkins" && row.scenario == Ar02TargetScenario.EXPLICIT_MERGE) {
                row.copy(failurePolicyPreserved = false)
            } else row
        }
        val errors = Ar02TargetMatrix.errors(changed, targets.keys, BuiltInTargetProjections.registry.targetIds)
        assertTrue(errors.any { "jenkins/EXPLICIT_MERGE" in it }, errors.joinToString())
    }

    private fun rewritePolicy(manifest: TargetManifest, transform: (Map<String, String>) -> Map<String, String>): TargetManifest {
        fun metadata(value: Map<String, String>): Map<String, String> =
            if (value["workflowFailurePolicy"] == "true") transform(value) else value
        fun steps(values: List<TargetStep>): List<TargetStep> = values.map {
            it.copy(metadata = metadata(it.metadata), children = steps(it.children))
        }
        return manifest.copy(jobs = manifest.jobs.map {
            it.copy(metadata = metadata(it.metadata), steps = steps(it.steps))
        })
    }

    companion object {
        private val closure by lazy { Ar02IntegratedSemanticClosureChecks(File(".")) }
        private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
        private val pipeline by lazy { BuiltInTargetProjections.pipeline(targets) }
        private val policy by lazy { closure.failureUnit.workflowPlanSet.workflows.single().failurePolicy }
        private val manifests by lazy {
            BuiltInTargetProjections.registry.targetIds.associateWith { target ->
                pipeline.generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest.fromCompilation(
                    closure.failureUnit, testTargetSelection(target, targets)
                ))
            }
        }
    }
}
