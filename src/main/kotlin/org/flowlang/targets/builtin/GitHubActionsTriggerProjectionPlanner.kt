package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetTrigger
import org.flowlang.generators.manifest.AdapterManifestLowering.id as sanitizeId

/**
 * Plans and renders the GitHub Actions `on` mapping independently from whole
 * workflow executability.
 *
 * A bounded trigger leaf can therefore be verified without promoting a
 * partially compatible workflow to executable status. The complete manifest
 * renderer consumes this same production planner after the ordinary readiness
 * gate has authorized executable rendering.
 */
internal object GitHubActionsTriggerProjectionPlanner {
    fun render(manifest: TargetManifest): String = buildString {
        val projection = plan(manifest)
        appendLine("on:")
        if (projection.manual) {
            appendLine("  workflow_dispatch:")
            renderInputs(manifest)
        }
        if (projection.schedules.isNotEmpty()) {
            appendLine("  schedule:")
            projection.schedules.forEach { trigger ->
                appendLine("    - cron: ${yamlScalar(requireNotNull(trigger.scheduleExpression))}")
                trigger.timezone?.let { timezone ->
                    appendLine("      timezone: ${yamlScalar(timezone)}")
                }
            }
        }
        projection.events.forEach { event -> appendLine("  $event:") }
    }

    fun plan(manifest: TargetManifest): GitHubActionsTriggerProjection {
        manifest.triggers.forEach(::requireSupportedTrigger)
        val events = manifest.triggers
            .filter { trigger -> trigger.type == "EVENT" }
            .map { trigger -> requireNotNull(trigger.event) }
        require(events.size == events.toSet().size) {
            "GitHub manifest contains duplicate event identities: " +
                events.groupingBy { it }.eachCount().filterValues { count -> count > 1 }.keys.sorted().joinToString()
        }
        return GitHubActionsTriggerProjection(
            manual = manifest.triggers.isEmpty() || manifest.triggers.any { trigger -> trigger.type == "MANUAL" },
            schedules = manifest.triggers.filter { trigger -> trigger.type == "SCHEDULE" },
            events = events.sorted()
        )
    }

    private fun requireSupportedTrigger(trigger: TargetTrigger) {
        require(trigger.workflows == listOf("main")) {
            "GitHub trigger '${trigger.id}' must target exactly the main workflow."
        }
        require(trigger.params.isEmpty()) {
            "GitHub trigger '${trigger.id}' contains unsupported untyped parameters: " +
                trigger.params.keys.sorted().joinToString()
        }
        when (trigger.type) {
            "MANUAL" -> require(trigger.scheduleKind == null && trigger.event == null) {
                "GitHub manual trigger '${trigger.id}' contains schedule or event evidence."
            }
            "SCHEDULE" -> {
                require(trigger.scheduleKind == "CRON") {
                    "GitHub schedule '${trigger.id}' must be CRON."
                }
                require(!trigger.scheduleExpression.isNullOrBlank()) {
                    "GitHub schedule '${trigger.id}' is missing expression."
                }
            }
            "EVENT" -> require(trigger.event in SUPPORTED_EVENTS) {
                "GitHub event '${trigger.id}' uses unsupported event identity '${trigger.event}'."
            }
            else -> error(
                "GitHub Actions cannot project trigger '${trigger.id}' of type '${trigger.type}' without changing its semantics."
            )
        }
    }

    private fun StringBuilder.renderInputs(manifest: TargetManifest) {
        if (manifest.inputs.isEmpty()) return
        appendLine("    inputs:")
        manifest.inputs.forEach { input ->
            appendLine("      ${sanitizeId(input.name)}:")
            appendLine("        description: ${yamlScalar(input.name)}")
            appendLine("        required: ${input.required}")
            val type = when (input.type) {
                "boolean" -> "boolean"
                "option" -> "choice"
                else -> "string"
            }
            appendLine("        type: $type")
            if (input.choices.isNotEmpty()) {
                appendLine("        options:")
                input.choices.forEach { choice -> appendLine("          - ${yamlScalar(choice)}") }
            }
            input.defaultValue?.let { value -> appendLine("        default: ${yamlScalar(value)}") }
        }
    }

    private val SUPPORTED_EVENTS: Set<String> = setOf("push", "pull_request", "release")
}

internal data class GitHubActionsTriggerProjection(
    val manual: Boolean,
    val schedules: List<TargetTrigger>,
    val events: List<String>
)
