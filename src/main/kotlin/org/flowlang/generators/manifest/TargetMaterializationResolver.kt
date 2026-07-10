package org.flowlang.generators.manifest

import org.flowlang.materialization.MaterializationDecision
import org.flowlang.materialization.MaterializationEvidence
import org.flowlang.materialization.MaterializationEvidenceKind
import org.flowlang.materialization.MaterializationNegotiation
import org.flowlang.materialization.MaterializationStatus
import org.flowlang.notes.NotesPackageBoundary
import org.flowlang.notes.NotesPackageContract
import org.flowlang.notes.NotesPackageKind
import org.flowlang.planner.TaskNode
import org.flowlang.projection.TargetProjectionArtifact
import org.flowlang.projection.TargetProjectionArtifactKind
import org.flowlang.projection.TargetProjectionPlan
import org.flowlang.semantic.SemanticActionEdge
import org.flowlang.semantic.SemanticActionEdgeKind
import org.flowlang.semantic.SemanticActionGraph
import org.flowlang.semantic.SemanticActionKind
import org.flowlang.semantic.SemanticActionNode

/**
 * Bridges real execution-plan tasks into the notes / semantic / materialization /
 * projection contract stack.
 *
 * This resolver is intentionally conservative. It does not invent target-native
 * execution for ordinary actions. It does make the contract path real: every
 * task receives a semantic node, a materialization decision and a projection
 * artifact before it becomes a TargetMaterialization on the manifest boundary.
 */
internal data class TargetMaterializationResolution(
    val semanticGraph: SemanticActionGraph,
    val notesPackages: List<NotesPackageContract>,
    val negotiation: MaterializationNegotiation,
    val projectionPlan: TargetProjectionPlan,
    val artifact: TargetProjectionArtifact,
    val materialization: TargetMaterialization
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
        return TargetMappingNote(
            level = level,
            target = targetName,
            nodeId = taskId,
            feature = feature,
            message = materialization.reason
        )
    }
}

internal object TargetMaterializationResolver {
    fun resolve(task: TaskNode, targetName: String): TargetMaterializationResolution {
        val capability = capabilityFor(task)
        val semanticNode = SemanticActionNode(
            id = "task.${contractId(task.id)}",
            kind = SemanticActionKind.CAPABILITY,
            declaration = capability,
            notesPackageId = GENERATED_CAPABILITY_PACKAGE,
            description = "Task capability derived from the execution plan.",
            attributes = mapOf(
                "sourceTask" to task.id,
                "sourceAction" to "${task.module}.${task.action}",
                "targetBoundary" to targetName
            )
        )
        val graph = SemanticActionGraph(
            graphId = "flow.semantic.${contractId(task.id)}",
            nodes = listOf(semanticNode),
            edges = emptyList()
        )
        val notes = listOf(generatedCapabilityNotes(capability))
        val decision = decisionFor(task, capability)
        val negotiation = MaterializationNegotiation(
            negotiationId = "flow.materialization.${contractId(task.id)}",
            graph = graph,
            decisions = listOf(decision)
        )
        val artifact = projectionArtifactFor(task, targetName, semanticNode, decision)
        val projectionPlan = TargetProjectionPlan(
            planId = "flow.projection.${contractId(task.id)}",
            negotiation = negotiation,
            artifacts = listOf(artifact)
        )
        val materialization = targetMaterializationFor(capability, decision, artifact, projectionPlan)
        return TargetMaterializationResolution(graph, notes, negotiation, projectionPlan, artifact, materialization)
    }

    private fun capabilityFor(task: TaskNode): String = when (task.module to task.action) {
        "shell" to "run" -> "manual.runtime.action"
        else -> listOf(task.module, task.action).joinToString(".").ifBlank { "flow.action" }
    }

