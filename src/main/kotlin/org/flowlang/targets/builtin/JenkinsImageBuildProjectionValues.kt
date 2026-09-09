package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetInput

internal object JenkinsImageBuildProjectionValues {
    fun jenkinsImageArgument(value: String, inputs: List<TargetInput>, context: String): String {
        val rendered = ImageBuildProjectionValues.renderText(JenkinsProjectionSyntax, value, inputs, context)
        return if (rendered.contains("\${params.")) groovyInterpolatedString(rendered) else groovyString(rendered)
    }

    fun jenkinsVariable(stepId: String): String = "flowImage_" + stepId
        .replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_').ifBlank { "build" }

    private fun groovyInterpolatedString(value: String): String = "\"" + value
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
