package org.flowlang.targets.builtin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.planner.ExecutionPlan

class GitHubJobConditionAuthorityTests {
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
    private val plan = ExecutionPlan(flowName = "condition-semantics")
    private val compatibility by lazy { CompatibilityAnalyzer(targets).analyze(plan, "github-actions") }

    @Test
    fun ordinaryDependencyKeepsGitHubDefaultSuccessAndCancellationSemantics() {
        val manifest = manifest(
            TargetJob(id = "build"),
            TargetJob(id = "test", dependsOn = listOf("build"))
        )

        val expression = GitHubActionsProjectionInspection.jobCondition(manifest.jobs.last(), manifest)

        assertNull(expression)
    }

    @Test
    fun providerApprovalSkipPathIsEvaluatedButNeverAfterCancellation() {
        val manifest = manifest(
            TargetJob(id = "approve", metadata = mapOf("providerApprovalPayload" to "true")),
            TargetJob(id = "deploy", dependsOn = listOf("approve"))
        )

        val expression = GitHubActionsProjectionInspection.jobCondition(manifest.jobs.last(), manifest).orEmpty()

        assertTrue(expression.startsWith("!cancelled()"))
        assertTrue(expression.contains("needs.approve.result == 'skipped'"))
        assertFalse(expression.contains("always()"))
    }

    @Test
    fun mixedApprovalAndOrdinaryDependenciesRequireOrdinarySuccess() {
        val manifest = manifest(
            TargetJob(id = "build"),
            TargetJob(id = "approve", metadata = mapOf("providerApprovalPayload" to "true")),
            TargetJob(id = "deploy", dependsOn = listOf("approve", "build"))
        )

        val expression = GitHubActionsProjectionInspection.jobCondition(manifest.jobs.last(), manifest).orEmpty()

        assertTrue(expression.startsWith("!cancelled()"))
        assertTrue(expression.contains("needs.approve.result == 'success'"))
        assertTrue(expression.contains("needs.approve.result == 'skipped'"))
        assertTrue(expression.contains("needs.build.result == 'success'"))
        assertFalse(expression.contains("needs.build.result == 'skipped'"))
        assertFalse(expression.contains("always()"))
    }

    @Test
    fun errorHandlerRunsOnFailureButNotAfterWorkflowCancellation() {
        val manifest = manifest(
            TargetJob(id = "recover", metadata = mapOf("errorHandler" to "true"))
        )

        val expression = GitHubActionsProjectionInspection.jobCondition(manifest.jobs.single(), manifest)

        assertEquals("!cancelled() && failure()", expression)
        assertFalse(expression.orEmpty().contains("always()"))
    }

    private fun manifest(vararg jobs: TargetJob) = TargetManifest(
        target = "github-actions",
        flowName = plan.flowName,
        compatibility = compatibility,
        jobs = jobs.toList()
    )
}
