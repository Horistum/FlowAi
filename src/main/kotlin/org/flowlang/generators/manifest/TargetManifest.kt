package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.capabilities.TargetRendererPayloadKind
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
    val triggers: List<TargetTrigger> = emptyList(),
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


data class TargetTrigger(
    val id: String,
    val type: String,
    val workflows: List<String> = listOf("main"),
    val scheduleKind: String? = null,
    val scheduleExpression: String? = null,
    val timezone: String? = null,
    val event: String? = null,
    val params: Map<String, String> = emptyMap()
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

data class TargetRendererPayload(
    val kind: TargetRendererPayloadKind,
    val target: String,
    val reference: String,
    val parameters: Map<String, String> = emptyMap(),
    val evidenceReference: String
)

data class TargetStep(
    val id: String,
    val name: String = id,
    val type: String,
    val module: String? = null,
    val action: String? = null,
    val target: String? = null,
    val materialization: TargetMaterialization = TargetMaterialization.semanticOnly("No materialization attached to this non-action projection step."),
    val rendererPayload: TargetRendererPayload? = null,
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

    fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest =
        generatorFor(compatibility.target).generate(plan, compatibility)
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
        plan.nodes.forEach { it.toTargetJobs(jobs, condition = null, targetName = target, projectionRules = compatibility.projectionRules) }
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = plan.inputs.map { it.toTargetInput() },
            triggers = plan.triggers.map { it.toTargetTrigger() },
            jobs = jobs.ifEmpty { listOf(TargetJob(id = sanitizeId(plan.flowName), name = plan.flowName, steps = listOf(emptyProjectionStep(plan.flowName)))) },
            mappingNotes = compatibility.toMappingNotes(target),
            metadata = baseMetadata(plan, "GitHubActionsManifestGenerator") + ("jobPerTask" to "true")
        )
    }
}

class TektonManifestGenerator : ReconciledTargetManifestGenerator() {
    override val target: String = "tekton"

