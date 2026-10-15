package org.flowlang.targets.builtin

import org.flowlang.ast.*
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.parser.ExpressionParser
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

/** Condition secrets use typed expression nodes, never substring matching or native variable inference. */
internal object JenkinsConditionSecrets {
    fun names(manifest: TargetManifest): List<String> = manifest.jobs.flatMap { it.steps }
        .flatMap { it.flatten() }.flatMap { step ->
            if (step.type == "condition") {
                TargetProjectionDiagnostics.opaqueNames(step.copy(params = step.params - "condition")) +
                    step.params["condition"]?.let { names(ExpressionParser.parseSource(it)) }.orEmpty()
            } else TargetProjectionDiagnostics.opaqueNames(step)
        }.distinct()

    private fun names(e: ExpressionNode): List<String> = when (e) {
        is SecretRefNode -> listOf(e.name)
        is MemberExpressionNode -> names(e.target)
        is IndexExpressionNode -> names(e.target) + names(e.index)
        is TemplateStringNode -> e.parts.flatMap(::names)
        is ListLiteralNode -> e.items.flatMap(::names)
        is MapLiteralNode -> e.entries.values.flatMap(::names)
        is CallExpressionNode -> e.args.flatMap(::names)
        is UnaryExpressionNode -> names(e.operand)
        is UnaryPostfixExpressionNode -> names(e.operand)
        is LogicalExpressionNode -> e.operands.flatMap(::names)
        is BinaryExpressionNode -> names(e.left) + names(e.right)
        is StringLiteralNode, is NumberLiteralNode, is BooleanLiteralNode, is NullLiteralNode,
        is IdentifierLiteralNode, is ReferenceNode -> emptyList()
    }
}
