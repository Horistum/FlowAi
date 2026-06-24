package org.flowlang.preview

import org.flowlang.planner.ExecutionPlan

/**
 * Design-time plan preview. This is not a runtime executor and never executes
 * commands. It exists only to make the standard execution plan easier to inspect.
 */
class PlanPreview {
    fun render(plan: ExecutionPlan): PlanPreviewReport {
        val entries = plan.tasks.map { task ->
            PlanPreviewEntry(task.id, "planned", "${task.module}.${task.action} -> ${task.target}")
        }
        return PlanPreviewReport(plan.flowName, "planning-preview", executesCommands = false, entries)
    }
}

data class PlanPreviewReport(
    val flowName: String,
    val mode: String,
    val executesCommands: Boolean,
    val entries: List<PlanPreviewEntry>
)

data class PlanPreviewEntry(
    val taskId: String,
    val status: String,
    val message: String
)
