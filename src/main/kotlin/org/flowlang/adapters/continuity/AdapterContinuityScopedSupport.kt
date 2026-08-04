package org.flowlang.adapters.continuity

import java.io.File
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ControlNode
import org.flowlang.planner.DataOpNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanNode
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode

/**
 * Bounded adapter support for one continuity semantic.
 *
 * A scoped declaration is deliberately narrower than a target-wide continuity
 * claim. It proves one provider-owned producer/channel/consumer path while the
 * generic semantic remains unsupported for every undeclared plan shape.
 */
data class AdapterContinuityScopedSupport(
    val target: String,
    val family: AdapterContinuityFamily,
    val semantic: String,
    val sourceAction: String,
    val targetAction: String,
    val channel: String,
    val evidenceReferences: List<String>
) {
    val identity: String = listOf(target, family.name, semantic, sourceAction, targetAction, channel).joinToString("|")

    init {
        require(target.isNotBlank()) { "Scoped continuity support target must not be blank." }
        require(semantic in AdapterContinuitySemanticContract.byFamily.getValue(family)) {
            "Scoped continuity semantic '$semantic' does not belong to family '$family'."
        }
        require(ACTION_IDENTITY.matches(sourceAction)) {
            "Scoped continuity source action '$sourceAction' must use module.action identity."
        }
        require(ACTION_IDENTITY.matches(targetAction)) {
            "Scoped continuity target action '$targetAction' must use module.action identity."
        }
        require(channel.isNotBlank()) { "Scoped continuity channel must not be blank." }
        require(evidenceReferences.isNotEmpty()) { "Scoped continuity support requires repository evidence." }
        require(evidenceReferences.none(String::isBlank)) { "Scoped continuity evidence references must not be blank." }
        require(evidenceReferences.size == evidenceReferences.toSet().size) {
            "Scoped continuity evidence references must not contain duplicates."
        }
    }

    private companion object {
        val ACTION_IDENTITY = Regex("[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*")
    }
}

object BuiltInAdapterContinuityScopedSupport {
    val githubActionsCheckoutBuildWorkspace = AdapterContinuityScopedSupport(
        target = "github-actions",
        family = AdapterContinuityFamily.ARTIFACT,
        semantic = AdapterContinuitySemanticContract.ARTIFACT_SHARED_WORKSPACE,
        sourceAction = "git.checkout",
        targetAction = "docker.build",
        channel = "source",
        evidenceReferences = listOf(
            "src/main/kotlin/org/flowlang/targets/builtin/GitHubActionsWorkspaceContinuityPlanner.kt",
            "src/main/kotlin/org/flowlang/targets/builtin/GitHubActionsManifestRenderer.kt",
            "src/test/kotlin/GitHubActionsWorkspaceContinuityTests.kt"
        )
    )

    val declarations: List<AdapterContinuityScopedSupport> = listOf(
        githubActionsCheckoutBuildWorkspace
    )
}

object AdapterContinuityScopedSupportAuthority {
    fun matchingSupport(
        plan: ExecutionPlan,
        target: String,
        requirement: AdapterContinuityRequirement,
        declarations: List<AdapterContinuityScopedSupport> = BuiltInAdapterContinuityScopedSupport.declarations
    ): AdapterContinuityScopedSupport? {
        if (requirement.completeness != AdapterContinuityRequirementCompleteness.RESOLVED) return null
        val sourceId = requirement.sourceNodeId ?: return null
        val nodes = plan.nodes.flattenPlanNodes().filterIsInstance<TaskNode>().associateBy(TaskNode::id)
        val source = nodes[sourceId] ?: return null
        val consumer = nodes[requirement.targetNodeId] ?: return null
        val sourceAction = "${source.module}.${source.action}"
        val targetAction = "${consumer.module}.${consumer.action}"
        return declarations.singleOrNull { declaration ->
            declaration.target == target &&
                declaration.family == requirement.family &&
                declaration.semantic == requirement.semantic &&
                declaration.sourceAction == sourceAction &&
                declaration.targetAction == targetAction &&
                declaration.channel == requirement.channel
        }
    }
}

