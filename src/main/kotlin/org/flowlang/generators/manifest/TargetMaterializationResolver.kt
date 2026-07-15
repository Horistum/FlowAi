package org.flowlang.generators.manifest

import org.flowlang.materialization.MaterializationNegotiation
import org.flowlang.notes.NotesPackageContract
import org.flowlang.projection.TargetProjectionArtifact
import org.flowlang.projection.TargetProjectionPlan
import org.flowlang.semantic.SemanticActionGraph

/**
 * Auditable result of resolving one execution-plan task against target
 * projection evidence.
 */
internal data class TargetMaterializationResolution(
    val semanticGraph: SemanticActionGraph,
    val notesPackages: List<NotesPackageContract>,
    val negotiation: MaterializationNegotiation,
    val projectionPlan: TargetProjectionPlan,
    val artifact: TargetProjectionArtifact,
    val materialization: TargetMaterialization,
    val rendererPayload: TargetRendererPayload? = null
) {
    fun mappingNote(targetName: String, taskId: String): TargetMappingNote {
        val level = when (materialization.status) {
            TargetMaterializationStatus.NATIVE,
            TargetMaterializationStatus.NOTES_PROJECTED -> "info"
            TargetMaterializationStatus.ADAPTER_REQUIRED,
            TargetMaterializationStatus.DECLARATIVE_ONLY,
            TargetMaterializationStatus.SEMANTIC_ONLY -> "warning"
            TargetMaterializationStatus.UNSUPPORTED,
            TargetMaterializationStatus.BLOCKED -> "error"
        }
        val feature = when (materialization.status) {
            TargetMaterializationStatus.BLOCKED -> "materialization.blocked"
            TargetMaterializationStatus.SEMANTIC_ONLY -> "materialization.semantic-only"
            TargetMaterializationStatus.ADAPTER_REQUIRED -> "materialization.adapter-required"
            else -> "materialization.${materialization.status.name.lowercase().replace('_', '-')}"
        }
        return TargetMappingNote(level, targetName, taskId, feature, materialization.reason)
    }
}
