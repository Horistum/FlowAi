package org.flowlang.generators.manifest

import org.flowlang.controls.CanonicalControlRequirementAuthority
import org.flowlang.controls.ControlRequirement
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentPolicy
import org.flowlang.intent.IntentPolicyType
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanDependencyRelations
import org.flowlang.planner.TaskNode

/**
 * Reconstructs the canonical control-requirement source that is still provable
 * from a serialized intent-derived ExecutionPlan.
 *
 * Materialization must not rederive controls from a bag of capabilities: doing
 * so erases the authored workflow/step identity carried by OPERATION scope. The
 * lowering metadata retains workflow membership and policy declarations, while
 * TaskNode retains the authored source id and canonical capability. Combining
 * those two independent records is sufficient to re-run the canonical control
 * authority without trusting the plan's authored controlRequirements list.
 */
internal object ExecutionPlanCanonicalControlRequirementAuthority {
    data class Reconstruction(
        val requirements: List<ControlRequirement>,
        val issues: List<PlanningEvidenceIssue>
    )

    fun rederive(plan: ExecutionPlan): Reconstruction {
        val source = plan.sourceIntent
        if (source == null) {
            val capabilities = PlanDependencyRelations.flatten(plan.nodes)
                .filterIsInstance<TaskNode>()
                .mapNotNull(::canonicalCapability)
            return Reconstruction(
                requirements = CanonicalControlRequirementAuthority.requirementsForCapabilities(capabilities),
                issues = emptyList()
            )
        }

        val issues = mutableListOf<PlanningEvidenceIssue>()
        val relevantTasks = PlanDependencyRelations.flatten(plan.nodes)
            .filterIsInstance<TaskNode>()
            .mapNotNull { task ->
                val capability = canonicalCapability(task) ?: return@mapNotNull null
                if (CanonicalControlRequirementAuthority.requirementsForCapabilities(listOf(capability)).isEmpty()) {
                    return@mapNotNull null
                }
                task to capability
            }

        val duplicateSourceIds = relevantTasks
            .mapNotNull { (task, _) -> task.sourceId?.takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        duplicateSourceIds.forEach { sourceId ->
            issues += issue(
                "planning.control.source-step.duplicate",
                "sourceIntent.workflows.$sourceId",
                "Canonical control operation source id '$sourceId' is represented by more than one plan task."
            )
        }

        val operationsByWorkflow = linkedMapOf<String, MutableList<IntentStep>>()
        relevantTasks.forEach { (task, capability) ->
            val sourceId = task.sourceId?.takeIf(String::isNotBlank)
            if (sourceId == null) {
                issues += issue(
                    "planning.control.source-step.missing",
                    "nodes.${task.id}.sourceId",
                    "Canonical control capability '$capability' on node '${task.id}' has no authored source step identity."
                )
                return@forEach
            }
            if (sourceId in duplicateSourceIds) return@forEach

            val owners = source.workflows.filter { sourceId in it.stepIds }
            if (owners.size != 1) {
                issues += issue(
                    "planning.control.source-workflow.invalid",
                    "sourceIntent.workflows.$sourceId",
                    "Canonical control operation '$sourceId' must belong to exactly one preserved source workflow; found ${owners.map { it.name }.sorted()}."
                )
                return@forEach
            }
            operationsByWorkflow.getOrPut(owners.single().name) { mutableListOf() } +=
                IntentStep(id = sourceId, capability = capability)
        }

        val workflows = source.workflows.map { metadata ->
            val kind = runCatching { IntentWorkflowKind.valueOf(metadata.kind) }.getOrElse {
                issues += issue(
                    "planning.control.source-workflow-kind.invalid",
                    "sourceIntent.workflows.${metadata.name}.kind",
                    "Preserved source workflow '${metadata.name}' has unknown kind '${metadata.kind}'."
                )
                IntentWorkflowKind.CUSTOM
            }
            val operations = operationsByWorkflow[metadata.name].orEmpty().associateBy(IntentStep::id)
            IntentWorkflow(
                name = metadata.name,
                kind = kind,
                steps = metadata.stepIds.mapNotNull(operations::get)
            )
        }

        val policies = source.policies.mapNotNull { metadata ->
            val type = runCatching { IntentPolicyType.valueOf(metadata.type) }.getOrElse {
                issues += issue(
                    "planning.control.source-policy-type.invalid",
                    "sourceIntent.policies.${metadata.name}.type",
                    "Preserved source policy '${metadata.name}' has unknown type '${metadata.type}'."
                )
                return@mapNotNull null
            }
            IntentPolicy(
                name = metadata.name,
                type = type,
                condition = metadata.condition,
                message = metadata.message
            )
        }

        val reconstructed = IntentDocument(
            name = plan.flowName,
            workflows = workflows,
            policies = policies
        )
        return Reconstruction(
            requirements = CanonicalControlRequirementAuthority.requirementsFor(reconstructed),
            issues = issues
        )
    }

    private fun canonicalCapability(task: TaskNode): StandardCapability? =
        task.semanticCapability?.let { runCatching { StandardCapability.valueOf(it) }.getOrNull() }

    private fun issue(code: String, location: String, message: String) =
        PlanningEvidenceIssue(code = code, location = location, message = message)
}
