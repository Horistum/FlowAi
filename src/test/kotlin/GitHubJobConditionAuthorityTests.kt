package org.flowlang.targets.builtin

import java.io.File
import org.flowlang.ast.BooleanLiteralNode
import org.flowlang.ast.ExpressionNode
import org.flowlang.ast.LogicalExpressionNode
import org.flowlang.ast.UnaryExpressionNode
import org.flowlang.parser.ExpressionParser
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.generators.manifest.TargetStep
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

    private fun download(source: String?) = TargetStep(
        id = "restore", name = "Restore workspace", type = "adapter-continuity-download",
        metadata = source?.let { mapOf("continuitySourceJob" to it) }.orEmpty()
    )

    @Test
    fun conditionalWorkspaceProducerRequiresSuccessForEitherBranch() {
        for (guard in listOf("true", "false", "not (true)", "true or false")) {
            val m = manifest(
                TargetJob(id = "snapshot", metadata = mapOf("condition" to guard)),
                TargetJob(id = "build", dependsOn = listOf("snapshot"), steps = listOf(download("snapshot")))
            )
            // Deliberately omit the consumer guard: the availability rule must stand on
            // its own even though the full renderer separately rejects branch escape.
            assertEquals("!cancelled() && needs.snapshot.result == 'success'",
                GitHubActionsProjectionInspection.jobCondition(m.jobs.last(), m))
        }
    }

    @Test
    fun failedSkippedOrCancelledProductionNeverMakesWorkspaceAvailable() {
        fun evaluate(node: ExpressionNode): Boolean = when (node) {
            is BooleanLiteralNode -> node.value
            is UnaryExpressionNode -> { require(node.operator == "not"); !evaluate(node.operand) }
            is LogicalExpressionNode -> when (node.operator) {
                "and" -> node.operands.map(::evaluate).all { it }
                "or" -> node.operands.map(::evaluate).any { it }
                else -> error("Unexpected operator")
            }
            else -> error("Unexpected expression $node")
        }
        for (producerGuard in listOf("true", "false")) {
            val m = manifest(TargetJob(id = "snapshot", metadata = mapOf("condition" to producerGuard)),
                TargetJob(id = "build", dependsOn = listOf("snapshot"), steps = listOf(download("snapshot"))))
            val generated = GitHubActionsProjectionInspection.jobCondition(m.jobs.last(), m)!!
            for (cancelled in listOf(false, true)) {
                for (outcome in listOf("success", "failure", "skipped", "cancelled")) {
                    var bound = generated.replace("cancelled()", cancelled.toString())
                    for (literal in listOf("success", "failure", "skipped", "cancelled")) {
                        bound = bound.replace("needs.snapshot.result == '$literal'", (outcome == literal).toString())
                    }
                    val expression = bound.replace("&&", " and ").replace("||", " or ").replace("!", "not ")
                    assertEquals(!cancelled && outcome == "success", evaluate(ExpressionParser.parseSource(expression)),
                        "producerGuard=$producerGuard outcome=$outcome cancelled=$cancelled")
                }
            }
        }
    }

    @Test
    fun workspaceSuccessTakesPrecedenceOverApprovalSkipMetadata() {
        for (metadata in listOf(mapOf("providerApprovalPayload" to "true"),
            mapOf("providerApprovalPayload" to "true", "condition" to "false"))) {
            val m = manifest(TargetJob(id = "snapshot", metadata = metadata),
                TargetJob(id = "build", dependsOn = listOf("snapshot"), steps = listOf(download("snapshot"))))
            assertEquals("!cancelled() && needs.snapshot.result == 'success'",
                GitHubActionsProjectionInspection.jobCondition(m.jobs.last(), m))
        }
    }

    @Test
    fun mixedDependenciesDistinguishDataAvailabilityFromSkippedOrdering() {
        val m = manifest(
            TargetJob(id = "snapshot", metadata = mapOf("condition" to "true")),
            TargetJob(id = "optional", metadata = mapOf("condition" to "false")),
            TargetJob(id = "approval", metadata = mapOf("providerApprovalPayload" to "true")),
            TargetJob(id = "ordinary"),
            TargetJob(id = "build", dependsOn = listOf("snapshot", "optional", "approval", "ordinary"),
                metadata = mapOf("condition" to "true"), steps = listOf(download("snapshot")))
        )
        assertEquals("!cancelled() && (true) && needs.snapshot.result == 'success' && " +
            "(needs.optional.result == 'success' || (needs.optional.result == 'skipped' && !(false))) && " +
            "(needs.approval.result == 'success' || needs.approval.result == 'skipped') && " +
            "needs.ordinary.result == 'success'",
            GitHubActionsProjectionInspection.jobCondition(m.jobs.last(), m))
    }

    @Test
    fun unconditionalWorkspaceRetainsNativeDefaultSuccessSemantics() {
        val m = manifest(TargetJob(id = "snapshot"),
            TargetJob(id = "build", dependsOn = listOf("snapshot"), steps = listOf(download("snapshot"))))
        assertNull(GitHubActionsProjectionInspection.jobCondition(m.jobs.last(), m))
    }

    @Test
    fun missingWorkspaceSourceOrOrderingCannotFallBackToGenericScheduling() {
        for ((source, dependencies) in listOf(null to listOf("snapshot"), "snapshot" to emptyList(),
            "other" to listOf("snapshot"))) {
            val m = manifest(TargetJob(id = "snapshot", metadata = mapOf("condition" to "false")),
                TargetJob(id = "build", dependsOn = dependencies, steps = listOf(download(source))))
            assertFailsWith<IllegalArgumentException> {
                GitHubActionsProjectionInspection.jobCondition(m.jobs.last(), m)
            }
        }
    }

    private fun manifest(vararg jobs: TargetJob) = TargetManifest(
        target = "github-actions",
        flowName = plan.flowName,
        compatibility = compatibility,
        jobs = jobs.toList()
    )
}
