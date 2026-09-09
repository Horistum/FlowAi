package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetInput
import org.flowlang.generators.manifest.TargetRendererPayload
import org.flowlang.generators.manifest.sanitizeId
import org.flowlang.generators.manifest.unquote
import org.flowlang.projection.ProjectionBindingResolutionStatus

/** Value validation shared by image-build projections, without concrete target syntax. */
object ImageBuildProjectionValues {
    fun image(payload: TargetRendererPayload, name: String, context: String): String =
        requiredText(payload, name, context).also { value ->
            require(value.isNotBlank()) { "$context binding '$name' must not be blank." }
            require(value.none { it.isWhitespace() || it.isISOControl() }) {
                "$context binding '$name' must not contain whitespace or control characters."
            }
        }

    fun buildContext(payload: TargetRendererPayload, name: String, context: String): String =
        optionalText(payload, name, context) ?: "."

    fun dockerfile(payload: TargetRendererPayload, name: String, context: String): String? =
        optionalText(payload, name, context)

    fun push(payload: TargetRendererPayload, name: String, context: String): Boolean {
        val raw = optionalText(payload, name, context) ?: "false"
        return raw.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("$context binding '$name' must be a compile-time boolean, but was '$raw'.")
    }

    fun workspace(payload: TargetRendererPayload, name: String, context: String): String {
        val raw = requiredText(payload, name, context)
        require(raw.isNotBlank()) { "$context binding '$name' must not be blank." }
        return sanitizeId(raw)
    }

    fun renderText(
        syntax: ProjectionValueSyntax,
        value: String,
        inputs: List<TargetInput>,
        context: String
    ): String {
        val inputNames = inputs.map { it.name }.toSet()
        requireKnownInputInterpolations(value, inputNames, context)
        return TargetProjectionValue.render(syntax, value, inputNames)
    }

    fun requireLiteralWorkspacePath(value: String, context: String): String {
        require(!value.contains("\${")) { "$context must be a compile-time relative workspace path." }
        require(value.isNotBlank()) { "$context must not be blank." }
        require(!value.startsWith("/") && !WINDOWS_ABSOLUTE.matches(value)) {
            "$context must be relative to the target workspace."
        }
        require(!value.startsWith("-") && value.none { it.isWhitespace() || it.isISOControl() }) {
            "$context must not contain option-like, whitespace or control characters."
        }
        require(PATH.matches(value)) { "$context contains unsupported path characters." }
        val normalized = value.removePrefix("./").trimEnd('/').ifBlank { "." }
        require(normalized.split('/').none { it == ".." }) { "$context must not escape the target workspace." }
        return normalized
    }

    fun defaultDockerfile(contextPath: String): String =
        if (contextPath == ".") "Dockerfile" else "$contextPath/Dockerfile"

    fun isDefaultDockerfile(contextPath: String, dockerfile: String?): Boolean {
        if (dockerfile == null) return true
        return dockerfile.removePrefix("./") == defaultDockerfile(contextPath).removePrefix("./")
    }

    private fun requiredText(payload: TargetRendererPayload, name: String, context: String): String =
        optionalText(payload, name, context)
            ?: throw IllegalArgumentException("$context binding '$name' is unresolved.")

    private fun optionalText(payload: TargetRendererPayload, name: String, context: String): String? {
        val binding = payload.bindings[name] ?: return null
        if (binding.resolutionStatus == ProjectionBindingResolutionStatus.UNRESOLVED) return null
        return unquote(requireNotNull(binding.value) { "$context binding '$name' is unresolved." })
    }

    private fun requireKnownInputInterpolations(value: String, inputNames: Set<String>, context: String) {
        INPUT_INTERPOLATION.findAll(value).forEach { match ->
            val expression = match.groupValues[1].trim()
            require(expression in inputNames) { "$context contains unsupported interpolation '$expression'." }
        }
        val withoutKnownInterpolations = INPUT_INTERPOLATION.replace(value, "")
        require('$' !in withoutKnownInterpolations) { "$context contains unsupported dollar interpolation." }
    }

    private val INPUT_INTERPOLATION = Regex("""\$\{([^}]+)}""")
    private val PATH = Regex("^[A-Za-z0-9._/-]+$")
    private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[/\\\\].*")
}