data class AdapterContinuityScopedSupportFinding(
    val code: String,
    val identity: String,
    val message: String
)

data class AdapterContinuityScopedSupportReport(
    val status: String,
    val findings: List<AdapterContinuityScopedSupportFinding>,
    val declarationCount: Int
)

class AdapterContinuityScopedSupportIntegrityAuthority(
    private val rootDir: File = File("."),
    private val declarations: List<AdapterContinuityScopedSupport> = BuiltInAdapterContinuityScopedSupport.declarations
) {
    fun analyze(): AdapterContinuityScopedSupportReport {
        val findings = mutableListOf<AdapterContinuityScopedSupportFinding>()
        declarations.groupingBy(AdapterContinuityScopedSupport::identity)
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
            .forEach { identity ->
                findings += finding("ADAPTER_CONTINUITY_SCOPED_SUPPORT_DUPLICATE", identity, "Scoped support identity is declared more than once.")
            }
        declarations.forEach { declaration ->
            val paths = declaration.evidenceReferences.map { it.substringBefore('#') }
            declaration.evidenceReferences.forEach { reference ->
                if (reference.startsWith("http://") || reference.startsWith("https://")) {
                    findings += finding(
                        "ADAPTER_CONTINUITY_SCOPED_SUPPORT_EXTERNAL_EVIDENCE",
                        declaration.identity,
                        "External documentation cannot certify provider implementation: $reference"
                    )
                }
                if (!File(rootDir, reference.substringBefore('#')).isFile) {
                    findings += finding(
                        "ADAPTER_CONTINUITY_SCOPED_SUPPORT_EVIDENCE_MISSING",
                        declaration.identity,
                        "Evidence reference does not resolve: $reference"
                    )
                }
            }
            if (paths.none { it.startsWith("src/main/") }) {
                findings += finding(
                    "ADAPTER_CONTINUITY_SCOPED_SUPPORT_IMPLEMENTATION_MISSING",
                    declaration.identity,
                    "Scoped support requires production implementation evidence."
                )
            }
            if (paths.none { it.startsWith("src/test/") || it.startsWith("tests/") }) {
                findings += finding(
                    "ADAPTER_CONTINUITY_SCOPED_SUPPORT_BEHAVIOR_MISSING",
                    declaration.identity,
                    "Scoped support requires independent behavioral evidence."
                )
            }
        }
        return AdapterContinuityScopedSupportReport(
            status = if (findings.isEmpty()) "PASS" else "FAIL",
            findings = findings.sortedWith(compareBy({ it.identity }, { it.code }, { it.message })),
            declarationCount = declarations.size
        )
    }

    private fun finding(code: String, identity: String, message: String) =
        AdapterContinuityScopedSupportFinding(code, identity, message)
}

private fun List<PlanNode>.flattenPlanNodes(): List<PlanNode> = flatMap { node ->
    listOf(node) + when (node) {
        is ConditionNode -> node.then.flattenPlanNodes() + node.otherwise.flattenPlanNodes()
        is LoopNode -> node.body.flattenPlanNodes()
        is ParallelGroupNode -> node.branches.flatMap { it.steps.flattenPlanNodes() }
        is MatchPlanNode -> node.cases.flatMap { it.steps.flattenPlanNodes() } +
            node.errorCase.flattenPlanNodes() + node.defaultSteps.flattenPlanNodes()
        is RetryGroupNode -> node.body.flattenPlanNodes()
        is TryPlanNode -> node.body.flattenPlanNodes() + node.errorHandler.flattenPlanNodes()
        is TaskNode, is ApprovalNode, is DataOpNode, is ControlNode -> emptyList()
    }
}