    private fun decisionFor(task: TaskNode, capability: String): MaterializationDecision = when (task.module to task.action) {
        "shell" to "run" -> MaterializationDecision(
            nodeId = "task.${contractId(task.id)}",
            status = MaterializationStatus.BLOCKED,
            reason = "Manual runtime action is preserved for review, but raw runtime execution is blocked at the Flow Core projection boundary.",
            evidence = listOf(MaterializationEvidence("v0.9.5.1.shell-prohibition", MaterializationEvidenceKind.REVIEW_DECISION, "The correction track prohibits raw runtime projection."))
        )
        "standard" to "execute" -> MaterializationDecision(
            nodeId = "task.${contractId(task.id)}",
            status = MaterializationStatus.MATERIALIZABLE,
            reason = "Standard execution is declared as notes-backed semantic work and may advance to projection review.",
            evidence = listOf(MaterializationEvidence(capability, MaterializationEvidenceKind.CAPABILITY_DECLARATION, "Generated capability notes declare this standard action."))
        )
        "standard" to "rollback" -> MaterializationDecision(
            nodeId = "task.${contractId(task.id)}",
            status = MaterializationStatus.MATERIALIZABLE,
            reason = "Rollback intent is preserved as notes-backed semantic work and may advance to projection review.",
            evidence = listOf(MaterializationEvidence(capability, MaterializationEvidenceKind.CAPABILITY_DECLARATION, "Generated capability notes declare this rollback action."))
        )
        else -> MaterializationDecision(
            nodeId = "task.${contractId(task.id)}",
            status = MaterializationStatus.ADAPTER_REQUIRED,
            reason = "Action capability is represented in the semantic graph, but target materialization requires an explicit projection adapter.",
            evidence = listOf(MaterializationEvidence("projection.${contractId(capability)}", MaterializationEvidenceKind.PROJECTION_RULE, "No target projection rule is declared for this action."))
        )
    }

    private fun projectionArtifactFor(
        task: TaskNode,
        targetName: String,
        node: SemanticActionNode,
        decision: MaterializationDecision
    ): TargetProjectionArtifact {
        val kind = when (decision.status) {
            MaterializationStatus.MATERIALIZABLE -> TargetProjectionArtifactKind.NOTES_BACKED
            MaterializationStatus.ADAPTER_REQUIRED -> TargetProjectionArtifactKind.ADAPTER_BOUNDARY
            MaterializationStatus.UNSUPPORTED,
            MaterializationStatus.BLOCKED,
            MaterializationStatus.DEFERRED -> TargetProjectionArtifactKind.REVIEW_RECORD
        }
        return TargetProjectionArtifact(
            artifactId = "artifact.${contractId(task.id)}",
            nodeId = node.id,
            kind = kind,
            materializationStatus = decision.status,
            target = if (kind == TargetProjectionArtifactKind.TARGET_NATIVE) targetName else "",
            notesReference = if (kind == TargetProjectionArtifactKind.NOTES_BACKED) node.declaration else "",
            description = "Projection evidence for task '${task.id}'.",
            fields = mapOf(
                "capability" to node.declaration,
                "sourceTask" to task.id,
                "targetBoundary" to targetName,
                "decision" to decision.status.name
            )
        )
    }

    private fun targetMaterializationFor(
        capability: String,
        decision: MaterializationDecision,
        artifact: TargetProjectionArtifact,
        projectionPlan: TargetProjectionPlan
    ): TargetMaterialization {
        val requirements = mapOf(
            "semanticNode" to decision.nodeId,
            "materializationStatus" to decision.status.name,
            "projectionPlan" to projectionPlan.planId,
            "projectionArtifact" to artifact.artifactId,
            "projectionArtifactKind" to artifact.kind.name
        )
        return when (decision.status) {
            MaterializationStatus.MATERIALIZABLE -> TargetMaterialization(
                status = if (artifact.kind == TargetProjectionArtifactKind.TARGET_NATIVE) TargetMaterializationStatus.NATIVE else TargetMaterializationStatus.NOTES_PROJECTED,
                capability = capability,
                reason = decision.reason,
                requirements = requirements,
                metadata = mapOf("materializationSource" to "notes-negotiation")
            )
            MaterializationStatus.ADAPTER_REQUIRED -> TargetMaterialization.adapterRequired(capability, decision.reason, requirements)
            MaterializationStatus.UNSUPPORTED -> TargetMaterialization(TargetMaterializationStatus.UNSUPPORTED, capability, decision.reason, requirements)
            MaterializationStatus.BLOCKED -> TargetMaterialization.blocked(capability, decision.reason).copy(requirements = requirements)
            MaterializationStatus.DEFERRED -> TargetMaterialization(TargetMaterializationStatus.DECLARATIVE_ONLY, capability, decision.reason, requirements)
        }
    }

    private fun generatedCapabilityNotes(capability: String): NotesPackageContract = NotesPackageContract(
        packageId = GENERATED_CAPABILITY_PACKAGE,
        packageVersion = "0.9.5.7.1",
        kind = NotesPackageKind.CAPABILITY,
        description = "Generated task capability notes used to bind execution-plan actions to materialization negotiation.",
        declaredCapabilities = setOf(capability),
        boundaries = setOf(NotesPackageBoundary("generated-task-capability", "Generated notes bind existing plan actions to materialization negotiation without claiming target execution."))
    )

    private fun contractId(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "flow" }

    private const val GENERATED_CAPABILITY_PACKAGE = "flow.capability.generated"
}
