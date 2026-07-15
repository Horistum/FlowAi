package org.flowlang.generators.manifest

import org.flowlang.projection.ProjectionBindingContract
import org.flowlang.projection.ProjectionBindingResolutionStatus

internal data class TargetBindingValidationIssue(
    val name: String,
    val reason: String
)

internal data class TargetUnresolvedBinding(
    val name: String,
    val reason: String
)

/** Shared validation used by manifest contracts and executable readiness. */
internal object TargetManifestBindingValidation {
    private val bindingNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    fun issues(payload: TargetRendererPayload): List<TargetBindingValidationIssue> =
        payload.bindings.flatMap { (name, binding) ->
            buildList {
                if (!bindingNamePattern.matches(name)) {
                    add(TargetBindingValidationIssue(name, "Binding name '$name' is not renderer-safe."))
                }
                ProjectionBindingContract.validationReason(
                    binding = binding,
                    manifest = true,
                    payloadTarget = payload.target
                )?.let { reason -> add(TargetBindingValidationIssue(name, reason)) }
            }
        }

    fun unresolved(payload: TargetRendererPayload): List<TargetUnresolvedBinding> =
        payload.bindings.mapNotNull { (name, binding) ->
            if (binding.resolutionStatus == ProjectionBindingResolutionStatus.UNRESOLVED) {
                TargetUnresolvedBinding(
                    name = name,
                    reason = binding.reason ?: "Projection binding is unresolved."
                )
            } else {
                null
            }
        }
}
