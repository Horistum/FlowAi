package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.planner.*
import org.flowlang.standard.FlowStandardVersions

/**
 * Platform-neutral generator contract output.
 *
 * TargetManifest is the auditable artifact between Flow ExecutionPlan and vendor syntax. Renderers
 * render this manifest; they do not re-plan Flow. v0.9.5.x removes raw command strings from this
 * boundary: actions carry structured materialization status instead of shell or CLI command text.
 */
data class TargetManifest(
    val manifestVersion: String = FlowStandardVersions.TARGET_MANIFEST_VERSION,
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val target: String,
    val flowName: String,
    val compatibility: CompatibilityReport,
    val inputs: List<TargetInput> = emptyList(),
    val jobs: List<TargetJob> = emptyList(),
    val mappingNotes: List<TargetMappingNote> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

data class TargetMappingNote(
    val level: String,
    val target: String,
    val nodeId: String,
    val feature: String,
    val message: String
)

data class TargetInput(
    val name: String,
    val type: String = "text",
    val required: Boolean = false,
    val defaultValue: String? = null,
    val choices: List<String> = emptyList()
)

data class TargetJob(
    val id: String,
    val name: String = id,
    val dependsOn: List<String> = emptyList(),
    val steps: List<TargetStep> = emptyList(),
    val mappingNotes: List<TargetMappingNote> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

data class TargetStep(
    val id: String,
    val name: String = id,
    val type: String,
    val module: String? = null,
    val action: String? = null,
    val target: String? = null,
    val materialization: TargetMaterialization = TargetMaterialization.semanticOnly("No materialization attached to this non-action projection step."),
    val run: String? = null,
    val dependsOn: List<String> = emptyList(),
    val params: Map<String, String> = emptyMap(),
    val children: List<TargetStep> = emptyList(),
    val mappingNotes: List<TargetMappingNote> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

data class TargetMaterialization(
    val status: TargetMaterializationStatus,
    val capability: String,
    val reason: String,
    val requirements: Map<String, String> = emptyMap(),
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        fun native(capability: String, reason: String, metadata: Map<String, String> = emptyMap()): TargetMaterialization =
            TargetMaterialization(TargetMaterializationStatus.NATIVE, capability, reason, metadata = metadata)

        fun adapterRequired(capability: String, reason: String, requirements: Map<String, String> = emptyMap()): TargetMaterialization =
            TargetMaterialization(TargetMaterializationStatus.ADAPTER_REQUIRED, capability, reason, requirements)

        fun semanticOnly(reason: String, capability: String = "flow.semantic"): TargetMaterialization =
            TargetMaterialization(TargetMaterializationStatus.SEMANTIC_ONLY, capability, reason)

        fun blocked(capability: String, reason: String): TargetMaterialization =
            TargetMaterialization(TargetMaterializationStatus.BLOCKED, capability, reason)
    }
}

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

class JenkinsManifestGenerator : TargetManifestGenerator {
    override val target: String = "jenkins"

    override fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest {
        val steps = plan.nodes.toJenkinsTargetSteps(target)
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = plan.inputs.map { it.toTargetInput() },
            jobs = listOf(TargetJob(id = sanitizeId(plan.flowName), name = plan.flowName, steps = steps)),
            mappingNotes = compatibility.toMappingNotes(target),
            metadata = baseMetadata(plan, "JenkinsManifestGenerator") + ("nodePreserving" to "true")
        )
    }
}

class GitHubActionsManifestGenerator : TargetManifestGenerator {
    override val target: String = "github-actions"

    override fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest {
        val jobs = mutableListOf<TargetJob>()
        plan.nodes.forEach { it.toTargetJobs(jobs, condition = null, targetName = target) }
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = plan.inputs.map { it.toTargetInput() },
            jobs = jobs.ifEmpty { listOf(TargetJob(id = sanitizeId(plan.flowName), name = plan.flowName, steps = listOf(emptyProjectionStep(plan.flowName)))) },
            mappingNotes = compatibility.toMappingNotes(target),
            metadata = baseMetadata(plan, "GitHubActionsManifestGenerator") + ("jobPerTask" to "true")
        )
    }
}

class TektonManifestGenerator : TargetManifestGenerator {
    override val target: String = "tekton"