    override fun buildManifest(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest {
        val jobs = mutableListOf<TargetJob>()
        plan.nodes.forEach { it.toTargetJobs(jobs, condition = null, targetName = target, projectionRules = compatibility.projectionRules) }
        val resolvedJobs = jobs.ifEmpty { listOf(TargetJob(id = sanitizeId(plan.flowName), name = plan.flowName, steps = listOf(emptyProjectionStep(plan.flowName)))) }
        val inputs = plan.inputs.map { it.toTargetInput() }
        val partialNote = TargetMappingNote("warning", target, "manifest", "target.partial", "Tekton renderer is a partial generator and requires notes-driven target projection before production use.")
        val untranslatableConditionNotes = resolvedJobs.mapNotNull { job ->
            val condition = job.metadata["condition"]
            if (condition != null && TargetExpressionTranslator.tektonWhen(condition, inputs, compatibility.expressionSupport) == null) {
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

private fun List<PlanNode>.toJenkinsTargetSteps(targetName: String, projectionRules: List<TargetProjectionRule>): List<TargetStep> {
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

internal fun PlanNode.toTargetSteps(targetName: String = "notes-driven", projectionRules: List<TargetProjectionRule> = emptyList()): List<TargetStep> = when (this) {
    is TaskNode -> listOf(toTargetStep(targetName, projectionRules))
    is ConditionNode -> {
        val out = mutableListOf<TargetStep>()
        if (then.isNotEmpty()) out += TargetStep(
            id = sanitizeId("${id}_then"),
            name = "$id then",
            type = "condition",
            params = mapOf("condition" to condition),
            children = then.flatMap { it.toTargetSteps(targetName, projectionRules) },
            metadata = mapOf("sourceNodeKind" to kind, "branch" to "then")
        )
        if (otherwise.isNotEmpty()) out += TargetStep(
            id = sanitizeId("${id}_else"),
            name = "$id else",
            type = "condition",
            params = mapOf("condition" to "not ($condition)"),
            children = otherwise.flatMap { it.toTargetSteps(targetName, projectionRules) },
            metadata = mapOf("sourceNodeKind" to kind, "branch" to "else")
        )
        out
    }
    is LoopNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = "loop", params = mapOf("item" to item, "source" to source), children = body.flatMap { it.toTargetSteps(targetName, projectionRules) }, metadata = mapOf("sourceNodeKind" to kind, "supportLevel" to "partial")))
    is ParallelGroupNode -> listOf(TargetStep(
        id = sanitizeId(id), name = id, type = "parallel",
        children = branches.mapIndexed { index, branch -> TargetStep(id = sanitizeId(branch.name ?: "branch-${index + 1}"), name = branch.name ?: "branch-${index + 1}", type = "parallel-branch", children = branch.steps.flatMap { it.toTargetSteps(targetName, projectionRules) }, metadata = mapOf("sourceNodeKind" to "ParallelBranch")) },
        metadata = mapOf("sourceNodeKind" to kind, "failFast" to failFast.toString())
    ))
    is MatchPlanNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = "match", params = mapOf("source" to source), children = cases.flatMap { c -> c.steps.flatMap { it.toTargetSteps(targetName, projectionRules) } } + errorCase.flatMap { it.toTargetSteps(targetName, projectionRules) } + defaultSteps.flatMap { it.toTargetSteps(targetName, projectionRules) }, metadata = mapOf("sourceNodeKind" to kind, "supportLevel" to "partial")))
    is RetryGroupNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = "retry", params = mapOf("max" to max.toString(), "delay" to delay, "backoff" to backoff), children = body.flatMap { it.toTargetSteps(targetName, projectionRules) }, metadata = mapOf("sourceNodeKind" to kind)))
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

private fun PlanNode.toTargetJobs(out: MutableList<TargetJob>, condition: String?, targetName: String, projectionRules: List<TargetProjectionRule>) {
    when (this) {
        is TaskNode -> {
            val step = toTargetStep(targetName, projectionRules).let { if (condition != null) it.copy(metadata = it.metadata + ("condition" to condition)) else it }
            out += TargetJob(id = sanitizeId(id), name = id, dependsOn = dependsOn.map(::sanitizeId), steps = listOf(step), metadata = mapOfNotNull("condition" to condition))
        }
        is ApprovalNode -> {
            val step = toTargetSteps(targetName, projectionRules).single().let { if (condition != null) it.copy(metadata = it.metadata + ("condition" to condition)) else it }
            out += TargetJob(id = sanitizeId(id), name = id, dependsOn = dependsOn.map(::sanitizeId), steps = listOf(step), metadata = mapOfNotNull("condition" to condition, "approval" to "true"))
        }
        is ConditionNode -> {
            then.forEach { it.toTargetJobs(out, combineConditions(condition, this.condition), targetName, projectionRules) }
            otherwise.forEach { it.toTargetJobs(out, combineConditions(condition, "not (${this.condition})"), targetName, projectionRules) }
        }
        is ParallelGroupNode -> branches.flatMap { it.steps }.forEach { it.toTargetJobs(out, condition, targetName, projectionRules) }
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
        is LoopNode -> out += TargetJob(id = sanitizeId(id), name = id, steps = toTargetSteps(targetName, projectionRules), metadata = mapOfNotNull("condition" to condition, "supportLevel" to "partial"))
        is MatchPlanNode -> out += TargetJob(id = sanitizeId(id), name = id, steps = toTargetSteps(targetName, projectionRules), metadata = mapOfNotNull("condition" to condition, "supportLevel" to "partial"))
        is DataOpNode, is ControlNode -> out += TargetJob(id = sanitizeId(id), name = id, steps = toTargetSteps(targetName, projectionRules), metadata = mapOfNotNull("condition" to condition))
    }
}

private fun TaskNode.toTargetStep(targetName: String = "notes-driven", projectionRules: List<TargetProjectionRule> = emptyList()): TargetStep {
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
        metadata = (mapOf(
            "sourceTask" to id,
            "sourceNodeKind" to kind,
            "resultName" to (resultName ?: ""),
            "destructive" to destructive.toString(),
            "safety" to (safety ?: "")
        ).filterValues { it.isNotBlank() } + materializationMetadata(resolution))
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
