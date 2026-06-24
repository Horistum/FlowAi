package org.flowlang.artifacts

import org.flowlang.standard.StandardModel

/**
 * Compatibility facade over [StandardModel].
 *
 * New projections should read StandardModel directly. This facade remains only to
 * avoid forcing older callers through a noisy migration in the same patch.
 */
object StandardArtifactRegistry {
    fun publicSurfaceEntries(): List<PublicSurfaceEntry> = StandardModel.artifacts.map { artifact ->
        PublicSurfaceEntry(
            artifact = artifact.artifact,
            schema = artifact.schema,
            stability = artifact.stability,
            area = artifact.area,
            introducedIn = artifact.introducedIn,
            changeGate = artifact.changeGate,
            notes = artifact.notes
        )
    }

    fun releaseRequirements(): List<ReleaseRequirement> = StandardReleaseProfile.releaseRequirements()

    fun requiredReleaseChecks(): List<String> = StandardModel.releaseProfileCheckIds()

    fun standardCandidateChecks(): List<String> = StandardModel.candidateCheckIds()

    fun standardExportManifestChecks(): List<String> = StandardModel.standardExportManifestCheckIds()

    fun standardCandidateArtifacts(): List<String> = StandardModel.candidateArtifacts()
}
