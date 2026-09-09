package org.flowlang.adapters.testing

import org.flowlang.adapters.rendering.AdapterArtifactRenderingBundle
import org.flowlang.adapters.rendering.AdapterArtifactRenderingIntegrityAuthority
import org.flowlang.adapters.rendering.AdapterArtifactRenderingRecord
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetRenderReadiness

/** Mutation-only access. This class is never a production dependency. */
object RenderingEvidenceTestFixture {
    fun requireValid(
        bundle: AdapterArtifactRenderingBundle,
        manifest: TargetManifest,
        readiness: TargetRenderReadiness,
        record: AdapterArtifactRenderingRecord
    ): AdapterArtifactRenderingBundle =
        AdapterArtifactRenderingIntegrityAuthority.requireValid(bundle, manifest, readiness, record)
}
