package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.planner.*
import org.flowlang.standard.FlowStandardVersions

enum class TargetMaterializationStatus {
    NATIVE,
    NOTES_PROJECTED,
    ADAPTER_REQUIRED,
    DECLARATIVE_ONLY,
    SEMANTIC_ONLY,
    UNSUPPORTED,
    BLOCKED
}

interface TargetManifestGenerator {
    val target: String
    fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest
}

/**
 * Final generation boundary shared by CLI, conformance and tests.
 *
 * Concrete generators can only build an unreconciled manifest. The public
 * [generate] method always reconciles capability claims against materialization
 * and projection evidence before the artifact can leave the generator.
 */
abstract class ReconciledTargetManifestGenerator : TargetManifestGenerator {
    final override fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest =
        buildManifest(plan, compatibility).reconcileCompatibilityReadiness()

    protected abstract fun buildManifest(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest
}

object TargetManifestGenerationPipeline {
    fun generatorFor(target: String): TargetManifestGenerator = when (target) {
        "jenkins" -> JenkinsManifestGenerator()
        "github-actions" -> GitHubActionsManifestGenerator()
        "tekton" -> TektonManifestGenerator()
        else -> error("No canonical TargetManifest generator is registered for target '$target'.")
    }

    fun generate(
        plan: ExecutionPlan,
        compatibility: CompatibilityReport,
        strict: Boolean = false
    ): TargetManifest {
        compatibility.assertAllowed(strict = strict)
        return generatorFor(compatibility.target).generate(plan, compatibility)
    }
}

class JenkinsManifestGenerator : ReconciledTargetManifestGenerator() {
    override val target: String = "jenkins"

    override fun buildManifest(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest {
        val steps = plan.nodes.toJenkinsTargetSteps(target, compatibility.projectionRules)
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = plan.inputs.map { it.toTargetInput() },
            triggers = plan.triggers.map { it.toTargetTrigger() },
            jobs = listOf(TargetJob(id = sanitizeId(plan.flowName), name = plan.flowName, steps = steps)),
            mappingNotes = compatibility.toMappingNotes(target),
            metadata = baseMetadata(plan, "JenkinsManifestGenerator") + ("nodePreserving" to "true")
        )
    }
}

class GitHubActionsManifestGenerator : ReconciledTargetManifestGenerator() {
    override val target: String = "github-actions"

    override fun buildManifest(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest {
        val jobs = mutableListOf<TargetJob>()
        plan.nodes.forEach {
            it.toTargetJobs(jobs, condition = null, targetName = target, projectionRules = compatibility.projectionRules)
        }
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = plan.inputs.map { it.toTargetInput() },
            triggers = plan.triggers.map { it.toTargetTrigger() },
            jobs = jobs.ifEmpty {
                listOf(TargetJob(
                    id = sanitizeId(plan.flowName),
                    name = plan.flowName,
                    steps = listOf(emptyProjectionStep(plan.flowName))
                ))
            },
            mappingNotes = compatibility.toMappingNotes(target),
            metadata = baseMetadata(plan, "GitHubActionsManifestGenerator") + ("jobPerTask" to "true")
        )
    }
}

class TektonManifestGenerator : ReconciledTargetManifestGenerator() {
    override val target: String = "tekton"

