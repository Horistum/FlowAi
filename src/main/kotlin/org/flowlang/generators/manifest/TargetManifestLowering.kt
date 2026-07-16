package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ControlNode
import org.flowlang.planner.DataOpNode
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanInput
import org.flowlang.planner.PlanNode
import org.flowlang.planner.PlanTrigger
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode
import org.flowlang.planner.ExecutionPlan
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

internal fun PlanInput.toTargetInput(): TargetInput = TargetInput(name, type, required, defaultValue, choices)

internal fun PlanTrigger.toTargetTrigger(): TargetTrigger = TargetTrigger(
    id = id,
    type = type,
    workflows = workflows,
    scheduleKind = schedule?.kind,
    scheduleExpression = schedule?.expression,
    timezone = schedule?.timezone,
    event = event,
    params = params
)

internal fun baseMetadata(plan: ExecutionPlan, generator: String): Map<String, String> = mapOf(
    "sourcePlanVersion" to plan.planVersion,
    "generator" to generator,
    "standardVersion" to FlowStandardVersions.FLOW_STANDARD_VERSION,
    "projectionModel" to "notes-driven-materialization"
)

internal fun CompatibilityReport.toMappingNotes(targetName: String): List<TargetMappingNote> = issues.map {
    TargetMappingNote(
        level = it.level.name.lowercase(),
        target = targetName,
        nodeId = it.nodeId,
        feature = it.feature,
        message = it.message
    )
}

/**
 * Target-neutral structural lowering. It preserves plan semantics and delegates
 * materialization evidence to the generic resolver without selecting a platform.
 */
internal fun PlanNode.toTargetSteps(
    targetName: String = "notes-driven",
    projectionRules: List<TargetProjectionRule> = emptyList(),
    nativeProjections: TargetNativeProjectionCatalog = TargetNativeProjectionCatalog.empty(targetName)
): List<TargetStep> = when (this) {
    is TaskNode -> listOf(toTargetStep(targetName, projectionRules, nativeProjections))
    is ConditionNode -> {
        val out = mutableListOf<TargetStep>()
        if (then.isNotEmpty()) {
            out += TargetStep(
                id = sanitizeId("${id}_then"),
                name = "$id then",
                type = "condition",
                params = mapOf("condition" to condition),
                children = then.flatMap { it.toTargetSteps(targetName, projectionRules, nativeProjections) },
                metadata = mapOf("sourceNodeKind" to kind, "branch" to "then")
            )
        }
        if (otherwise.isNotEmpty()) {
            out += TargetStep(
                id = sanitizeId("${id}_else"),
                name = "$id else",
                type = "condition",
                params = mapOf("condition" to "not ($condition)"),
                children = otherwise.flatMap { it.toTargetSteps(targetName, projectionRules, nativeProjections) },
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
        children = body.flatMap { it.toTargetSteps(targetName, projectionRules, nativeProjections) },
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
                children = branch.steps.flatMap { it.toTargetSteps(targetName, projectionRules, nativeProjections) },
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
            case.steps.flatMap { it.toTargetSteps(targetName, projectionRules, nativeProjections) }
        } + errorCase.flatMap { it.toTargetSteps(targetName, projectionRules, nativeProjections) } +
            defaultSteps.flatMap { it.toTargetSteps(targetName, projectionRules, nativeProjections) },
        metadata = mapOf("sourceNodeKind" to kind, "supportLevel" to "partial")
    ))
    is RetryGroupNode -> listOf(TargetStep(
        id = sanitizeId(id),
        name = id,
        type = "retry",
        params = mapOf("max" to max.toString(), "delay" to delay, "backoff" to backoff),
        children = body.flatMap { it.toTargetSteps(targetName, projectionRules, nativeProjections) },
        metadata = mapOf("sourceNodeKind" to kind)
    ))
    is TryPlanNode -> {
        val bodyStep = TargetStep(
            id = sanitizeId("${id}_body"),
            name = "$id body",
            type = "try-body",
            children = body.flatMap { it.toTargetSteps(targetName, projectionRules, nativeProjections) },
            metadata = mapOf("sourceNodeKind" to kind, "tryRole" to "body")
        )
        val handlerStep = TargetStep(
            id = sanitizeId("${id}_handler"),
            name = "$id error handler",
            type = "error-handler",
            children = errorHandler.flatMap { it.toTargetSteps(targetName, projectionRules, nativeProjections) },
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

internal fun TaskNode.toTargetStep(
    targetName: String = "notes-driven",
    projectionRules: List<TargetProjectionRule> = emptyList(),
    nativeProjections: TargetNativeProjectionCatalog = TargetNativeProjectionCatalog.empty(targetName)
): TargetStep {
    val resolution = TargetMaterializationResolver.resolve(this, targetName, projectionRules, nativeProjections)
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

internal fun emptyProjectionStep(flowName: String): TargetStep = TargetStep(
    id = sanitizeId("${flowName}_projection"),
    name = "$flowName projection",
    type = "projection",
    materialization = TargetMaterialization.semanticOnly("Empty flow projection has no executable target work.")
)

internal fun combineConditions(a: String?, b: String): String =
    if (a.isNullOrBlank()) b else "($a) and ($b)"

internal fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> = pairs
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
