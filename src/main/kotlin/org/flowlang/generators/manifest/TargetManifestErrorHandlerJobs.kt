package org.flowlang.generators.manifest

import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.PlanNode
import org.flowlang.planner.TaskNode

/**
 * Compatibility overload for legacy two-argument error-handler job lowering.
 *
 * The main manifest path should pass a concrete target name. This overload keeps
 * older call sites compilable and still emits auditable shell-oriented steps
 * instead of silently dropping error-handler work.
 */
internal fun PlanNode.toTargetJobs(out: MutableList<TargetJob>, condition: String?) {
    val steps = toTargetSteps("portable-shell")
    val metadata = if (condition.isNullOrBlank()) emptyMap() else mapOf("condition" to condition)
    val dependsOn = when (this) {
        is TaskNode -> dependsOn.map(::sanitizeId)
        is ApprovalNode -> dependsOn.map(::sanitizeId)
        else -> emptyList()
    }
    out += TargetJob(
        id = sanitizeId(id),
        name = id,
        dependsOn = dependsOn,
        steps = steps,
        metadata = metadata
    )
}
