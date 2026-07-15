package org.flowlang.generators.manifest

import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingKind

internal enum class ProjectionTarget { JENKINS, GITHUB_ACTIONS, TEKTON }

/** Target syntax is owned here, after Core binding validation. */
internal object ProjectionBindingRenderer {
    fun jenkinsArgument(binding: ProjectionBinding, context: String): String = when (binding.kind) {
        ProjectionBindingKind.LITERAL,
        ProjectionBindingKind.TASK_PARAMETER,
        ProjectionBindingKind.TASK_INPUT,
        ProjectionBindingKind.TASK_METADATA -> groovyString(requireResolvedValue(binding, context))
        ProjectionBindingKind.FLOW_INPUT -> "params.${safeIdentifier(requireName(binding, context))}"
        ProjectionBindingKind.SECRET -> "env.${safeEnvName(requireName(binding, context))}"
        ProjectionBindingKind.TARGET_EXPRESSION -> {
            require(binding.target == "jenkins") {
                "$context contains a target expression for '${binding.target}', not Jenkins."
            }
            requireNotNull(binding.expression) { "$context target expression is missing expression text." }
        }
        ProjectionBindingKind.TASK_OUTPUT -> error(
            "$context uses TASK_OUTPUT, but Jenkins output binding requires an explicit renderer contract."
        )
        ProjectionBindingKind.ARTIFACT -> error(
            "$context uses ARTIFACT, but Jenkins artifact binding requires an explicit renderer contract."
        )
    }

    fun githubValue(binding: ProjectionBinding, context: String): String = when (binding.kind) {
        ProjectionBindingKind.LITERAL,
        ProjectionBindingKind.TASK_PARAMETER,
        ProjectionBindingKind.TASK_INPUT,
        ProjectionBindingKind.TASK_METADATA -> requireResolvedValue(binding, context)
        ProjectionBindingKind.FLOW_INPUT -> githubExpression("inputs.${safeIdentifier(requireName(binding, context))}")
        ProjectionBindingKind.SECRET -> githubExpression("secrets.${requireName(binding, context)}")
        ProjectionBindingKind.TASK_OUTPUT -> githubExpression(
            "needs.${sanitizeId(requireNotNull(binding.taskId) { "$context TASK_OUTPUT is missing taskId." })}.outputs.${sanitizeId(requireNotNull(binding.output) { "$context TASK_OUTPUT is missing output." })}"
        )
        ProjectionBindingKind.TARGET_EXPRESSION -> {
            require(binding.target == "github-actions") {
                "$context contains a target expression for '${binding.target}', not GitHub Actions."
            }
            requireNotNull(binding.expression) { "$context target expression is missing expression text." }
        }
        ProjectionBindingKind.ARTIFACT -> error(
            "$context uses ARTIFACT, but GitHub Actions artifact binding requires an explicit action contract."
        )
    }

    fun tektonValue(binding: ProjectionBinding, context: String): String = when (binding.kind) {
        ProjectionBindingKind.LITERAL,
        ProjectionBindingKind.TASK_PARAMETER,
        ProjectionBindingKind.TASK_INPUT,
        ProjectionBindingKind.TASK_METADATA -> requireResolvedValue(binding, context)
        ProjectionBindingKind.FLOW_INPUT -> "\$(params.${safeIdentifier(requireName(binding, context))})"
        ProjectionBindingKind.TASK_OUTPUT ->
            "\$(tasks.${sanitizeId(requireNotNull(binding.taskId) { "$context TASK_OUTPUT is missing taskId." })}.results.${sanitizeId(requireNotNull(binding.output) { "$context TASK_OUTPUT is missing output." })})"
        ProjectionBindingKind.TARGET_EXPRESSION -> {
            require(binding.target == "tekton") {
                "$context contains a target expression for '${binding.target}', not Tekton."
            }
            requireNotNull(binding.expression) { "$context target expression is missing expression text." }
        }
        ProjectionBindingKind.SECRET -> error(
            "$context uses SECRET, but Tekton secret binding requires explicit workspace or secretKeyRef evidence."
        )
        ProjectionBindingKind.ARTIFACT -> error(
            "$context uses ARTIFACT, but Tekton artifact binding requires explicit workspace evidence."
        )
    }

    private fun requireResolvedValue(binding: ProjectionBinding, context: String): String =
        requireNotNull(binding.value) { "$context ${binding.kind} binding is unresolved." }

    private fun requireName(binding: ProjectionBinding, context: String): String =
        requireNotNull(binding.name) { "$context ${binding.kind} binding is missing name." }
}

internal object TargetProjectionDiagnostics {
    fun append(
        target: ProjectionTarget,
        step: TargetStep,
        inputs: List<TargetInput>,
        sb: StringBuilder,
        indent: String,
        prefix: String
    ) {
        val inputNames = inputs.map { it.name }.toSet()
        step.params.forEach { (key, value) ->
            sb.appendLine("$indent$prefix Flow param $key: ${TargetProjectionValue.render(target, value, inputNames).replace("\n", " ")}")
            legacyOpaqueNames(value).forEach { name ->
                sb.appendLine("$indent$prefix projection requirement: opaque value $name for param $key as ${TargetProjectionValue.renderOpaque(target, name)}")
                sb.appendLine("$indent$prefix runtime environment reference for param $key: \"${TargetProjectionValue.runtimeEnvName(name)}\"")
                TargetProjectionValue.mappingNote(target, name)?.let { note -> sb.appendLine("$indent$prefix $note") }
                TargetProjectionValue.structuredMappingNote(target, name)?.lines()?.forEach { line ->
                    sb.appendLine("$indent$prefix $line")
                }
            }
        }
        step.rendererPayload?.bindings.orEmpty().forEach { (name, binding) ->
            sb.appendLine("$indent$prefix Flow binding $name: ${binding.kind}")
        }
    }

