package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.AdapterManifestLowering
import org.flowlang.generators.manifest.TargetProjectionIdentityChecks
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.projection.ProjectionBindingKind

/** Concrete adapters supply syntax; shared traversal never selects a platform. */
interface ProjectionValueSyntax {
    fun renderOpaque(name: String): String
    fun renderInput(name: String): String
    fun renderInputInterpolation(name: String): String = renderInput(name)
    fun mappingNote(name: String): String?
    fun structuredMappingNote(name: String): String? = null
}

object TargetProjectionDiagnostics {
    fun append(
        syntax: ProjectionValueSyntax,
        step: TargetStep,
        inputs: List<TargetInput>,
        sb: StringBuilder,
        indent: String,
        prefix: String
    ) {
        val inputNames = inputs.map { it.name }.toSet()
        step.params.forEach { (key, value) ->
            sb.appendLine("$indent$prefix Flow param $key: ${TargetProjectionValue.render(syntax, value, inputNames).replace("\n", " ")}")
            legacyOpaqueNames(value).forEach { name ->
                sb.appendLine("$indent$prefix projection requirement: opaque value $name for param $key as ${syntax.renderOpaque(name)}")
                sb.appendLine("$indent$prefix runtime environment reference for param $key: \"${TargetProjectionValue.runtimeEnvName(name)}\"")
                syntax.mappingNote(name)?.let { note -> sb.appendLine("$indent$prefix $note") }
                syntax.structuredMappingNote(name)?.lines()?.forEach { line ->
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
        .map { it.groupValues[1] }.distinct().toList()
}

object TargetProjectionValue {
    fun render(syntax: ProjectionValueSyntax, value: String, inputNames: Set<String>): String {
        val opaqueRendered = opaqueRefRegex.replace(value) { match -> syntax.renderOpaque(match.groupValues[1]) }
        if (opaqueRendered in inputNames) return syntax.renderInput(opaqueRendered)
        val regex = Regex(Regex.escape("\${") + "([^}]+)}")
        return regex.replace(opaqueRendered) { match ->
            val expression = match.groupValues[1].trim()
            if (expression in inputNames) syntax.renderInputInterpolation(expression) else match.value
        }
    }

    fun runtimeEnvName(name: String): String = "$" + safeEnvName(name)
}

private val opaqueRefRegex = Regex("secret:([A-Za-z0-9_.-]+)")
fun safeEnvName(value: String): String = "FLOW_SECRET_" + value.uppercase()
    .replace(Regex("[^A-Z0-9_]+"), "_").trim('_').ifBlank { "OPAQUE" }
fun safeIdentifier(value: String): String = value.replace(Regex("[^A-Za-z0-9_-]+"), "_")
    .trim('_').ifBlank { "flow" }
fun yamlScalar(value: String): String = "\"" + value.replace("\\", "\\\\")
    .replace("\"", "\\\"").replace("\n", "\\n") + "\""
fun TargetStep.flatten(): List<TargetStep> = listOf(this) + children.flatMap { it.flatten() }

/** Shared checks accept the concrete adapter's name projections rather than selecting a target by id. */
object TargetRenderingIdentityChecks {
    fun requireValid(manifest: TargetManifest, inputName: (String) -> String, payloadName: (String) -> String) {
        val checks = TargetProjectionIdentityChecks
        checks.requireNames("render.jobs", manifest.jobs.map { it.id }, AdapterManifestLowering::id)
        checks.requireNames("render.inputs", manifest.inputs.map { it.name }, inputName)
        checks.requireNames("render.secret-environment", TargetProjectionDiagnostics.opaqueNames(manifest), ::safeEnvName)
        manifest.jobs.forEach { job ->
            val steps = job.steps.flatMap { it.flatten() }
            checks.requireNames("render.jobs.${job.id}.steps", steps.map { it.id }, AdapterManifestLowering::id)
            steps.forEach { step ->
                checks.requireNames("render.steps.${step.id}.bindings", step.rendererPayload?.bindings.orEmpty().keys.toList(), payloadName)
            }
        }
    }
}
