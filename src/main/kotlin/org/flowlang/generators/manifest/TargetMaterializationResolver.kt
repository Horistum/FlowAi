package org.flowlang.generators.manifest

import org.flowlang.materialization.MaterializationNegotiation
import org.flowlang.materialization.MaterializationNegotiationValidator
import org.flowlang.notes.NotesPackageContract
import org.flowlang.projection.TargetProjectionArtifact
import org.flowlang.projection.TargetProjectionPlan
import org.flowlang.projection.TargetProjectionPlanValidator
import org.flowlang.semantic.SemanticActionGraph
import org.flowlang.semantic.SemanticActionGraphValidator

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


/**
 * Mandatory validation boundary for generated materialization evidence.
 *
 * The resolver may construct evidence, but it cannot publish that evidence until
 * all target-neutral semantic, negotiation and projection contracts agree.
 */
internal object TargetMaterializationEvidenceAuthority {
    fun requireValid(resolution: TargetMaterializationResolution): TargetMaterializationResolution {
        val semantic = SemanticActionGraphValidator(resolution.notesPackages).validate(resolution.semanticGraph)
        val negotiation = MaterializationNegotiationValidator(resolution.notesPackages).validate(resolution.negotiation)
        val projection = TargetProjectionPlanValidator(resolution.notesPackages).validate(resolution.projectionPlan)
        val issues = buildList {
            semantic.issues.forEach { add("${it.code}: ${it.message}") }
            negotiation.issues.forEach { add("${it.code}: ${it.message}") }
            projection.issues.forEach { add("${it.code}: ${it.message}") }
            if (resolution.negotiation.graph != resolution.semanticGraph) {
                add("materialization.graph.authority-mismatch: Negotiation graph does not match the validated semantic graph.")
            }
            if (resolution.projectionPlan.negotiation != resolution.negotiation) {
                add("projection.negotiation.authority-mismatch: Projection plan does not contain the validated negotiation.")
            }
            if (resolution.artifact !in resolution.projectionPlan.artifacts) {
                add("projection.artifact.authority-mismatch: Selected artifact is not present in the validated projection plan.")
            }
        }
        require(issues.isEmpty()) {
            "Generated materialization evidence is invalid: ${issues.joinToString()}"
        }
        return resolution
    }
}
