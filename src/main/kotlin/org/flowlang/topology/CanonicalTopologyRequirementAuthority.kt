package org.flowlang.topology

import org.flowlang.intent.IntentDocument
import org.flowlang.intent.StandardCapability

/** Derives target-neutral topology requirements before implementation binding. */
object CanonicalTopologyRequirementAuthority {
    fun requirementsFor(intent: IntentDocument): List<ExecutionTopologyRequirement> {
        val requirements = buildList {
            intent.workflows.filter { it.steps.isNotEmpty() }.forEach { workflow ->
                add(requirement(
                    kind = ExecutionTopologyKind.WORKFLOW_SCOPE,
                    subject = workflow.name,
                    source = ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW,
                    reference = "intent.workflows.${workflow.name}"
                ))
                add(requirement(
                    kind = ExecutionTopologyKind.WORKFLOW_LIFETIME,
                    subject = workflow.name,
                    source = ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW,
                    reference = "intent.workflows.${workflow.name}"
                ))
                workflow.steps.filter { it.capability == StandardCapability.APPROVE }.forEach { step ->
                    add(requirement(
                        kind = ExecutionTopologyKind.SUSPEND_RESUME,
                        subject = step.id,
                        source = ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY,
                        reference = "intent.workflows.${workflow.name}.steps.${step.id}"
                    ))
                }
            }
            if (intent.failure.notify || intent.failure.rollback) {
                add(requirement(
                    kind = ExecutionTopologyKind.FAILURE_PROPAGATION,
                    subject = intent.name,
                    source = ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW,
                    reference = "intent.failure"
                ))
            }
        }
        return TopologyRequirementIdentityAuthority.assign(requirements)
    }

    private fun requirement(
        kind: ExecutionTopologyKind,
        subject: String,
        source: ExecutionTopologyRequirementSource,
        reference: String
    ) = ExecutionTopologyRequirement(
        id = TopologyRequirementIdentityAuthority.baseId(kind, subject),
        kind = kind,
        subject = subject,
        source = source,
        evidenceReference = reference
    )
}
