package org.flowlang.projection

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Target-neutral binding kinds carried from target registry projection evidence
 * into Target Manifest. The Core validates structure and provenance, while edge
 * renderers own target syntax.
 */
enum class ProjectionBindingKind {
    LITERAL,
    TASK_PARAMETER,
    TASK_INPUT,
    TASK_METADATA,
    FLOW_INPUT,
    SECRET,
    ARTIFACT,
    TASK_OUTPUT,
    TARGET_EXPRESSION
}

enum class TaskMetadataField { ID, TARGET }

/**
 * Discriminated projection binding contract.
 *
 * Registry templates use unresolved TASK_PARAMETER, TASK_INPUT and TASK_METADATA
 * bindings. Manifest generation resolves those bindings and preserves their
 * semantic origin by retaining the binding kind and filling [value]. Runtime
 * references such as FLOW_INPUT, SECRET and TASK_OUTPUT remain symbolic.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProjectionBinding(
    val kind: ProjectionBindingKind,
    val value: String? = null,
    val name: String? = null,
    val taskId: String? = null,
    val output: String? = null,
    val field: TaskMetadataField? = null,
    val target: String? = null,
    val expression: String? = null,
    val defaultValue: String? = null
) {
    companion object {
        fun literal(value: String): ProjectionBinding = ProjectionBinding(ProjectionBindingKind.LITERAL, value = value)

        fun taskParameter(name: String, defaultValue: String? = null): ProjectionBinding =
            ProjectionBinding(ProjectionBindingKind.TASK_PARAMETER, name = name, defaultValue = defaultValue)

        fun taskInput(name: String, defaultValue: String? = null): ProjectionBinding =
            ProjectionBinding(ProjectionBindingKind.TASK_INPUT, name = name, defaultValue = defaultValue)

        fun taskMetadata(field: TaskMetadataField): ProjectionBinding =
            ProjectionBinding(ProjectionBindingKind.TASK_METADATA, field = field)

        fun flowInput(name: String): ProjectionBinding = ProjectionBinding(ProjectionBindingKind.FLOW_INPUT, name = name)

        fun secret(name: String): ProjectionBinding = ProjectionBinding(ProjectionBindingKind.SECRET, name = name)

        fun artifact(name: String): ProjectionBinding = ProjectionBinding(ProjectionBindingKind.ARTIFACT, name = name)

        fun taskOutput(taskId: String, output: String): ProjectionBinding =
            ProjectionBinding(ProjectionBindingKind.TASK_OUTPUT, taskId = taskId, output = output)

        fun targetExpression(target: String, expression: String): ProjectionBinding =
            ProjectionBinding(ProjectionBindingKind.TARGET_EXPRESSION, target = target, expression = expression)
    }
}

object ProjectionBindingContract {
    fun requireTemplate(binding: ProjectionBinding, context: String) {
        validationReason(binding, resolved = false, payloadTarget = null)?.let { reason ->
            throw IllegalArgumentException("Invalid projection binding at '$context': $reason")
        }
    }

    fun requireResolved(binding: ProjectionBinding, payloadTarget: String, context: String) {
        validationReason(binding, resolved = true, payloadTarget = payloadTarget)?.let { reason ->
            throw IllegalArgumentException("Invalid resolved projection binding at '$context': $reason")
        }
    }

    fun validationReason(
        binding: ProjectionBinding,
        resolved: Boolean,
        payloadTarget: String?
    ): String? {
        fun requireName(label: String = "name"): String? = when {
            binding.name == null -> "$label is required for ${binding.kind}."
            binding.name.isBlank() -> "$label must not be blank for ${binding.kind}."
            else -> null
        }

        val requiredReason = when (binding.kind) {
            ProjectionBindingKind.LITERAL -> if (binding.value == null) "value is required for LITERAL." else null
            ProjectionBindingKind.TASK_PARAMETER,
            ProjectionBindingKind.TASK_INPUT -> requireName()
                ?: if (resolved && binding.value == null) "resolved value is required for ${binding.kind}." else null
            ProjectionBindingKind.TASK_METADATA -> when {
                binding.field == null -> "field is required for TASK_METADATA."
                resolved && binding.value == null -> "resolved value is required for TASK_METADATA."
                else -> null
            }
            ProjectionBindingKind.FLOW_INPUT,
            ProjectionBindingKind.SECRET,
            ProjectionBindingKind.ARTIFACT -> requireName()
            ProjectionBindingKind.TASK_OUTPUT -> when {
                binding.taskId.isNullOrBlank() -> "taskId is required for TASK_OUTPUT."
                binding.output.isNullOrBlank() -> "output is required for TASK_OUTPUT."
                else -> null
            }
            ProjectionBindingKind.TARGET_EXPRESSION -> when {
                binding.target.isNullOrBlank() -> "target is required for TARGET_EXPRESSION."
                binding.expression.isNullOrBlank() -> "expression is required for TARGET_EXPRESSION."
                payloadTarget != null && binding.target != payloadTarget ->
                    "TARGET_EXPRESSION is bound to '${binding.target}', not payload target '$payloadTarget'."
                else -> null
            }
        }
        if (requiredReason != null) return requiredReason

        if (!resolved && binding.kind in setOf(
                ProjectionBindingKind.TASK_PARAMETER,
                ProjectionBindingKind.TASK_INPUT,
                ProjectionBindingKind.TASK_METADATA
            ) && binding.value != null
        ) {
            return "Registry templates must not pre-resolve ${binding.kind} values."
        }

        val populated = buildSet {
            if (binding.value != null) add("value")
            if (binding.name != null) add("name")
            if (binding.taskId != null) add("taskId")
            if (binding.output != null) add("output")
            if (binding.field != null) add("field")
            if (binding.target != null) add("target")
            if (binding.expression != null) add("expression")
            if (binding.defaultValue != null) add("defaultValue")
        }
        val allowed = when (binding.kind) {
            ProjectionBindingKind.LITERAL -> setOf("value")
            ProjectionBindingKind.TASK_PARAMETER,
            ProjectionBindingKind.TASK_INPUT -> setOf("name", "value", "defaultValue")
            ProjectionBindingKind.TASK_METADATA -> setOf("field", "value")
            ProjectionBindingKind.FLOW_INPUT,
            ProjectionBindingKind.SECRET,
            ProjectionBindingKind.ARTIFACT -> setOf("name")
            ProjectionBindingKind.TASK_OUTPUT -> setOf("taskId", "output")
            ProjectionBindingKind.TARGET_EXPRESSION -> setOf("target", "expression")
        }
        val unexpected = populated - allowed
        return unexpected.takeIf { it.isNotEmpty() }?.let {
            "Fields ${it.sorted().joinToString()} are not valid for ${binding.kind}."
        }
    }
}