    override fun buildManifest(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest {
        val jobs = mutableListOf<TargetJob>()
        plan.nodes.forEach {
            it.toTargetJobs(jobs, condition = null, targetName = target, projectionRules = compatibility.projectionRules)
        }
        val resolvedJobs = jobs.ifEmpty {
            listOf(TargetJob(
                id = sanitizeId(plan.flowName),
                name = plan.flowName,
                steps = listOf(emptyProjectionStep(plan.flowName))
            ))
        }
        val inputs = plan.inputs.map { it.toTargetInput() }
        val partialNote = TargetMappingNote(
            "warning",
            target,
            "manifest",
            "target.partial",
            "Tekton renderer is a partial generator and requires notes-driven target projection before production use."
        )
        val untranslatableConditionNotes = resolvedJobs.mapNotNull { job ->
            val condition = job.metadata["condition"]
            if (
                condition != null &&
                TargetExpressionTranslator.tektonWhen(condition, inputs, compatibility.expressionSupport) == null
            ) {
                TargetMappingNote(
                    level = "error",
                    target = target,
                    nodeId = job.id,
                    feature = "condition.unsupported",
                    message = "Flow condition cannot be enforced as a native Tekton 'when' guard; without resolution the task would run unconditionally. Use a supported condition shape or an explicit target note before production use: $condition"
                )
            } else {
                null
            }
        }
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = inputs,
            jobs = resolvedJobs,
            mappingNotes = compatibility.toMappingNotes(target) + partialNote + untranslatableConditionNotes,
            metadata = baseMetadata(plan, "TektonManifestGenerator") + mapOf(
                "supportLevel" to "partial",
                "jobPerTask" to "true"
            )
        )
    }
}

private fun PlanInput.toTargetInput(): TargetInput = TargetInput(name, type, required, defaultValue, choices)

private fun PlanTrigger.toTargetTrigger(): TargetTrigger = TargetTrigger(
    id = id,
    type = type,
    workflows = workflows,
    scheduleKind = schedule?.kind,
    scheduleExpression = schedule?.expression,
    timezone = schedule?.timezone,
    event = event,
    params = params
)

private fun baseMetadata(plan: ExecutionPlan, generator: String): Map<String, String> = mapOf(
    "sourcePlanVersion" to plan.planVersion,
    "generator" to generator,
    "standardVersion" to FlowStandardVersions.FLOW_STANDARD_VERSION,
    "projectionModel" to "notes-driven-materialization"
)

private fun CompatibilityReport.toMappingNotes(targetName: String): List<TargetMappingNote> = issues.map {
    TargetMappingNote(
        level = it.level.name.lowercase(),
        target = targetName,
        nodeId = it.nodeId,
        feature = it.feature,
        message = it.message
    )
}

private fun List<PlanNode>.toJenkinsTargetSteps(
    targetName: String,
    projectionRules: List<TargetProjectionRule>
): List<TargetStep> {
    val flowHandler = lastOrNull() as? TryPlanNode
    if (flowHandler != null && flowHandler.body.isEmpty() && flowHandler.errorHandler.isNotEmpty()) {
        val bodyNodes = dropLast(1)
        if (bodyNodes.isNotEmpty()) {
            val bodyStep = TargetStep(
                id = sanitizeId("flow_1_body"),
                name = "flow_1 body",
                type = "try-body",
                children = bodyNodes.flatMap { it.toTargetSteps(targetName, projectionRules) },
                metadata = mapOf("sourceNodeKind" to flowHandler.kind, "tryRole" to "body")
            )
            val handlerStep = TargetStep(
                id = sanitizeId("flow_1_handler"),
                name = "flow_1 error handler",
                type = "error-handler",
                children = flowHandler.errorHandler.flatMap { it.toTargetSteps(targetName, projectionRules) },
                metadata = mapOf("sourceNodeKind" to flowHandler.kind, "tryRole" to "errorHandler")
            )
            return listOf(TargetStep(
                id = sanitizeId("flow_1"),
                name = "flow_1",
                type = "try",
                children = listOf(bodyStep, handlerStep),
                metadata = mapOf(
                    "sourceNodeKind" to flowHandler.kind,
                    "flowLevelErrorBoundary" to "true",
                    "errorHandlerCount" to flowHandler.errorHandler.size.toString()
                )
            ))
        }
    }
    return flatMap { it.toTargetSteps(targetName, projectionRules) }
}

internal fun PlanNode.toTargetSteps(
    targetName: String = "notes-driven",
    projectionRules: List<TargetProjectionRule> = emptyList()
): List<TargetStep> = when (this) {
    is TaskNode -> listOf(toTargetStep(targetName, projectionRules))
    is ConditionNode -> {
        val out = mutableListOf<TargetStep>()
        if (then.isNotEmpty()) {
            out += TargetStep(
                id = sanitizeId("${id}_then"),
                name = "$id then",
                type = "condition",
                params = mapOf("condition" to condition),
                children = then.flatMap { it.toTargetSteps(targetName, projectionRules) },
                metadata = mapOf("sourceNodeKind" to kind, "branch" to "then")
            )
        }
        if (otherwise.isNotEmpty()) {
            out += TargetStep(
                id = sanitizeId("${id}_else"),
                name = "$id else",
                type = "condition",
                params = mapOf("condition" to "not ($condition)"),
                children = otherwise.flatMap { it.toTargetSteps(targetName, projectionRules) },
                metadata = mapOf("sourceNodeKind" to kind, "branch" to "else")
            )
        }
        out
    }
    is LoopNode -> listOf(TargetStep(
        id = sanitizeId(id),
        name = id,
        type = "loop",
        params = mapOf("item" to item, "source" to source),
        children = body.flatMap { it.toTargetSteps(targetName, projectionRules) },
        metadata = mapOf("sourceNodeKind" to kind, "supportLevel" to "partial")
    ))
    is ParallelGroupNode -> listOf(TargetStep(
        id = sanitizeId(id),
        name = id,
        type = "parallel",
        children = branches.mapIndexed { index, branch ->
            TargetStep(
                id = sanitizeId(branch.name ?: "branch-${index + 1}"),
                name = branch.name ?: "branch-${index + 1}",
                type = "parallel-branch",
                children = branch.steps.flatMap { it.toTargetSteps(targetName, projectionRules) },
                metadata = mapOf("sourceNodeKind" to "ParallelBranch")
            )
        },
        metadata = mapOf("sourceNodeKind" to kind, "failFast" to failFast.toString())
    ))
    is MatchPlanNode -> listOf(TargetStep(
        id = sanitizeId(id),
        name = id,
        type = "match",
        params = mapOf("source" to source),
        children = cases.flatMap { case ->
            case.steps.flatMap { it.toTargetSteps(targetName, projectionRules) }
        } + errorCase.flatMap { it.toTargetSteps(targetName, projectionRules) } +
            defaultSteps.flatMap { it.toTargetSteps(targetName, projectionRules) },
        metadata = mapOf("sourceNodeKind" to kind, "supportLevel" to "partial")
    ))
    is RetryGroupNode -> listOf(TargetStep(
        id = sanitizeId(id),
        name = id,
        type = "retry",
        params = mapOf("max" to max.toString(), "delay" to delay, "backoff" to backoff),
        children = body.flatMap { it.toTargetSteps(targetName, projectionRules) },
        metadata = mapOf("sourceNodeKind" to kind)
    ))
    is TryPlanNode -> {
        val bodyStep = TargetStep(
            id = sanitizeId("${id}_body"),
            name = "$id body",
            type = "try-body",
            children = body.flatMap { it.toTargetSteps(targetName, projectionRules) },
            metadata = mapOf("sourceNodeKind" to kind, "tryRole" to "body")
        )
        val handlerStep = TargetStep(
            id = sanitizeId("${id}_handler"),
            name = "$id error handler",
            type = "error-handler",
            children = errorHandler.flatMap { it.toTargetSteps(targetName, projectionRules) },
            metadata = mapOf("sourceNodeKind" to kind, "tryRole" to "errorHandler")
        )
        if (body.isEmpty()) {
            listOf(handlerStep)
        } else {
            listOf(TargetStep(
                id = sanitizeId(id),
                name = id,
                type = "try",
                children = listOf(bodyStep, handlerStep),
                metadata = mapOf("sourceNodeKind" to kind, "errorHandlerCount" to errorHandler.size.toString())
            ))
        }
    }
    is ApprovalNode -> listOf(TargetStep(
        id = sanitizeId(id),
        name = id,
        type = "approval",
        dependsOn = dependsOn.map(::sanitizeId),
        params = mapOf("mode" to mode) + (message?.let { mapOf("message" to it) } ?: emptyMap()),
        materialization = TargetMaterialization.native(
            "approval.require",
            "Approval is represented as a target-native review boundary when the target supports it."
        ),
        metadata = mapOf(
            "sourceNodeKind" to kind,
            "resultName" to (resultName ?: "")
        ).filterValues { it.isNotBlank() }
    ))
    is DataOpNode -> listOf(TargetStep(
        id = sanitizeId(id),
        name = id,
        type = kind.lowercase(),
        params = mapOf(
            "target" to (target ?: ""),
            "detail" to (detail ?: "")
        ).filterValues { it.isNotBlank() }.mapValues { (_, value) -> normalizeTargetParam(value) },
        mappingNotes = listOf(TargetMappingNote(
            "warning",
            "all",
            id,
            "dataop.not-materialised",
            "Flow ${kind.lowercase()} is a Flow-layer data operation and is not materialised by this projection; downstream steps must not depend on its result at runtime without notes-driven materialization."
        )),
        materialization = TargetMaterialization.semanticOnly(
            "Flow-layer data operation is not materialized as target execution.",
            "data.${kind.lowercase()}"
        ),
        metadata = mapOf("sourceNodeKind" to kind)
    ))
    is ControlNode -> listOf(TargetStep(
        id = sanitizeId(id),
        name = id,
        type = kind.lowercase(),
        params = mapOf("detail" to (detail ?: ""))
            .filterValues { it.isNotBlank() }
            .mapValues { (_, value) -> normalizeTargetParam(value) },
        materialization = TargetMaterialization.semanticOnly(
            "Control node is represented structurally and has no executable command."
        ),
        metadata = mapOf("sourceNodeKind" to kind)
    ))
}

private fun PlanNode.toTargetJobs(
    out: MutableList<TargetJob>,
    condition: String?,
    targetName: String,
    projectionRules: List<TargetProjectionRule>
) {
    when (this) {
        is TaskNode -> {
            val step = toTargetStep(targetName, projectionRules).let {
                if (condition != null) it.copy(metadata = it.metadata + ("condition" to condition)) else it
            }
            out += TargetJob(
                id = sanitizeId(id),
                name = id,
                dependsOn = dependsOn.map(::sanitizeId),
                steps = listOf(step),
                metadata = mapOfNotNull("condition" to condition)
            )
        }
        is ApprovalNode -> {
            val step = toTargetSteps(targetName, projectionRules).single().let {
                if (condition != null) it.copy(metadata = it.metadata + ("condition" to condition)) else it
            }
            out += TargetJob(
                id = sanitizeId(id),
                name = id,
                dependsOn = dependsOn.map(::sanitizeId),
                steps = listOf(step),
                metadata = mapOfNotNull("condition" to condition, "approval" to "true")
            )
        }
        is ConditionNode -> {
            then.forEach {
                it.toTargetJobs(out, combineConditions(condition, this.condition), targetName, projectionRules)
            }
            otherwise.forEach {
                it.toTargetJobs(out, combineConditions(condition, "not (${this.condition})"), targetName, projectionRules)
            }
        }
        is ParallelGroupNode -> branches.flatMap { it.steps }
            .forEach { it.toTargetJobs(out, condition, targetName, projectionRules) }
        is RetryGroupNode -> body.forEach { it.toTargetJobs(out, condition, targetName, projectionRules) }
        is TryPlanNode -> {
            val previousJobIds = out.map { it.id }
            val bodyJobs = mutableListOf<TargetJob>()
            body.forEach { it.toTargetJobs(bodyJobs, condition, targetName, projectionRules) }
            out += bodyJobs
            val guardDependencies = bodyJobs.map { it.id }.ifEmpty { previousJobIds }
            val handlerJobs = mutableListOf<TargetJob>()
            errorHandler.forEach { it.toTargetJobs(handlerJobs, condition, targetName, projectionRules) }
            out += handlerJobs.map { job ->
                job.copy(
                    dependsOn = (job.dependsOn + guardDependencies).distinct(),
                    metadata = job.metadata + ("errorHandler" to "true")
                )
            }
        }
        is LoopNode -> out += TargetJob(
            id = sanitizeId(id),
            name = id,
            steps = toTargetSteps(targetName, projectionRules),
            metadata = mapOfNotNull("condition" to condition, "supportLevel" to "partial")
        )
        is MatchPlanNode -> out += TargetJob(
            id = sanitizeId(id),
            name = id,
            steps = toTargetSteps(targetName, projectionRules),
            metadata = mapOfNotNull("condition" to condition, "supportLevel" to "partial")
        )
        is DataOpNode, is ControlNode -> out += TargetJob(
            id = sanitizeId(id),
            name = id,
            steps = toTargetSteps(targetName, projectionRules),
            metadata = mapOfNotNull("condition" to condition)
        )
    }
}

private fun TaskNode.toTargetStep(
    targetName: String = "notes-driven",
    projectionRules: List<TargetProjectionRule> = emptyList()
): TargetStep {
    val resolution = TargetMaterializationResolver.resolve(this, targetName, projectionRules)
    return TargetStep(
        id = sanitizeId(id),
        name = id,
        type = "action",
        module = module,
        action = action,
        target = target,
        materialization = resolution.materialization,
        rendererPayload = resolution.rendererPayload,
        dependsOn = dependsOn.map(::sanitizeId),
        params = params.mapValues { (_, value) -> normalizeTargetParam(value) },
        mappingNotes = listOf(resolution.mappingNote(targetName, id)),
        metadata = mapOf(
            "sourceTask" to id,
            "sourceNodeKind" to kind,
            "resultName" to (resultName ?: ""),
            "destructive" to destructive.toString(),
            "safety" to (safety ?: "")
        ).filterValues { it.isNotBlank() } + materializationMetadata(resolution)
    )
}

private fun materializationMetadata(resolution: TargetMaterializationResolution): Map<String, String> = mapOf(
    "semanticGraph" to resolution.semanticGraph.graphId,
    "semanticNode" to resolution.negotiation.decisions.single().nodeId,
    "materializationNegotiation" to resolution.negotiation.negotiationId,
    "projectionPlan" to resolution.projectionPlan.planId,
    "projectionArtifact" to resolution.artifact.artifactId,
    "projectionArtifactKind" to resolution.artifact.kind.name
)

private fun emptyProjectionStep(flowName: String): TargetStep = TargetStep(
    id = sanitizeId("${flowName}_projection"),
    name = "$flowName projection",
    type = "projection",
    materialization = TargetMaterialization.semanticOnly("Empty flow projection has no executable target work.")
)

private fun combineConditions(a: String?, b: String): String =
    if (a.isNullOrBlank()) b else "($a) and ($b)"

private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> = pairs
    .mapNotNull { (key, value) -> value?.takeIf { it.isNotBlank() }?.let { key to value } }
    .toMap()

private fun normalizeTargetParam(value: String): String {
    val unquoted = unquote(value)
    val exactInterpolation = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)}""")
        .matchEntire(unquoted)
    return exactInterpolation?.groupValues?.get(1) ?: unquoted
}

internal fun sanitizeId(value: String): String = value.lowercase()
    .replace(Regex("[^a-z0-9_-]+"), "-")
    .trim('-')
    .ifBlank { "flow-job" }

internal fun unquote(value: String): String {
    val normalized = value.trim()
    return if (normalized.length >= 2 && normalized.first() == '"' && normalized.last() == '"') {
        normalized.substring(1, normalized.length - 1)
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
    } else {
        normalized
    }
}
