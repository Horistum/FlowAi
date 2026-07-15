package org.flowlang.generators.manifest

import org.flowlang.capabilities.TargetProjectionMode
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.materialization.MaterializationDecision
import org.flowlang.materialization.MaterializationEvidence
import org.flowlang.materialization.MaterializationEvidenceKind
import org.flowlang.materialization.MaterializationNegotiation
import org.flowlang.materialization.MaterializationStatus
import org.flowlang.notes.NotesPackageBoundary
import org.flowlang.notes.NotesPackageContract
import org.flowlang.notes.NotesPackageKind
import org.flowlang.planner.TaskNode
import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingContract
import org.flowlang.projection.ProjectionBindingKind
import org.flowlang.projection.ProjectionBindingResolutionStatus
import org.flowlang.projection.TargetProjectionArtifact
import org.flowlang.projection.TargetProjectionArtifactKind
import org.flowlang.projection.TargetProjectionPlan
import org.flowlang.projection.TaskMetadataField
import org.flowlang.semantic.SemanticActionGraph
import org.flowlang.semantic.SemanticActionKind
import org.flowlang.semantic.SemanticActionNode

/**
 * Bridges execution-plan tasks into semantic, materialization and projection
 * evidence. Ordinary action decisions are read from target registry evidence;
 * this class contains no positive target/action allow-list.
 */
internal object TargetMaterializationResolver {
    fun resolve(
        task: TaskNode,
        targetName: String,
        projectionRules: List<TargetProjectionRule> = emptyList()
    ): TargetMaterializationResolution {
        val capability = capabilityFor(task)
        val semanticNode = SemanticActionNode(
            id = "task.${contractId(task.id)}",
            kind = SemanticActionKind.CAPABILITY,
            declaration = capability,
            notesPackageId = GENERATED_CAPABILITY_PACKAGE,
            description = "Task capability derived from the execution plan.",
            attributes = mapOf(
                "sourceTask" to task.id,
                "sourceCapability" to capability,
                "targetBoundary" to targetName
            )
        )
        val graph = SemanticActionGraph(
            graphId = "flow.semantic.${contractId(task.id)}",
            nodes = listOf(semanticNode),
            edges = emptyList()
        )
        val notes = listOf(generatedCapabilityNotes(capability))
        val rule = projectionRules.singleOrNull { it.matches(task.module, task.action) }
            ?: projectionRules.firstOrNull { it.module == task.module && it.action == "*" }
        val decision = decisionFor(task, capability, targetName, rule)
        val negotiation = MaterializationNegotiation(
            negotiationId = "flow.materialization.${contractId(task.id)}",
            graph = graph,
            decisions = listOf(decision)
        )
        val artifact = projectionArtifactFor(task, targetName, semanticNode, decision, rule)
        val projectionPlan = TargetProjectionPlan(
            planId = "flow.projection.${contractId(task.id)}",
            negotiation = negotiation,
            artifacts = listOf(artifact)
        )
        val materialization = targetMaterializationFor(capability, decision, artifact, projectionPlan)
        val payload = if (artifact.kind == TargetProjectionArtifactKind.TARGET_NATIVE) {
            rule?.payload?.let { template ->
                TargetRendererPayload(
                    kind = template.kind,
                    target = targetName,
                    reference = template.reference,
                    bindings = template.bindings.mapValues { (name, binding) ->
                        resolveBinding(binding, task, targetName, name)
                    },
                    evidenceReference = rule.evidenceReference
                )
            }
        } else null
        return TargetMaterializationResolution(
            graph,
            notes,
            negotiation,
            projectionPlan,
            artifact,
            materialization,
            payload
        )
    }

    private fun resolveBinding(
        binding: ProjectionBinding,
        task: TaskNode,
        payloadTarget: String,
        bindingName: String
    ): ProjectionBinding {
        ProjectionBindingContract.requireTemplate(binding, "$payloadTarget.bindings.$bindingName")
        val resolved = when (binding.kind) {
            ProjectionBindingKind.LITERAL -> binding.copy(
                resolutionStatus = ProjectionBindingResolutionStatus.RESOLVED
            )
            ProjectionBindingKind.TASK_PARAMETER -> resolveTaskValue(
                binding = binding,
                value = task.params[binding.name] ?: binding.defaultValue,
                unresolvedReason = "Task '${task.id}' does not provide required parameter '${binding.name}' for projection binding '$bindingName'."
            )
            ProjectionBindingKind.TASK_INPUT -> resolveTaskValue(
                binding = binding,
                value = task.inputs[binding.name] ?: binding.defaultValue,
                unresolvedReason = "Task '${task.id}' does not provide required input '${binding.name}' for projection binding '$bindingName'."
            )
            ProjectionBindingKind.TASK_METADATA -> binding.copy(
                value = when (binding.field) {
                    TaskMetadataField.ID -> task.id
                    TaskMetadataField.TARGET -> task.target
                    null -> error("TASK_METADATA projection binding '$bindingName' has no field.")
                },
                resolutionStatus = ProjectionBindingResolutionStatus.RESOLVED,
                reason = null
            )
            ProjectionBindingKind.FLOW_INPUT,
            ProjectionBindingKind.SECRET,
            ProjectionBindingKind.ARTIFACT,
            ProjectionBindingKind.TASK_OUTPUT,
            ProjectionBindingKind.TARGET_EXPRESSION -> binding.copy(
                resolutionStatus = ProjectionBindingResolutionStatus.SYMBOLIC,
                reason = null
            )
        }
        ProjectionBindingContract.requireManifest(
            resolved,
            payloadTarget,
            "$payloadTarget.bindings.$bindingName"
        )
        return resolved
    }

