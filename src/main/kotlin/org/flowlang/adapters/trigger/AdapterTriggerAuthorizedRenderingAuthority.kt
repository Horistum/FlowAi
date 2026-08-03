package org.flowlang.adapters.trigger

import java.io.File
import org.flowlang.adapters.rendering.AdapterArtifactRenderingAuthority
import org.flowlang.adapters.rendering.AdapterArtifactRenderingBundle
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.targets.builtin.BuiltInTargetProjections

/**
 * Trigger-aware decorator over the single A0.6 artifact rendering authority.
 *
 * It does not render content and does not choose a file identity. It only proves
 * that every explicit manifest trigger already passed A0.7 materialization before
 * delegating the unchanged manifest to A0.6.
 */
class AdapterTriggerAuthorizedRenderingAuthority(
    rootDir: File = File("."),
    projections: TargetProjectionRegistry = BuiltInTargetProjections.registry,
    private val delegate: AdapterArtifactRenderingAuthority =
        AdapterArtifactRenderingAuthority(rootDir, projections)
) {
    fun render(manifest: TargetManifest): AdapterArtifactRenderingBundle {
        requireAuthorized(manifest)
        return delegate.render(manifest)
    }

    fun requireAuthorized(manifest: TargetManifest) {
        if (manifest.triggers.isEmpty()) return
        val decision = manifest.metadata["adapterTriggerDecision"]
        val requirementCount = manifest.metadata["adapterTriggerRequirementCount"]?.toIntOrNull()
        val blockerCount = manifest.metadata["adapterTriggerBlockerCount"]?.toIntOrNull()
        require(decision == AdapterTriggerDecision.MATCHED.name) {
            "Manifest '${manifest.flowName}' for '${manifest.target}' contains explicit triggers without MATCHED adapter trigger evidence."
        }
        require(requirementCount == manifest.triggers.size) {
            "Manifest '${manifest.flowName}' trigger evidence count '$requirementCount' does not match ${manifest.triggers.size} preserved triggers."
        }
        require(blockerCount == 0) {
            "Manifest '${manifest.flowName}' declares adapter trigger blockers and cannot be rendered."
        }
    }
}
