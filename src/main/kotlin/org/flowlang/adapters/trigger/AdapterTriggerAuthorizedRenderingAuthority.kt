package org.flowlang.adapters.trigger

import java.io.File
import org.flowlang.adapters.rendering.AdapterArtifactRenderingAuthority
import org.flowlang.adapters.rendering.AdapterArtifactRenderingBundle
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.providerFor
import org.flowlang.generators.manifest.requireProvider

/**
 * Trigger-aware decorator over the single A0.6 artifact rendering authority.
 *
 * It does not render content and does not choose a file identity. It verifies the
 * A0.7 assessment before delegation and then ensures that a blocked trigger can
 * produce review evidence only, never executable target syntax.
 */
class AdapterTriggerAuthorizedRenderingAuthority(
    rootDir: File = File("."),
    projections: AdapterCatalog<TargetProjectionProvider>,
    private val delegate: AdapterArtifactRenderingAuthority =
        AdapterArtifactRenderingAuthority(rootDir, projections)
) {
    fun render(manifest: TargetManifest): AdapterArtifactRenderingBundle {
        val decision = requireAssessment(manifest)
        val bundle = delegate.render(manifest)
        if (decision == AdapterTriggerDecision.BLOCKED) {
            require(bundle.artifact.kind == AdapterRenderedArtifactKind.REVIEW_EVIDENCE) {
                "Blocked adapter trigger evidence produced executable target syntax for '${manifest.target}'."
            }
        }
        return bundle
    }

    fun requireAssessment(manifest: TargetManifest): AdapterTriggerDecision? {
        if (manifest.triggers.isEmpty()) return null
        val decision = manifest.metadata["adapterTriggerDecision"]
            ?.let { value -> runCatching { AdapterTriggerDecision.valueOf(value) }.getOrNull() }
            ?: error(
                "Manifest '${manifest.flowName}' for '${manifest.target}' contains explicit triggers without a valid A0.7 adapter trigger decision."
            )
        val requirementCount = manifest.metadata["adapterTriggerRequirementCount"]?.toIntOrNull()
        val blockerCount = manifest.metadata["adapterTriggerBlockerCount"]?.toIntOrNull()
        require(requirementCount == manifest.triggers.size) {
            "Manifest '${manifest.flowName}' trigger evidence count '$requirementCount' does not match ${manifest.triggers.size} preserved triggers."
        }
        when (decision) {
            AdapterTriggerDecision.MATCHED -> require(blockerCount == 0) {
                "Matched adapter trigger evidence for '${manifest.flowName}' declares blocker count '$blockerCount'."
            }
            AdapterTriggerDecision.BLOCKED -> require(blockerCount != null && blockerCount > 0) {
                "Blocked adapter trigger evidence for '${manifest.flowName}' does not declare a positive blocker count."
            }
        }
        return decision
    }
}