    override fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest {
        val jobs = mutableListOf<TargetJob>()
        plan.nodes.forEach { it.toTargetJobs(jobs, condition = null, targetName = target) }
        val resolvedJobs = jobs.ifEmpty { listOf(TargetJob(id = sanitizeId(plan.flowName), name = plan.flowName, steps = listOf(emptyProjectionStep(plan.flowName)))) }
        val inputs = plan.inputs.map { it.toTargetInput() }
        val partialNote = TargetMappingNote("warning", target, "manifest", "target.partial", "Tekton renderer is a partial generator and requires notes-driven target projection before production use.")
        val untranslatableConditionNotes = resolvedJobs.mapNotNull { job ->
            val condition = job.metadata["condition"]
            if (condition != null && TargetExpressionTranslator.tektonWhen(condition, inputs) == null) {
                TargetMappingNote(
                    level = "error",
                    target = target,
                    nodeId = job.id,
                    feature = "condition.unsupported",
                    message = "Flow condition cannot be enforced as a native Tekton 'when' guard; without resolution the task would run unconditionally. Use a supported condition shape or an explicit target note before production use: $condition"
                )
            } else null
        }
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = inputs,
            jobs = resolvedJobs,
            mappingNotes = compatibility.toMappingNotes(target) + partialNote + untranslatableConditionNotes,
            metadata = baseMetadata(plan, "TektonManifestGenerator") + mapOf("supportLevel" to "partial", "jobPerTask" to "true")
        )
    }
}

private fun PlanInput.toTargetInput(): TargetInput = TargetInput(name, type, required, defaultValue, choices)

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

private fun List<PlanNode>.toJenkinsTargetSteps(targetName: String): List<TargetStep> {
    val flowHandler = lastOrNull() as? TryPlanNode
    if (flowHandler != null && flowHandler.body.isEmpty() && flowHandler.errorHandler.isNotEmpty()) {
        val bodyNodes = dropLast(1)
        if (bodyNodes.isNotEmpty()) {
            val bodyStep = TargetStep(
                id = sanitizeId("flow_1_body"),
                name = "flow_1 body",
                type = "try-body",
                children = bodyNodes.flatMap { it.toTargetSteps(targetName) },
                metadata = mapOf("sourceNodeKind" to flowHandler.kind, "tryRole" to "body")
            )
            val handlerStep = TargetStep(
                id = sanitizeId("flow_1_handler"),
                name = "flow_1 error handler",
                type = "error-handler",
                children = flowHandler.errorHandler.flatMap { it.toTargetSteps(targetName) },
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
    return flatMap { it.toTargetSteps(targetName) }
}

internal fun PlanNode.toTargetSteps(targetName: String = "notes-driven"): List<TargetStep> = when (this) {
    is TaskNode -> listOf(toTargetStep(targetName))
    is ConditionNode -> {
        val out = mutableListOf<TargetStep>()
        if (then.isNotEmpty()) out += TargetStep(
            id = sanitizeId("${id}_then"),
            name = "$id then",
            type = "condition",
            params = mapOf("condition" to condition),
            children = then.flatMap { it.toTargetSteps(targetName) },
            metadata = mapOf("sourceNodeKind" to kind, "branch" to "then")
        )
        if (otherwise.isNotEmpty()) out += TargetStep(
            id = sanitizeId("${id}_else"),
            name = "$id else",
            type = "condition",
            params = mapOf("condition" to "not ($condition)"),
            children = otherwise.flatMap { it.toTargetSteps(targetName) },
            metadata = mapOf("sourceNodeKind" to kind, "branch" to "else")
        )
        out
    }
    is LoopNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = "loop", params = mapOf("item" to item, "source" to source), children = body.flatMap { it.toTargetSteps(targetName) }, metadata = mapOf("sourceNodeKind" to kind, "supportLevel" to "partial")))
    is ParallelGroupNode -> listOf(TargetStep(
        id = sanitizeId(id), name = id, type = "parallel",
        children = branches.mapIndexed { index, branch -> TargetStep(id = sanitizeId(branch.name ?: "branch-${index + 1}"), name = branch.name ?: "branch-${index + 1}", type = "parallel-branch", children = branch.steps.flatMap { it.toTargetSteps(targetName) }, metadata = mapOf("sourceNodeKind" to "ParallelBranch")) },
        metadata = mapOf("sourceNodeKind" to kind, "failFast" to failFast.toString())
    ))
    is MatchPlanNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = "match", params = mapOf("source" to source), children = cases.flatMap { c -> c.steps.flatMap { it.toTargetSteps(targetName) } } + errorCase.flatMap { it.toTargetSteps(targetName) } + defaultSteps.flatMap { it.toTargetSteps(targetName) }, metadata = mapOf("sourceNodeKind" to kind, "supportLevel" to "partial")))
    is RetryGroupNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = "retry", params = mapOf("max" to max.toString(), "delay" to delay, "backoff" to backoff), children = body.flatMap { it.toTargetSteps(targetName) }, metadata = mapOf("sourceNodeKind" to kind)))
    is TryPlanNode -> {
        val bodyStep = TargetStep(
            id = sanitizeId("${id}_body"),
            name = "$id body",
            type = "try-body",
            children = body.flatMap { it.toTargetSteps(targetName) },
            metadata = mapOf("sourceNodeKind" to kind, "tryRole" to "body")
        )
        val handlerStep = TargetStep(
            id = sanitizeId("${id}_handler"),
            name = "$id error handler",
            type = "error-handler",
            children = errorHandler.flatMap { it.toTargetSteps(targetName) },
            metadata = mapOf("sourceNodeKind" to kind, "tryRole" to "errorHandler")
        )
        if (body.isEmpty()) listOf(handlerStep) else listOf(TargetStep(
            id = sanitizeId(id),
            name = id,
            type = "try",
            children = listOf(bodyStep, handlerStep),
            metadata = mapOf("sourceNodeKind" to kind, "errorHandlerCount" to errorHandler.size.toString())
        ))
    }
    is ApprovalNode -> listOf(TargetStep(
        id = sanitizeId(id),
        name = id,
        type = "approval",
        dependsOn = dependsOn.map(::sanitizeId),
        params = mapOf("mode" to mode) + (message?.let { mapOf("message" to it) } ?: emptyMap()),
        materialization = TargetMaterialization.native("approval.require", "Approval is represented as a target-native review boundary when the target supports it."),
        metadata = mapOf("sourceNodeKind" to kind, "resultName" to (resultName ?: "")).filterValues { it.isNotBlank() }
    ))
    is DataOpNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = kind.lowercase(), params = mapOf("target" to (target ?: ""), "detail" to (detail ?: "")).filterValues { it.isNotBlank() }.mapValues { (_, value) -> normalizeTargetParam(value) }, mappingNotes = listOf(
        TargetMappingNote("warning", "all", id, "dataop.not-materialised", "Flow ${kind.lowercase()} is a Flow-layer data operation and is not materialised by this projection; downstream steps must not depend on its result at runtime without notes-driven materialization.")
    ), materialization = TargetMaterialization.semanticOnly("Flow-layer data operation is not materialized as target execution.", "data.${kind.lowercase()}"), metadata = mapOf("sourceNodeKind" to kind)))
    is ControlNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = kind.lowercase(), params = mapOf("detail" to (detail ?: "")).filterValues { it.isNotBlank() }.mapValues { (_, value) -> normalizeTargetParam(value) }, materialization = TargetMaterialization.semanticOnly("Control node is represented structurally and has no executable command."), metadata = mapOf("sourceNodeKind" to kind)))
}

