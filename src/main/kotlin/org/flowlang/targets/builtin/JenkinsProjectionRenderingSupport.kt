package org.flowlang.targets.builtin

import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingKind

internal object JenkinsProjectionPayloadKinds {
    const val JENKINS_STEP = "JENKINS_STEP"
    const val JENKINS_STRUCTURE = "JENKINS_STRUCTURE"
}

internal object JenkinsProjectionSyntax : ProjectionValueSyntax {
    override fun renderOpaque(name: String): String = "env.${safeEnvName(name)}"
    override fun renderInput(name: String): String = groovyProperty("params", name)
    override fun renderInputInterpolation(name: String): String = "\${${renderInput(name)}}"
    fun bindingValue(name: String): String = "credentials(${groovyString(name)})"
    fun boundary(): String = "withCredentials"
    fun mappingSpec(name: String): String =
        "string(credentialsId: ${groovyString(name)}, variable: ${groovyString(safeEnvName(name))})"
    override fun mappingNote(name: String): String =
        "value mapping requirement: ${boundary()}([${mappingSpec(name)}]) exposes env.${safeEnvName(name)}"
}

internal object JenkinsProjectionBindingRenderer {
    fun jenkinsArgument(binding: ProjectionBinding, context: String): String = when (binding.kind) {
        ProjectionBindingKind.LITERAL,
        ProjectionBindingKind.TASK_PARAMETER,
        ProjectionBindingKind.TASK_INPUT,
        ProjectionBindingKind.TASK_METADATA -> groovyString(requireNotNull(binding.value) { "$context ${binding.kind} binding is unresolved." })
        ProjectionBindingKind.FLOW_INPUT -> groovyProperty("params", requireName(binding, context))
        ProjectionBindingKind.SECRET -> "env.${safeEnvName(requireName(binding, context))}"
        ProjectionBindingKind.TARGET_EXPRESSION -> {
            require(binding.target == "jenkins") { "$context contains a target expression for '${binding.target}', not Jenkins." }
            requireNotNull(binding.expression) { "$context target expression is missing expression text." }
        }
        ProjectionBindingKind.TASK_OUTPUT -> error("$context uses TASK_OUTPUT, but Jenkins output binding requires an explicit renderer contract.")
        ProjectionBindingKind.ARTIFACT -> error("$context uses ARTIFACT, but Jenkins artifact binding requires an explicit renderer contract.")
    }

    private fun requireName(binding: ProjectionBinding, context: String): String =
        requireNotNull(binding.name) { "$context ${binding.kind} binding is missing name." }
}

/** Complete, non-interpolating Groovy string literal. Regex text is data too. */
internal fun groovyString(value: String): String = "'" + groovyEscape(value) + "'"

/** Content of a single-quoted literal. Escape backslash before interpreting quotes. */
internal fun groovyEscape(value: String): String = buildString {
    value.forEach { character ->
        append(when (character) {
            '\\' -> "\\\\"
            '\'' -> "\\'"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            '\b' -> "\\b"
            '\u000C' -> "\\f"
            else -> if (character.code < 0x20 || character == '\u007F' || character == '\u2028' || character == '\u2029') {
                "\\u" + character.code.toString(16).padStart(4, '0')
            } else character.toString()
        })
    }
}

private val GROOVY_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** Preserve unusual property names instead of sanitizing them into a different identity. */
internal fun groovyProperty(target: String, name: String, safe: Boolean = false): String =
    target + (if (safe) "?." else ".") +
        (if (GROOVY_IDENTIFIER.matches(name)) name else groovyString(name))

/** Free variables/callees cannot use the quoted-property syntax. Reject unsupported names. */
internal fun groovyIdentifier(name: String): String {
    require(GROOVY_IDENTIFIER.matches(name)) { "Unsupported Jenkins Groovy identifier '$name'." }
    return name
}