    private fun resolveTaskValue(
        binding: ProjectionBinding,
        value: String?,
        unresolvedReason: String
    ): ProjectionBinding = if (value != null) {
        binding.copy(
            value = value,
            resolutionStatus = ProjectionBindingResolutionStatus.RESOLVED,
            reason = null
        )
    } else {
        binding.copy(
            value = null,
            resolutionStatus = ProjectionBindingResolutionStatus.UNRESOLVED,
            reason = unresolvedReason
        )
    }

    private fun decisionFor(
        task: TaskNode,
        capability: String,
        targetName: String,
        rule: TargetProjectionRule?
    ): MaterializationDecision {
        val nodeId = "task.${contractId(task.id)}"
        if (task.module == "shell" && task.action == "run") {
            return MaterializationDecision(
                nodeId = nodeId,
                status = MaterializationStatus.BLOCKED,
                reason = "Raw runtime execution is blocked at the Flow Core projection boundary.",
                evidence = listOf(MaterializationEvidence(
                    "v0.9.5.1.shell-prohibition",
                    MaterializationEvidenceKind.REVIEW_DECISION,
                    "The architecture constitution prohibits raw runtime projection."
                ))
            )
        }
        if (rule == null) {
            return MaterializationDecision(
                nodeId = nodeId,
                status = MaterializationStatus.ADAPTER_REQUIRED,
                reason = "Target '$targetName' declares no projection rule for '${task.module}.${task.action}'.",
                evidence = listOf(MaterializationEvidence(
                    "target:$targetName#projectionRules",
                    MaterializationEvidenceKind.PROJECTION_RULE,
                    "Missing target projection evidence fails closed."
                ))
            )
        }
        val status = when (rule.mode) {
            TargetProjectionMode.NATIVE,
            TargetProjectionMode.NOTES_PROJECTED -> MaterializationStatus.MATERIALIZABLE
            TargetProjectionMode.ADAPTER_REQUIRED -> MaterializationStatus.ADAPTER_REQUIRED
            TargetProjectionMode.UNSUPPORTED -> MaterializationStatus.UNSUPPORTED
            TargetProjectionMode.BLOCKED -> MaterializationStatus.BLOCKED
        }
        val evidenceKind = when (rule.mode) {
            TargetProjectionMode.BLOCKED -> MaterializationEvidenceKind.REVIEW_DECISION
            else -> MaterializationEvidenceKind.PROJECTION_RULE
        }
        val evidence = mutableListOf(
            MaterializationEvidence(
                rule.evidenceReference,
                evidenceKind,
                "Declarative target registry projection evidence."
            )
        )
        if (status == MaterializationStatus.MATERIALIZABLE) {
            evidence += MaterializationEvidence(
                capability,
                MaterializationEvidenceKind.CAPABILITY_DECLARATION,
                "Semantic capability declaration."
            )
        }
        return MaterializationDecision(nodeId, status, rule.reason, evidence)
    }

    private fun projectionArtifactFor(
        task: TaskNode,
        targetName: String,
        node: SemanticActionNode,
        decision: MaterializationDecision,
        rule: TargetProjectionRule?
    ): TargetProjectionArtifact {
        val kind = when (decision.status) {
            MaterializationStatus.MATERIALIZABLE -> when (rule?.mode) {
                TargetProjectionMode.NATIVE -> TargetProjectionArtifactKind.TARGET_NATIVE
                else -> TargetProjectionArtifactKind.NOTES_BACKED
            }
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
                "decision" to decision.status.name,
                "evidenceReference" to (rule?.evidenceReference ?: "target:$targetName#projectionRules")
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
                status = if (artifact.kind == TargetProjectionArtifactKind.TARGET_NATIVE) {
                    TargetMaterializationStatus.NATIVE
                } else {
                    TargetMaterializationStatus.NOTES_PROJECTED
                },
                capability = capability,
                reason = decision.reason,
                requirements = requirements,
                metadata = mapOf("materializationSource" to "target-registry-projection-rule")
            )
            MaterializationStatus.ADAPTER_REQUIRED ->
                TargetMaterialization.adapterRequired(capability, decision.reason, requirements)
            MaterializationStatus.UNSUPPORTED ->
                TargetMaterialization(
                    TargetMaterializationStatus.UNSUPPORTED,
                    capability,
                    decision.reason,
                    requirements
                )
            MaterializationStatus.BLOCKED ->
                TargetMaterialization.blocked(capability, decision.reason).copy(requirements = requirements)
            MaterializationStatus.DEFERRED ->
                TargetMaterialization(
                    TargetMaterializationStatus.DECLARATIVE_ONLY,
                    capability,
                    decision.reason,
                    requirements
                )
        }
    }

    private fun capabilityFor(task: TaskNode): String = when (task.module to task.action) {
        "shell" to "run" -> "manual.runtime.action"
        else -> listOf(task.module, task.action).joinToString(".").ifBlank { "flow.action" }
    }

    private fun generatedCapabilityNotes(capability: String): NotesPackageContract = NotesPackageContract(
        packageId = GENERATED_CAPABILITY_PACKAGE,
        packageVersion = "0.9.5",
        kind = NotesPackageKind.CAPABILITY,
        description = "Generated task capability notes used to bind execution-plan actions to materialization negotiation.",
        declaredCapabilities = setOf(capability),
        boundaries = setOf(NotesPackageBoundary(
            "generated-task-capability",
            "Generated notes bind existing plan actions to materialization negotiation without claiming target execution."
        ))
    )

    private fun contractId(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "flow" }

    private const val GENERATED_CAPABILITY_PACKAGE = "flow.capability.generated"
}
