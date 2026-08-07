package org.flowlang.adapters

import org.flowlang.capabilities.CompatibilityIssue
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMappingNote

/**
 * Shared mechanical projection of an already-owned adapter blocker decision into
 * diagnostic manifest evidence.
 *
 * This helper owns no policy: control, continuity and trigger authorities still
 * decide whether evidence is blocking and construct their domain-specific issues.
 */
internal object AdapterDiagnosticReconciliation {
    fun blocked(
        manifest: TargetManifest,
        metadata: Map<String, String>,
        issues: List<CompatibilityIssue>
    ): TargetManifest {
        require(issues.isNotEmpty()) {
            "A blocked adapter diagnostic reconciliation requires at least one compatibility issue."
        }
        val notes = issues.map { issue ->
            TargetMappingNote(
                level = "error",
                target = issue.target,
                nodeId = issue.nodeId,
                feature = issue.feature,
                message = issue.message
            )
        }
        return manifest.copy(
            compatibility = manifest.compatibility.copy(
                status = SupportLevel.UNSUPPORTED,
                issues = (manifest.compatibility.issues + issues).distinct(),
                executable = false,
                readinessEvidenceAvailable = true
            ),
            mappingNotes = (manifest.mappingNotes + notes).distinct(),
            metadata = metadata
        )
    }
}
