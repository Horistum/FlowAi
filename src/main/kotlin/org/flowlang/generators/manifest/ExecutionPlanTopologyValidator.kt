package org.flowlang.generators.manifest

import org.flowlang.capabilities.TargetCapability
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanDependencyRelations
import org.flowlang.planner.TaskNode
import org.flowlang.topology.ExecutionPlanCanonicalTopologyAuthority
import org.flowlang.topology.ExecutionTopologyAssessment
import org.flowlang.topology.ExecutionTopologyDecisionStatus
import org.flowlang.topology.ExecutionTopologyEvidence
import org.flowlang.topology.ExecutionTopologyEvidenceStatus
import org.flowlang.topology.ExecutionTopologyMatchingAuthority
import org.flowlang.topology.ExecutionTopologyRequirement
import org.flowlang.topology.ExecutionTopologyRequirementSource
import org.flowlang.topology.PlanningTopologyAuthority

class UnresolvedExecutionTopologyException(
    val assessment: ExecutionTopologyAssessment
) : IllegalArgumentException(
    "Execution topology does not match target '${assessment.target}': " +
        assessment.evidence.filter { it.status != ExecutionTopologyEvidenceStatus.SATISFIED }
            .joinToString { "${it.requirementId}=${it.status}" }
)

internal object ExecutionPlanTopologyValidator {
    fun validate(plan: ExecutionPlan): List<PlanningEvidenceIssue> {
        val issues = mutableListOf<PlanningEvidenceIssue>()
        issues += ExecutionPlanDerivedProjectionValidator.validate(plan)

        val duplicateIds = plan.topologyRequirements.groupingBy(ExecutionTopologyRequirement::id)
            .eachCount().filterValues { it > 1 }.keys
        duplicateIds.forEach { id ->
            issues += PlanningEvidenceIssue(
                "planning.topology.requirement.duplicate",
                "topologyRequirements.$id",
                "Topology requirement '$id' is declared more than once."
            )
        }
        plan.topologyRequirements.forEachIndexed { index, requirement ->
            if (requirement.id.isBlank()) issues += PlanningEvidenceIssue(
                "planning.topology.requirement.id.missing",
                "topologyRequirements[$index]",
                "Topology requirement id must not be blank."
            )
            if (requirement.subject.isBlank()) issues += PlanningEvidenceIssue(
                "planning.topology.requirement.subject.missing",
                "topologyRequirements[$index]",
                "Topology requirement subject must not be blank."
            )
        }

        val canonicalActual = plan.topologyRequirements.filter { it.source in canonicalSources }
        val hasIndependentCanonicalProvenance = plan.sourceIntent != null
        if (!hasIndependentCanonicalProvenance && retainsCanonicalSourceSignals(plan, canonicalActual)) {
            issues += PlanningEvidenceIssue(
                "planning.topology.canonical.provenance.missing",
                "sourceIntent",
                "Plan retains canonical topology or intent-derived source signals without the independent source provenance required to validate them."
            )
        }

        val canonicalExpected = if (hasIndependentCanonicalProvenance) {
            ExecutionPlanCanonicalTopologyAuthority.requirementsFor(plan)
        } else {
            emptyList()
        }

        if (hasIndependentCanonicalProvenance) {
            val actualCanonicalByKey = canonicalActual.associateBy(::semanticKey)
            val expectedCanonicalByKey = canonicalExpected.associateBy(::semanticKey)

            canonicalExpected.forEach { expected ->
                val actual = actualCanonicalByKey[semanticKey(expected)]
                when {
                    actual == null -> issues += PlanningEvidenceIssue(
                        "planning.topology.canonical.missing",
                        "topologyRequirements",
                        "Plan omits canonical execution topology '${expected.kind.registryKey}' for '${expected.subject}'."
                    )
                    actual != expected -> issues += PlanningEvidenceIssue(
                        "planning.topology.canonical.invalid",
                        "topologyRequirements.${actual.id}",
                        "Canonical topology evidence must match source provenance. Expected $expected, found $actual."
                    )
                }
            }
            canonicalActual.filter { semanticKey(it) !in expectedCanonicalByKey }.forEach { unexpected ->
                issues += PlanningEvidenceIssue(
                    "planning.topology.canonical.orphaned",
                    "topologyRequirements.${unexpected.id}",
                    "Plan declares canonical topology '${unexpected.kind.registryKey}' for '${unexpected.subject}' without matching source provenance."
                )
            }
        }

        val expected = PlanningTopologyAuthority.requirementsFor(
            flowName = plan.flowName,
            canonicalRequirements = canonicalExpected,
            nodes = plan.nodes,
            dependencyRelations = plan.dependencyRelations
        )
        val actualByKey = plan.topologyRequirements.associateBy(::semanticKey)
        expected.forEach { requirement ->
            val actual = actualByKey[semanticKey(requirement)]
            if (actual == null) {
                issues += PlanningEvidenceIssue(
                    "planning.topology.requirement.missing",
                    "topologyRequirements",
                    "Plan omits required execution topology '${requirement.kind.registryKey}' for '${requirement.subject}'."
                )
            }
        }
        return issues
    }

    fun assess(plan: ExecutionPlan, target: TargetCapability?): ExecutionTopologyAssessment =
        ExecutionTopologyMatchingAuthority.assess(plan.topologyRequirements, target?.topologyProfile)

    fun requireMatched(plan: ExecutionPlan, target: TargetCapability): ExecutionTopologyAssessment {
        val assessment = assess(plan, target)
        if (assessment.decision.status != ExecutionTopologyDecisionStatus.MATCHED) {
            throw UnresolvedExecutionTopologyException(assessment)
        }
        return assessment
    }

    fun blockers(plan: ExecutionPlan, target: TargetCapability?): List<ExecutionTopologyEvidence> =
        assess(plan, target).evidence.filter { it.status != ExecutionTopologyEvidenceStatus.SATISFIED }

    private fun retainsCanonicalSourceSignals(
        plan: ExecutionPlan,
        canonicalActual: List<ExecutionTopologyRequirement>
    ): Boolean {
        if (canonicalActual.isNotEmpty() || plan.loweringReport != null) return true
        return PlanDependencyRelations.flatten(plan.nodes).any { node ->
            when (node) {
                is TaskNode -> node.sourceId != null
                is ApprovalNode -> node.sourceId != null
                else -> false
            }
        }
    }

    private fun semanticKey(requirement: ExecutionTopologyRequirement): Pair<String, String> =
        requirement.kind.name to requirement.subject

    private val canonicalSources = setOf(
        ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW,
        ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY
    )
}
