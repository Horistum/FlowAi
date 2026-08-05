package org.flowlang.conformance

import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.effects.SemanticEffect
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.planner.PlanOutput
import org.flowlang.planner.TaskNode

/**
 * Builds target-neutral plans for C0.3 polarity tests.
 *
 * Alternate labels change only implementation-owned module, action and target
 * identities. Source identities, canonical effects, results and continuity are
 * deliberately stable so equivalence cannot depend on provider vocabulary.
 */
object SemanticEquivalencePlanFactory {
    fun plan(
        fixture: SemanticEquivalenceFixture,
        alternateImplementationLabels: Boolean = false
    ): ExecutionPlan {
        val labels = if (alternateImplementationLabels) {
            ImplementationLabels("alternate.module", "alternate.action", "alternate-target")
        } else {
            ImplementationLabels("reference.module", "reference.action", "reference-target")
        }
        return when (fixture) {
            SemanticEquivalenceFixture.EFFECT -> effectPlan(labels)
            SemanticEquivalenceFixture.RESULT_IDENTITY -> resultIdentityPlan(labels)
            SemanticEquivalenceFixture.RESULT_VALUE -> resultValuePlan(labels)
            SemanticEquivalenceFixture.VALUE_CONTINUITY -> continuityPlan(labels, PlanDependencyKind.VALUE, "version")
            SemanticEquivalenceFixture.WORKSPACE_CONTINUITY -> continuityPlan(labels, PlanDependencyKind.WORKSPACE, "source")
            SemanticEquivalenceFixture.STATE_CONTINUITY -> continuityPlan(labels, PlanDependencyKind.STATE, "deployment-state")
        }
    }

    private fun effectPlan(labels: ImplementationLabels): ExecutionPlan = ExecutionPlan(
        flowName = "semantic-effect",
        nodes = listOf(
            task(
                id = "effect-task",
                sourceId = "effect-source",
                labels = labels,
                semanticCapability = "DEPLOY",
                effects = listOf(
                    SemanticEffect(
                        domain = EffectDomain.INFRASTRUCTURE_STATE,
                        operation = EffectOperation.UPSERT,
                        resource = "deployment.state",
                        sourceCapability = "DEPLOY"
                    )
                )
            )
        ),
        dependencyRelations = emptyList()
    )

    private fun resultIdentityPlan(labels: ImplementationLabels): ExecutionPlan = ExecutionPlan(
        flowName = "semantic-result-identity",
        nodes = listOf(
            task(
                id = "producer",
                sourceId = "source-producer",
                labels = labels,
                semanticCapability = "BUILD",
                resultName = "build-result"
            )
        ),
        dependencyRelations = emptyList()
    )

    private fun resultValuePlan(labels: ImplementationLabels): ExecutionPlan = ExecutionPlan(
        flowName = "semantic-result-value",
        outputs = listOf(PlanOutput(name = "image", type = "container-image", sourceNodeId = "producer")),
        nodes = listOf(
            task(
                id = "producer",
                sourceId = "source-producer",
                labels = labels,
                semanticCapability = "BUILD_IMAGE",
                outputs = listOf("image")
            )
        ),
        dependencyRelations = emptyList()
    )

    private fun continuityPlan(
        labels: ImplementationLabels,
        kind: PlanDependencyKind,
        channel: String
    ): ExecutionPlan {
        val producer = task(
            id = "producer",
            sourceId = "source-producer",
            labels = labels,
            semanticCapability = "BUILD",
            outputs = listOf(channel)
        )
        val consumer = task(
            id = "consumer",
            sourceId = "source-consumer",
            labels = labels,
            semanticCapability = "DEPLOY"
        )
        return ExecutionPlan(
            flowName = "semantic-${kind.name.lowercase()}-continuity",
            nodes = listOf(producer, consumer),
            dependencyRelations = listOf(
                PlanDependencyRelation(
                    sourceNodeId = producer.id,
                    targetNodeId = consumer.id,
                    kind = kind,
                    channel = channel,
                    evidence = PlanDependencyEvidence.MODULE_CONTRACT,
                    path = listOf(producer.id, consumer.id),
                    evidenceReference = "c0.3:${kind.name.lowercase()}:$channel"
                )
            )
        )
    }

    private fun task(
        id: String,
        sourceId: String,
        labels: ImplementationLabels,
        semanticCapability: String,
        resultName: String? = null,
        outputs: List<String> = emptyList(),
        effects: List<SemanticEffect> = emptyList()
    ): TaskNode = TaskNode(
        id = id,
        module = labels.module,
        action = labels.action,
        target = labels.target,
        resultName = resultName,
        semanticCapability = semanticCapability,
        sourceId = sourceId,
        effectModel = effects,
        outputs = outputs
    )

    private data class ImplementationLabels(
        val module: String,
        val action: String,
        val target: String
    )
}
