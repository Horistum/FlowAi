package org.flowlang.targets.builtin

import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingKind

internal object JenkinsProjectionPayloadKinds {
    const val JENKINS_STEP = "JENKINS_STEP"
    const val JENKINS_STRUCTURE = "JENKINS_STRUCTURE"
}

internal object JenkinsProjectionSyntax : ProjectionValueSyntax {
    override fun renderOpaque(name: String): String = "env.${safeEnvName(name)}"
    override fun renderInput(name: String): String = "params.$name"
    override fun renderInputInterpolation(name: String): String = "\${params.$name}"
    fun bindingValue(name: String): String = "credentials('${groovyEscape(name)}')"
    fun boundary(): String = "withCredentials"
    fun mappingSpec(name: String): String =
        "string(credentialsId: '${groovyEscape(name)}', variable: '${safeEnvName(name)}')"
    override fun mappingNote(name: String): String =
        "value mapping requirement: ${boundary()}([${mappingSpec(name)}]) exposes env.${safeEnvName(name)}"
}

internal object JenkinsProjectionBindingRenderer {
    fun jenkinsArgument(binding: ProjectionBinding, context: String): String = when (binding.kind) {
        ProjectionBindingKind.LITERAL,
        ProjectionBindingKind.TASK_PARAMETER,
        ProjectionBindingKind.TASK_INPUT,
        ProjectionBindingKind.TASK_METADATA -> groovyString(requireNotNull(binding.value) { "$context ${binding.kind} binding is unresolved." })
        ProjectionBindingKind.FLOW_INPUT -> "params.${safeIdentifier(requireName(binding, context))}"
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

internal fun groovyString(value: String): String = "'" + value.replace("\\", "\\\\")
    .replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'"
internal fun groovyEscape(value: String): String = value.replace("'", "\\'")