private fun PlanNode.toTargetJobs(out: MutableList<TargetJob>, condition: String?, targetName: String) {
    when (this) {
        is TaskNode -> {
            val step = toTargetStep(targetName).let { if (condition != null) it.copy(metadata = it.metadata + ("condition" to condition)) else it }
            out += TargetJob(id = sanitizeId(id), name = id, dependsOn = dependsOn.map(::sanitizeId), steps = listOf(step), metadata = mapOfNotNull("condition" to condition))
        }
        is ApprovalNode -> {
            val step = toTargetSteps(targetName).single().let { if (condition != null) it.copy(metadata = it.metadata + ("condition" to condition)) else it }
            out += TargetJob(id = sanitizeId(id), name = id, dependsOn = dependsOn.map(::sanitizeId), steps = listOf(step), metadata = mapOfNotNull("condition" to condition, "approval" to "true"))
        }
        is ConditionNode -> {
            then.forEach { it.toTargetJobs(out, combineConditions(condition, this.condition), targetName) }
            otherwise.forEach { it.toTargetJobs(out, combineConditions(condition, "not (${this.condition})"), targetName) }
        }
        is ParallelGroupNode -> branches.flatMap { it.steps }.forEach { it.toTargetJobs(out, condition, targetName) }
        is RetryGroupNode -> body.forEach { it.toTargetJobs(out, condition, targetName) }
        is TryPlanNode -> {
            val previousJobIds = out.map { it.id }
            val bodyJobs = mutableListOf<TargetJob>()
            body.forEach { it.toTargetJobs(bodyJobs, condition, targetName) }
            out += bodyJobs
            if (targetName != "tekton") {
                val guardDependencies = bodyJobs.map { it.id }.ifEmpty { previousJobIds }
                val handlerJobs = mutableListOf<TargetJob>()
                errorHandler.forEach { it.toTargetJobs(handlerJobs, condition, targetName) }
                out += handlerJobs.map { job ->
                    job.copy(
                        dependsOn = (job.dependsOn + guardDependencies).distinct(),
                        metadata = job.metadata + ("errorHandler" to "true")
                    )
                }
            }
        }
        is LoopNode -> out += TargetJob(id = sanitizeId(id), name = id, steps = toTargetSteps(targetName), metadata = mapOfNotNull("condition" to condition, "supportLevel" to "partial"))
        is MatchPlanNode -> out += TargetJob(id = sanitizeId(id), name = id, steps = toTargetSteps(targetName), metadata = mapOfNotNull("condition" to condition, "supportLevel" to "partial"))
        is DataOpNode, is ControlNode -> out += TargetJob(id = sanitizeId(id), name = id, steps = toTargetSteps(targetName), metadata = mapOfNotNull("condition" to condition))
    }
}

