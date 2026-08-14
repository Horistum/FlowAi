package org.flowlang.conformance

import org.flowlang.effects.SemanticEffect
import org.flowlang.effects.canonicalObservationValue
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelations
import org.flowlang.planner.PlanDependencyResolution
import org.flowlang.planner.PlanNode
import org.flowlang.planner.TaskNode

/**
 * Derives observable requirements exclusively from target-neutral plan meaning.
 * Provider syntax, job layout, module names, action names and target identities
 * are intentionally absent from every requirement fingerprint.
 */
object SemanticObservationAuthority {
    fun requirementsFor(plan: ExecutionPlan): List<SemanticObservationRequirement> {
        val nodes = PlanDependencyRelations.flatten(plan.nodes)
        val semanticIdentityByNode = nodes.associate { node -> node.id to semanticIdentity(node) }
        val requirements = buildList {
            nodes.filterIsInstance<TaskNode>().forEach { task ->
                task.effectModel.forEach { effect -> add(effectRequirement(task, effect)) }
                task.resultName?.takeIf(String::isNotBlank)?.let { resultName ->
                    add(
                        requirement(
                            kind = SemanticObservationKind.RESULT_IDENTITY,
                            subject = semanticIdentity(task),
                            value = resultName,
                            producerIdentity = semanticIdentity(task)
                        )
                    )
                }
            }
            nodes.filterIsInstance<ApprovalNode>().forEach { approval ->
                approval.resultName?.takeIf(String::isNotBlank)?.let { resultName ->
                    add(
                        requirement(
                            kind = SemanticObservationKind.RESULT_IDENTITY,
                            subject = semanticIdentity(approval),
                            value = resultName,
                            producerIdentity = semanticIdentity(approval)
                        )
                    )
                }
            }
            plan.outputs.forEach { output ->
                val producerIdentity = output.sourceNodeId?.let { sourceNodeId ->
                    requireNotNull(semanticIdentityByNode[sourceNodeId]) {
                        "Plan output '${output.name}' references unknown source node '$sourceNodeId'."
                    }
                } ?: FLOW_OUTPUT_PRODUCER
                add(
                    requirement(
                        kind = SemanticObservationKind.RESULT_VALUE,
                        subject = output.name,
                        value = output.type,
                        producerIdentity = producerIdentity
                    )
                )
            }
            plan.dependencyRelations
                .filter { it.kind != PlanDependencyKind.ORDERING }
                .forEach { relation ->
                    require(relation.resolution == PlanDependencyResolution.RESOLVED) {
                        "Semantic equivalence cannot certify ${relation.kind} continuity from '${relation.sourceNodeId}' " +
                            "to '${relation.targetNodeId}' with resolution ${relation.resolution}."
                    }
                    val sourceNodeId = requireNotNull(relation.sourceNodeId) {
                        "Resolved ${relation.kind} continuity to '${relation.targetNodeId}' has no source node."
                    }
                    val producerIdentity = requireNotNull(semanticIdentityByNode[sourceNodeId]) {
                        "Resolved ${relation.kind} continuity references unknown source node '$sourceNodeId'."
                    }
                    val consumerIdentity = requireNotNull(semanticIdentityByNode[relation.targetNodeId]) {
                        "Resolved ${relation.kind} continuity references unknown target node '${relation.targetNodeId}'."
                    }
                    val channel = relation.channel?.takeIf(String::isNotBlank) ?: DEFAULT_CONTINUITY_CHANNEL
                    val value = if (relation.kind == PlanDependencyKind.STATE) {
                        val lifetime = requireNotNull(relation.stateLifetime) {
                            "Resolved STATE continuity '$channel' has no explicit state lifetime."
                        }
                        "$channel|lifetime=${lifetime.wireName}"
                    } else {
                        channel
                    }
                    add(
                        requirement(
                            kind = SemanticObservationKind.CONTINUITY,
                            subject = relation.kind.name,
                            value = value,
                            producerIdentity = producerIdentity,
                            consumerIdentity = consumerIdentity
                        )
                    )
                }
        }
        return requirements
            .distinctBy(SemanticObservationRequirement::fingerprint)
            .sortedWith(compareBy({ it.kind.ordinal }, { it.subject }, { it.value }, { it.id }))
    }

    fun fullyPreserved(
        requirements: List<SemanticObservationRequirement>,
        evidenceReference: String
    ): List<SemanticObservationEvidence> {
        require(evidenceReference.isNotBlank()) { "Semantic observation evidence reference must not be blank." }
        return requirements.map { requirement ->
            SemanticObservationEvidence(
                requirementId = requirement.id,
                fingerprint = requirement.fingerprint,
                status = SemanticObservationEvidenceStatus.PRESERVED,
                evidenceReference = evidenceReference
            )
        }
    }

