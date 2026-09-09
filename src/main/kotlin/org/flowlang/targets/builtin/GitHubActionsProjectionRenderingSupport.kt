package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.AdapterManifestLowering.id as sanitizeId
import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingKind

internal object GitHubActionsProjectionPayloadKinds {
    const val GITHUB_ACTION = "GITHUB_ACTION"
}

internal object GitHubActionsProjectionSyntax : ProjectionValueSyntax {
    override fun renderOpaque(name: String): String = githubExpression("env.${safeEnvName(name)}")
    override fun renderInput(name: String): String = githubExpression("inputs.$name")
    fun bindingValue(name: String): String = githubExpression("secrets.$name")
    override fun mappingNote(name: String): String =
        "value mapping requirement: repository opaque value $name must be available as ${renderOpaque(name)}"
}

internal object GitHubActionsProjectionBindingRenderer {
    fun githubValue(binding: ProjectionBinding, context: String): String = when (binding.kind) {
        ProjectionBindingKind.LITERAL,
        ProjectionBindingKind.TASK_PARAMETER,
        ProjectionBindingKind.TASK_INPUT,
        ProjectionBindingKind.TASK_METADATA -> requireNotNull(binding.value) { "$context ${binding.kind} binding is unresolved." }
        ProjectionBindingKind.FLOW_INPUT -> githubExpression("inputs.${safeIdentifier(requireName(binding, context))}")
        ProjectionBindingKind.SECRET -> githubExpression("secrets.${requireName(binding, context)}")
        ProjectionBindingKind.TASK_OUTPUT -> githubExpression(
            "needs.${sanitizeId(requireNotNull(binding.taskId) { "$context TASK_OUTPUT is missing taskId." })}.outputs.${sanitizeId(requireNotNull(binding.output) { "$context TASK_OUTPUT is missing output." })}"
        )
        ProjectionBindingKind.TARGET_EXPRESSION -> {
            require(binding.target == "github-actions") { "$context contains a target expression for '${binding.target}', not GitHub Actions." }
            requireNotNull(binding.expression) { "$context target expression is missing expression text." }
        }
        ProjectionBindingKind.ARTIFACT -> error("$context uses ARTIFACT, but GitHub Actions artifact binding requires an explicit action contract.")
    }

    private fun requireName(binding: ProjectionBinding, context: String): String =
        requireNotNull(binding.name) { "$context ${binding.kind} binding is missing name." }
}

internal fun githubExpression(value: String): String = "\${{ $value }}"