private fun TaskNode.toTargetStep(targetName: String = "notes-driven"): TargetStep = TargetStep(
    id = sanitizeId(id),
    name = id,
    type = "action",
    module = module,
    action = action,
    target = target,
    materialization = materializationFor(this),
    dependsOn = dependsOn.map(::sanitizeId),
    params = params.mapValues { (_, value) -> normalizeTargetParam(value) },
    mappingNotes = materializationMappingNotes(this, targetName),
    metadata = mapOf(
        "sourceTask" to id,
        "sourceNodeKind" to kind,
        "resultName" to (resultName ?: ""),
        "destructive" to destructive.toString(),
        "safety" to (safety ?: "")
    ).filterValues { it.isNotBlank() }
)

private fun materializationFor(task: TaskNode): TargetMaterialization {
    val capability = listOfNotNull(task.module, task.action).joinToString(".").ifBlank { "flow.action" }
    return when (task.module to task.action) {
        "shell" to "run" -> TargetMaterialization.blocked(capability, "Raw command execution is removed from Flow Core projection. Use notes-driven capability materialization instead.")
        "standard" to "execute" -> TargetMaterialization.semanticOnly(capability = capability, reason = "Standard capability is semantic-only until a notes package declares materialization.")
        "standard" to "rollback" -> TargetMaterialization.semanticOnly(capability = capability, reason = "Rollback intent is semantic-only until target rollback materialization is declared through notes.")
        else -> TargetMaterialization.adapterRequired(capability, "No core command is generated. This action requires notes-driven target projection before it can be treated as executable.")
    }
}

private fun materializationMappingNotes(task: TaskNode, targetName: String): List<TargetMappingNote> {
    val materialization = materializationFor(task)
    val level = when (materialization.status) {
        TargetMaterializationStatus.NATIVE,
        TargetMaterializationStatus.NOTES_PROJECTED -> "info"
        TargetMaterializationStatus.ADAPTER_REQUIRED,
        TargetMaterializationStatus.DECLARATIVE_ONLY,
        TargetMaterializationStatus.SEMANTIC_ONLY -> "warning"
        TargetMaterializationStatus.UNSUPPORTED,
        TargetMaterializationStatus.BLOCKED -> "error"
    }
    val feature = when (materialization.status) {
        TargetMaterializationStatus.BLOCKED -> "materialization.blocked"
        TargetMaterializationStatus.SEMANTIC_ONLY -> "materialization.semantic-only"
        TargetMaterializationStatus.ADAPTER_REQUIRED -> "materialization.adapter-required"
        else -> "materialization.${materialization.status.name.lowercase().replace('_', '-')}"
    }
    return listOf(TargetMappingNote(level, targetName, task.id, feature, materialization.reason))
}

private fun emptyProjectionStep(flowName: String): TargetStep = TargetStep(
    id = sanitizeId("${flowName}_projection"),
    name = "$flowName projection",
    type = "projection",
    materialization = TargetMaterialization.semanticOnly("Empty flow projection has no executable target work.")
)

private fun combineConditions(a: String?, b: String): String = if (a.isNullOrBlank()) b else "($a) and ($b)"

private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> = pairs.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to v } }.toMap()

private fun normalizeTargetParam(value: String): String {
    val unquoted = unquote(value)
    val exactInterpolation = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)}""").matchEntire(unquoted)
    return exactInterpolation?.groupValues?.get(1) ?: unquoted
}

internal fun sanitizeId(value: String): String = value.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifBlank { "flow-job" }

internal fun unquote(value: String): String {
    val v = value.trim()
    return if (v.length >= 2 && v.first() == '"' && v.last() == '"') {
        v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t")
    } else v
}
