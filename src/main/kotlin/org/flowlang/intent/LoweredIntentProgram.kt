package org.flowlang.intent

import org.flowlang.ast.FlowDocument
import org.flowlang.ast.InputNode
import org.flowlang.ast.TriggerNode
import org.flowlang.lowering.IntentSourceMetadata
import org.flowlang.planner.MultipleWorkflowCompatibilityViewException

/** One independently lowered workflow before validation and planning. */
internal data class LoweredIntentWorkflow(
    val name: String,
    val document: FlowDocument
) {
    init {
        require(name.isNotBlank()) { "Lowered Intent workflow name must not be blank." }
    }
}

/**
 * Intent lowering product that preserves workflow boundaries and global trigger
 * routing. It is compiler input, never an executable or target projection.
 */
internal data class LoweredIntentProgram(
    val name: String,
    val inputs: List<InputNode>,
    val triggers: List<TriggerNode>,
    val sourceIntent: IntentSourceMetadata,
    val workflows: List<LoweredIntentWorkflow>
) {
    init {
        require(name.isNotBlank()) { "Lowered Intent program name must not be blank." }
        require(workflows.isNotEmpty()) { "Lowered Intent program must own at least one workflow." }
        require(workflows.map { it.name }.toSet().size == workflows.size) {
            "Lowered Intent program contains duplicate workflow names."
        }
        val names = workflows.map { it.name }.toSet()
        require(triggers.all { trigger ->
            trigger.workflows.isNotEmpty() &&
                trigger.workflows.toSet().size == trigger.workflows.size &&
                trigger.workflows.all { it in names }
        }) {
            "Lowered Intent program contains an unresolved or duplicate trigger route."
        }
    }

    fun requireSingleDocument(): FlowDocument {
        if (workflows.size != 1) {
            throw MultipleWorkflowCompatibilityViewException(
                "FlowDocument compatibility lowering",
                workflows.map { it.name }
            )
        }
        return workflows.single().document
    }
}
