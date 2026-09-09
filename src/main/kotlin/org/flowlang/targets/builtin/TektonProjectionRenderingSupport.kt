package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.AdapterManifestLowering.id as sanitizeId
import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingKind

internal object TektonProjectionPayloadKinds {
    const val TEKTON_TASK = "TEKTON_TASK"
}

internal object TektonProjectionSyntax : ProjectionValueSyntax {
    override fun renderOpaque(name: String): String = "\$(params.${safeEnvName(name)})"
    override fun renderInput(name: String): String = "\$(params.$name)"
    override fun mappingNote(name: String): String = "value mapping requirement: secretKeyRef name=flow-secrets key=$name"
    override fun structuredMappingNote(name: String): String = "valueFrom:\n  secretKeyRef:\n    name: flow-secrets\n    key: $name"
}

internal object TektonProjectionBindingRenderer {
    fun tektonValue(binding: ProjectionBinding, context: String): String = when (binding.kind) {
        ProjectionBindingKind.LITERAL,
        ProjectionBindingKind.TASK_PARAMETER,
        ProjectionBindingKind.TASK_INPUT,
        ProjectionBindingKind.TASK_METADATA -> requireNotNull(binding.value) { "$context ${binding.kind} binding is unresolved." }
        ProjectionBindingKind.FLOW_INPUT -> "\$(params.${safeIdentifier(requireName(binding, context))})"
        ProjectionBindingKind.TASK_OUTPUT ->
            "\$(tasks.${sanitizeId(requireNotNull(binding.taskId) { "$context TASK_OUTPUT is missing taskId." })}.results.${sanitizeId(requireNotNull(binding.output) { "$context TASK_OUTPUT is missing output." })})"
        ProjectionBindingKind.TARGET_EXPRESSION -> {
            require(binding.target == "tekton") { "$context contains a target expression for '${binding.target}', not Tekton." }
            requireNotNull(binding.expression) { "$context target expression is missing expression text." }
        }
        ProjectionBindingKind.SECRET -> error("$context uses SECRET, but Tekton secret binding requires explicit workspace or secretKeyRef evidence.")
        ProjectionBindingKind.ARTIFACT -> error("$context uses ARTIFACT, but Tekton artifact binding requires explicit workspace evidence.")
    }

    private fun requireName(binding: ProjectionBinding, context: String): String =
        requireNotNull(binding.name) { "$context ${binding.kind} binding is missing name." }
}