    fun assess(
        requirements: List<SemanticObservationRequirement>,
        evidence: List<SemanticObservationEvidence>
    ): SemanticEquivalenceAssessment {
        val duplicateRequirementIds = requirements.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateRequirementIds.isEmpty()) {
            "Semantic observation requirement ids must be unique: ${duplicateRequirementIds.sorted()}."
        }
        val requiredById = requirements.associateBy(SemanticObservationRequirement::id)
        val evidenceByRequirement = evidence.groupBy(SemanticObservationEvidence::requirementId)
        val observations = buildList {
            if (requirements.isEmpty()) {
                add(
                    SemanticObservationAssessment(
                        requirement = null,
                        status = SemanticObservationEvidenceStatus.UNKNOWN,
                        evidenceReferences = emptyList(),
                        message = "Semantic equivalence requires at least one explicit observable requirement."
                    )
                )
            }
            requirements.forEach { requirement ->
                add(assessmentFor(requirement, evidenceByRequirement[requirement.id].orEmpty()))
            }
            (evidenceByRequirement.keys - requiredById.keys).sorted().forEach { unknownId ->
                add(
                    SemanticObservationAssessment(
                        requirement = null,
                        status = SemanticObservationEvidenceStatus.CONTRADICTORY,
                        evidenceReferences = evidenceByRequirement.getValue(unknownId).map { it.evidenceReference }.sorted(),
                        message = "Evidence references unknown semantic observation requirement '$unknownId'."
                    )
                )
            }
        }
        val preserved = observations.count { it.requirement != null && it.status == SemanticObservationEvidenceStatus.PRESERVED }
        val blocking = observations.size - preserved
        return SemanticEquivalenceAssessment(
            decision = SemanticEquivalenceDecision(
                status = if (requirements.isNotEmpty() && blocking == 0 && preserved == requirements.size) {
                    SemanticEquivalenceDecisionStatus.EQUIVALENT
                } else {
                    SemanticEquivalenceDecisionStatus.NOT_EQUIVALENT
                },
                requiredObservations = requirements.size,
                preservedObservations = preserved,
                blockingObservations = blocking
            ),
            observations = observations
        )
    }

    private fun assessmentFor(
        requirement: SemanticObservationRequirement,
        evidence: List<SemanticObservationEvidence>
    ): SemanticObservationAssessment = when {
        evidence.isEmpty() -> SemanticObservationAssessment(
            requirement = requirement,
            status = SemanticObservationEvidenceStatus.MISSING,
            evidenceReferences = emptyList(),
            message = "Required semantic observation '${requirement.id}' has no evidence."
        )
        evidence.size > 1 -> SemanticObservationAssessment(
            requirement = requirement,
            status = SemanticObservationEvidenceStatus.CONTRADICTORY,
            evidenceReferences = evidence.map { it.evidenceReference }.sorted(),
            message = "Required semantic observation '${requirement.id}' has multiple evidence records."
        )
        evidence.single().fingerprint != requirement.fingerprint -> SemanticObservationAssessment(
            requirement = requirement,
            status = SemanticObservationEvidenceStatus.CONTRADICTORY,
            evidenceReferences = listOf(evidence.single().evidenceReference),
            message = "Evidence fingerprint does not match semantic observation '${requirement.id}'."
        )
        else -> SemanticObservationAssessment(
            requirement = requirement,
            status = evidence.single().status,
            evidenceReferences = listOf(evidence.single().evidenceReference),
            message = evidence.single().status.takeUnless { it == SemanticObservationEvidenceStatus.PRESERVED }?.let {
                "Semantic observation '${requirement.id}' is ${it.name}."
            }
        )
    }

    private fun effectRequirement(task: TaskNode, effect: SemanticEffect): SemanticObservationRequirement =
        requirement(
            kind = SemanticObservationKind.EFFECT,
            subject = semanticIdentity(task),
            value = effect.canonicalObservationValue(),
            producerIdentity = semanticIdentity(task)
        )

    private fun requirement(
        kind: SemanticObservationKind,
        subject: String,
        value: String,
        producerIdentity: String? = null,
        consumerIdentity: String? = null
    ): SemanticObservationRequirement = SemanticObservationRequirement(
        id = SemanticObservationIdentity.requirementId(kind, subject, value, producerIdentity, consumerIdentity),
        kind = kind,
        subject = subject,
        value = value,
        producerIdentity = producerIdentity,
        consumerIdentity = consumerIdentity
    )

    private fun semanticIdentity(node: PlanNode): String = when (node) {
        is TaskNode -> node.sourceId?.takeIf(String::isNotBlank) ?: node.id
        is ApprovalNode -> node.sourceId?.takeIf(String::isNotBlank) ?: node.id
        else -> node.id
    }

    private const val FLOW_OUTPUT_PRODUCER = "flow-output"
    private const val DEFAULT_CONTINUITY_CHANNEL = "default"
}
