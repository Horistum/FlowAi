package org.flowlang.generators.manifest

import org.flowlang.capabilities.TargetProjectionMode
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.compiler.requireAuthorizedTask
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
import org.flowlang.obligations.ArchitectureObligationGraph
import org.flowlang.obligations.ArchitectureObligationKind
import org.flowlang.obligations.ArchitectureObligationNode

/**
 * Derives materialization obligation evidence from an already authorized canonical task.
 *
 * Semantic capability comes exclusively from CanonicalExecutionGraph. Module/action remains
 * implementation binding used only to select target projection rules; it can never substitute
 * for canonical meaning.
 */
internal object TargetMaterializationResolver {
    fun resolve(
        authorization: CompilationAuthorization,
        task: TaskNode,
        targetName: String,
        projectionRules: List<TargetProjectionRule> = emptyList(),
        nativeProjections: TargetNativeProjectionCatalog = TargetNativeProjectionCatalog.empty(targetName)
    ): TargetMaterializationResolution {
        require(nativeProjections.target == targetName) {
            "Materialization target '$targetName' cannot use native projection catalog '${nativeProjections.target}'."
        }
        val authorizedTask = authorization.requireAuthorizedTask(task)
        val canonicalCapability = authorizedTask.node.semantics.capability?.value
        val implementationBinding = "${authorizedTask.binding.module}.${authorizedTask.binding.action}"
        val obligation = obligationFor(
            task = task,
            canonicalNodeId = authorizedTask.node.id.value,
            canonicalCapability = canonicalCapability,
            implementationBinding = implementationBinding,
            targetName = targetName,
            graphDigest = authorization.graphDigest.value
        )
        val obligationNode = obligation.node
        val graph = ArchitectureObligationGraph(
            // Historical evidence id is part of frozen Target Manifest 3.0 metadata.
            graphId = "flow.semantic.${contractId(task.id)}",
            nodes = listOf(obligationNode),
            edges = emptyList()
        )
        val notes = listOf(obligation.notes)
        val rule = projectionRules.singleOrNull { it.matches(task.module, task.action) }
            ?: projectionRules.firstOrNull { it.module == task.module && it.action == "*" }
        // TargetMaterialization.capability is a frozen compatibility field. For historical
        // Flow Source tasks without canonical capability it retains the implementation label,
        // but that label is never promoted into canonical or obligation semantic identity.
        val compatibilityCapability = canonicalCapability ?: implementationBinding
        val decision = decisionFor(
            task = task,
            compatibilityCapability = compatibilityCapability,
            obligationDeclaration = obligationNode.declaration,
            hasCanonicalCapability = canonicalCapability != null,
            targetName = targetName,
            rule = rule
        )
        val negotiation = MaterializationNegotiation(
            negotiationId = "flow.materialization.${contractId(task.id)}",
            graph = graph,
            decisions = listOf(decision)
        )
        val artifact = projectionArtifactFor(task, targetName, obligationNode, decision, rule)
        val projectionPlan = TargetProjectionPlan(
            planId = "flow.projection.${contractId(task.id)}",
            negotiation = negotiation,
            artifacts = listOf(artifact)
        )
        val materialization = targetMaterializationFor(compatibilityCapability, decision, artifact, projectionPlan)
        val payload = if (artifact.kind == TargetProjectionArtifactKind.TARGET_NATIVE) {
            nativeProjections.compile(requireNotNull(rule), task)
        } else null
        return TargetMaterializationEvidenceAuthority.requireValid(
            TargetMaterializationResolution(
                graph,
                notes,
                negotiation,
                projectionPlan,
                artifact,
                materialization,
                payload
            )
        )
    }

    private data class MaterializationObligation(
        val node: ArchitectureObligationNode,
        val notes: NotesPackageContract
    )

