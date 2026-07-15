package org.flowlang.generators.manifest

import org.flowlang.projection.ProjectionBindingContract

internal data class TargetBindingValidationIssue(
    val name: String,
    val reason: String
)

/** Shared validation used by manifest contracts and executable readiness. */
internal object TargetManifestBindingValidation {
    private val bindingNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    fun issues(payload: TargetRendererPayload): List<TargetBindingValidationIssue> = payload.bindings.flatMap { (name, binding) ->
        buildList {
            if (!bindingNamePattern.matches(name)) {
                add(TargetBindingValidationIssue(name, "Binding name '$name' is not renderer-safe."))
            }
            ProjectionBindingContract.validationReason(
                binding = binding,
                resolved = true,
                payloadTarget = payload.target
            )?.let { reason -> add(TargetBindingValidationIssue(name, reason)) }
        }
    }
}