    fun opaqueNames(manifest: TargetManifest): List<String> = manifest.jobs
        .flatMap { job -> job.steps.flatMap { it.flatten() } }
        .flatMap { opaqueNames(it) }
        .distinct()

    fun opaqueNames(step: TargetStep): List<String> {
        val legacy = step.params.values.flatMap(::legacyOpaqueNames)
        val typed = step.rendererPayload?.bindings.orEmpty().values
            .filter { it.kind == ProjectionBindingKind.SECRET }
            .mapNotNull { it.name }
        return (legacy + typed).distinct()
    }

    private fun legacyOpaqueNames(value: String): List<String> = opaqueRefRegex.findAll(value)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
}

internal object TargetProjectionValue {
    fun render(target: ProjectionTarget, value: String, inputNames: Set<String>): String {
        val opaqueRendered = opaqueRefRegex.replace(value) { match -> renderOpaque(target, match.groupValues[1]) }
        return renderInputs(target, opaqueRendered, inputNames)
    }

    fun renderOpaque(target: ProjectionTarget, name: String): String = when (target) {
        ProjectionTarget.JENKINS -> "env.${safeEnvName(name)}"
        ProjectionTarget.GITHUB_ACTIONS -> githubExpression("env.${safeEnvName(name)}")
        ProjectionTarget.TEKTON -> "\$(params.${safeEnvName(name)})"
    }

    fun runtimeEnvName(name: String): String = "$" + safeEnvName(name)

    fun bindingValue(target: ProjectionTarget, name: String): String = when (target) {
        ProjectionTarget.JENKINS -> "credentials('${groovyEscape(name)}')"
        ProjectionTarget.GITHUB_ACTIONS -> githubExpression("secrets.$name")
        ProjectionTarget.TEKTON -> "flow-secrets/$name"
    }

    fun boundary(target: ProjectionTarget): String = when (target) {
        ProjectionTarget.JENKINS -> "withCredentials"
        else -> "projectionBoundary"
    }

    fun mappingSpec(target: ProjectionTarget, name: String): String = when (target) {
        ProjectionTarget.JENKINS -> "string(credentialsId: '${groovyEscape(name)}', variable: '${safeEnvName(name)}')"
        ProjectionTarget.GITHUB_ACTIONS -> safeEnvName(name)
        ProjectionTarget.TEKTON -> safeEnvName(name)
    }

    fun mappingNote(target: ProjectionTarget, name: String): String? = when (target) {
        ProjectionTarget.JENKINS -> "value mapping requirement: ${boundary(target)}([${mappingSpec(target, name)}]) exposes env.${safeEnvName(name)}"
        ProjectionTarget.GITHUB_ACTIONS -> "value mapping requirement: repository opaque value $name must be available as ${renderOpaque(target, name)}"
        ProjectionTarget.TEKTON -> "value mapping requirement: secretKeyRef name=flow-secrets key=$name"
    }

    fun structuredMappingNote(target: ProjectionTarget, name: String): String? = when (target) {
        ProjectionTarget.TEKTON -> "valueFrom:\n  secretKeyRef:\n    name: flow-secrets\n    key: $name"
        else -> null
    }

    private fun renderInputs(target: ProjectionTarget, value: String, inputNames: Set<String>): String {
        if (value in inputNames) return renderInput(target, value)
        val interpolationStart = "\${"
        val regex = Regex(Regex.escape(interpolationStart) + "([^}]+)}")
        return regex.replace(value) { match ->
            val expression = match.groupValues[1].trim()
            if (expression in inputNames) renderInputInterpolation(target, expression) else match.value
        }
    }

    private fun renderInput(target: ProjectionTarget, name: String): String = when (target) {
        ProjectionTarget.JENKINS -> "params.$name"
        ProjectionTarget.GITHUB_ACTIONS -> githubExpression("inputs.$name")
        ProjectionTarget.TEKTON -> "\$(params.$name)"
    }

    private fun renderInputInterpolation(target: ProjectionTarget, name: String): String = when (target) {
        ProjectionTarget.JENKINS -> "\${params.$name}"
        ProjectionTarget.GITHUB_ACTIONS -> githubExpression("inputs.$name")
        ProjectionTarget.TEKTON -> "\$(params.$name)"
    }
}

internal val opaqueRefRegex = Regex("secret:([A-Za-z0-9_.-]+)")
internal fun safeEnvName(value: String): String = "FLOW_SECRET_" + value.uppercase()
    .replace(Regex("[^A-Z0-9_]+"), "_")
    .trim('_')
    .ifBlank { "OPAQUE" }
internal fun safeIdentifier(value: String): String = value.replace(Regex("[^A-Za-z0-9_-]+"), "_")
    .trim('_')
    .ifBlank { "flow" }
internal fun yamlScalar(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
internal fun groovyString(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'"
internal fun groovyEscape(value: String): String = value.replace("'", "\\'")
internal fun githubExpression(value: String): String = "\${{ $value }}"
internal fun TargetStep.flatten(): List<TargetStep> = listOf(this) + children.flatMap { it.flatten() }
