package org.flowlang.projection

import com.fasterxml.jackson.annotation.JsonInclude

/** Target-neutral projection binding vocabulary. */
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

enum class ProjectionBindingResolutionStatus {
    RESOLVED,
    SYMBOLIC,
    UNRESOLVED
}

enum class TaskMetadataField { ID, TARGET }

/**
 * Discriminated projection binding contract.
 *
 * Registry templates omit [resolutionStatus]. Manifest generation assigns one
 * of RESOLVED, SYMBOLIC or UNRESOLVED while preserving the semantic source.
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
    val defaultValue: String? = null,
    val resolutionStatus: ProjectionBindingResolutionStatus? = null,
    val reason: String? = null
) {
    companion object {
        fun literal(value: String): ProjectionBinding = ProjectionBinding(
            kind = ProjectionBindingKind.LITERAL,
            value = value
        )

        fun taskParameter(name: String, defaultValue: String? = null): ProjectionBinding =
            ProjectionBinding(
                kind = ProjectionBindingKind.TASK_PARAMETER,
                name = name,
                defaultValue = defaultValue
            )

        fun taskInput(name: String, defaultValue: String? = null): ProjectionBinding =
            ProjectionBinding(
                kind = ProjectionBindingKind.TASK_INPUT,
                name = name,
                defaultValue = defaultValue
            )

        fun taskMetadata(field: TaskMetadataField): ProjectionBinding = ProjectionBinding(
            kind = ProjectionBindingKind.TASK_METADATA,
            field = field
        )

        fun flowInput(name: String): ProjectionBinding = ProjectionBinding(
            kind = ProjectionBindingKind.FLOW_INPUT,
            name = name
        )

        fun secret(name: String): ProjectionBinding = ProjectionBinding(
            kind = ProjectionBindingKind.SECRET,
            name = name
        )

        fun artifact(name: String): ProjectionBinding = ProjectionBinding(
            kind = ProjectionBindingKind.ARTIFACT,
            name = name
        )

        fun taskOutput(taskId: String, output: String): ProjectionBinding = ProjectionBinding(
            kind = ProjectionBindingKind.TASK_OUTPUT,
            taskId = taskId,
            output = output
        )

        fun targetExpression(target: String, expression: String): ProjectionBinding = ProjectionBinding(
            kind = ProjectionBindingKind.TARGET_EXPRESSION,
            target = target,
            expression = expression
        )
    }
}

object ProjectionBindingContract {
    fun requireTemplate(binding: ProjectionBinding, context: String) {
        validationReason(binding, manifest = false, payloadTarget = null)?.let { reason ->
            throw IllegalArgumentException("Invalid projection binding at '$context': $reason")
        }
    }

    fun requireManifest(binding: ProjectionBinding, payloadTarget: String, context: String) {
        validationReason(binding, manifest = true, payloadTarget = payloadTarget)?.let { reason ->
            throw IllegalArgumentException("Invalid manifest projection binding at '$context': $reason")
        }
    }

    fun validationReason(
        binding: ProjectionBinding,
        manifest: Boolean,
        payloadTarget: String?
    ): String? {
        fun requireName(): String? = when {
            binding.name == null -> "name is required for ${binding.kind}."
            binding.name.isBlank() -> "name must not be blank for ${binding.kind}."
            else -> null
        }

        val shapeReason = when (binding.kind) {
            ProjectionBindingKind.LITERAL -> if (binding.value == null) "value is required for LITERAL." else null
            ProjectionBindingKind.TASK_PARAMETER,
            ProjectionBindingKind.TASK_INPUT -> requireName()
            ProjectionBindingKind.TASK_METADATA -> if (binding.field == null) "field is required for TASK_METADATA." else null
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
        if (shapeReason != null) return shapeReason

        if (!manifest) {
            if (binding.resolutionStatus != null || binding.reason != null) {
                return "Registry templates must not declare resolution status or resolution reason."
            }
            if (binding.kind in compileTimeKinds && binding.kind != ProjectionBindingKind.LITERAL && binding.value != null) {
                return "Registry templates must not pre-resolve ${binding.kind} values."
            }
        } else {
            val statusReason = manifestStatusReason(binding)
            if (statusReason != null) return statusReason
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
            if (binding.resolutionStatus != null) add("resolutionStatus")
            if (binding.reason != null) add("reason")
        }
        val stateFields = if (manifest) setOf("resolutionStatus", "reason") else emptySet()
        val allowed = when (binding.kind) {
            ProjectionBindingKind.LITERAL -> setOf("value") + stateFields
            ProjectionBindingKind.TASK_PARAMETER,
            ProjectionBindingKind.TASK_INPUT -> setOf("name", "value", "defaultValue") + stateFields
            ProjectionBindingKind.TASK_METADATA -> setOf("field", "value") + stateFields
            ProjectionBindingKind.FLOW_INPUT,
            ProjectionBindingKind.SECRET,
            ProjectionBindingKind.ARTIFACT -> setOf("name") + stateFields
            ProjectionBindingKind.TASK_OUTPUT -> setOf("taskId", "output") + stateFields
            ProjectionBindingKind.TARGET_EXPRESSION -> setOf("target", "expression") + stateFields
        }
        val unexpected = populated - allowed
        return unexpected.takeIf { it.isNotEmpty() }?.let {
            "Fields ${it.sorted().joinToString()} are not valid for ${binding.kind}."
        }
    }

    private fun manifestStatusReason(binding: ProjectionBinding): String? {
        val status = binding.resolutionStatus
            ?: return "resolutionStatus is required in Target Manifest."
        return when (binding.kind) {
            ProjectionBindingKind.LITERAL -> when {
                status != ProjectionBindingResolutionStatus.RESOLVED -> "LITERAL must be RESOLVED."
                binding.value == null -> "RESOLVED LITERAL requires value."
                !binding.reason.isNullOrBlank() -> "RESOLVED LITERAL must not declare reason."
                else -> null
            }
            ProjectionBindingKind.TASK_PARAMETER,
            ProjectionBindingKind.TASK_INPUT,
            ProjectionBindingKind.TASK_METADATA -> when (status) {
                ProjectionBindingResolutionStatus.RESOLVED -> when {
                    binding.value == null -> "RESOLVED ${binding.kind} requires value."
                    !binding.reason.isNullOrBlank() -> "RESOLVED ${binding.kind} must not declare reason."
                    else -> null
                }
                ProjectionBindingResolutionStatus.UNRESOLVED -> when {
                    binding.value != null -> "UNRESOLVED ${binding.kind} must not declare value."
                    binding.reason.isNullOrBlank() -> "UNRESOLVED ${binding.kind} requires reason."
                    else -> null
                }
                ProjectionBindingResolutionStatus.SYMBOLIC -> "${binding.kind} cannot be SYMBOLIC."
            }
            ProjectionBindingKind.FLOW_INPUT,
            ProjectionBindingKind.SECRET,
            ProjectionBindingKind.ARTIFACT,
            ProjectionBindingKind.TASK_OUTPUT,
            ProjectionBindingKind.TARGET_EXPRESSION -> when {
                status != ProjectionBindingResolutionStatus.SYMBOLIC -> "${binding.kind} must be SYMBOLIC."
                binding.value != null -> "SYMBOLIC ${binding.kind} must not declare value."
                !binding.reason.isNullOrBlank() -> "SYMBOLIC ${binding.kind} must not declare reason."
                else -> null
            }
        }
    }

    private val compileTimeKinds = setOf(
        ProjectionBindingKind.LITERAL,
        ProjectionBindingKind.TASK_PARAMETER,
        ProjectionBindingKind.TASK_INPUT,
        ProjectionBindingKind.TASK_METADATA
    )
}