    /**
     * Builds evidence about the already-authorized task without inventing executable semantics.
     *
     * A canonical capability, when present, is legitimate semantic evidence. Older Flow Source
     * tasks may intentionally have no canonical capability; for those tasks we record only the
     * target-projection obligation. The implementation binding is evidence for adapter selection,
     * never a replacement CanonicalExecutionGraph capability.
     */
    private fun obligationFor(
        task: TaskNode,
        canonicalNodeId: String,
        canonicalCapability: String?,
        implementationBinding: String,
        targetName: String,
        graphDigest: String
    ): MaterializationObligation {
        val commonAttributes = linkedMapOf(
            "sourceTask" to task.id,
            "canonicalNode" to canonicalNodeId,
            "implementationBinding" to implementationBinding,
            "targetBoundary" to targetName,
            "graphDigest" to graphDigest
        )
        return if (canonicalCapability != null) {
            commonAttributes["canonicalCapability"] = canonicalCapability
            MaterializationObligation(
                node = ArchitectureObligationNode(
                    id = "task.${contractId(task.id)}",
                    kind = ArchitectureObligationKind.CAPABILITY,
                    declaration = canonicalCapability,
                    notesPackageId = GENERATED_CAPABILITY_PACKAGE,
                    description = "Architecture obligation derived from the authorized canonical task capability.",
                    attributes = commonAttributes
                ),
                notes = generatedCapabilityNotes(canonicalCapability)
            )
        } else {
            MaterializationObligation(
                node = ArchitectureObligationNode(
                    id = "task.${contractId(task.id)}",
                    kind = ArchitectureObligationKind.CONFORMANCE_REQUIREMENT,
                    declaration = AUTHORIZED_TASK_DECLARATION,
                    notesPackageId = GENERATED_AUTHORIZATION_PACKAGE,
                    description = "Authorization obligation for a canonical task with no declared semantic capability.",
                    attributes = commonAttributes
                ),
                notes = generatedAuthorizationNotes()
            )
        }
    }

    private fun decisionFor(
        task: TaskNode,
        compatibilityCapability: String,
        obligationDeclaration: String,
        hasCanonicalCapability: Boolean,
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
            evidence += if (hasCanonicalCapability) {
                MaterializationEvidence(
                    compatibilityCapability,
                    MaterializationEvidenceKind.CAPABILITY_DECLARATION,
                    "Canonical semantic capability declaration."
                )
            } else {
                MaterializationEvidence(
                    obligationDeclaration,
                    MaterializationEvidenceKind.CONFORMANCE_CHECK,
                    "Canonical task authorization evidence; no semantic capability was inferred from implementation binding."
                )
            }
        }
        return MaterializationDecision(nodeId, status, rule.reason, evidence)
    }

    private fun projectionArtifactFor(
        task: TaskNode,
        targetName: String,
        node: ArchitectureObligationNode,
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


    private fun generatedCapabilityNotes(capability: String): NotesPackageContract = NotesPackageContract(
        packageId = GENERATED_CAPABILITY_PACKAGE,
        packageVersion = "0.9.5",
        kind = NotesPackageKind.CAPABILITY,
        description = "Generated capability notes bind authorized canonical task capability to materialization-obligation evidence.",
        declaredCapabilities = setOf(capability),
        boundaries = setOf(NotesPackageBoundary(
            "generated-task-capability",
            "Generated notes bind canonical capability evidence to materialization negotiation without claiming execution authority."
        ))
    )

    private fun generatedAuthorizationNotes(): NotesPackageContract = NotesPackageContract(
        packageId = GENERATED_AUTHORIZATION_PACKAGE,
        packageVersion = "0.9.5",
        kind = NotesPackageKind.CONFORMANCE,
        description = "Generated notes record canonical task authorization without inventing semantic capability.",
        conformanceChecks = setOf(AUTHORIZED_TASK_DECLARATION),
        boundaries = setOf(NotesPackageBoundary(
            "authorized-canonical-task",
            "Implementation binding is recorded only after exact canonical graph authorization."
        ))
    )

    private fun contractId(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "flow" }

    private const val GENERATED_CAPABILITY_PACKAGE = "flow.capability.generated"
    private const val GENERATED_AUTHORIZATION_PACKAGE = "flow.conformance.generated"
    private const val AUTHORIZED_TASK_DECLARATION = "canonical.task.authorized"
}
