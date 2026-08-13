package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.File
import kotlin.test.assertFailsWith
import org.flowlang.cli.Json
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.effects.SemanticEffect
import org.flowlang.intent.StandardCapability
import org.flowlang.planner.CanonicalExecutionPlanSemanticsAuthority
import org.flowlang.planner.CanonicalPlanNodeKind
import org.flowlang.planner.ControlNode
import org.flowlang.planner.DataOpNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.planner.TaskNode

class CanonicalExecutionPlanSemanticsTests {
    @Test
    fun executionPlanSchemaUsesExactlyTheClosedCanonicalNodeKindVocabulary() {
        val schema = Json.mapper.readTree(File("schemas/execution-plan.schema.json"))
        val schemaKinds = schema.path("\$defs").path("node").path("properties").path("kind").path("enum")
            .map { it.asText() }
            .toSet()
        val typedKinds = CanonicalPlanNodeKind.entries.map { it.wireValue }.toSet()

        assertEquals(typedKinds, schemaKinds)
    }

    @Test
    fun semanticCapabilityOwnsTaskKind() {
        val cases = mapOf(
            StandardCapability.ROLLBACK to CanonicalPlanNodeKind.ROLLBACK,
            StandardCapability.NOTIFY to CanonicalPlanNodeKind.NOTIFICATION,
            StandardCapability.PACKAGE to CanonicalPlanNodeKind.ARTIFACT,
            StandardCapability.SECRET_ROTATE to CanonicalPlanNodeKind.SECRET,
            StandardCapability.BUILD to CanonicalPlanNodeKind.TASK,
            StandardCapability.DEPLOY to CanonicalPlanNodeKind.TASK
        )

        cases.forEach { (capability, expected) ->
            assertEquals(expected, CanonicalExecutionPlanSemanticsAuthority.kindForSemanticCapability(capability.name))
        }
    }

    @Test
    fun implementationLabelsCannotChangeCanonicalTaskKind() {
        val semanticCapability = StandardCapability.NOTIFY.name
        val ordinary = task(
            id = "ordinary-labels",
            semanticCapability = semanticCapability,
            module = "standard",
            action = "execute",
            target = "local",
            requiredCapabilities = emptyList(),
            effectResource = "communication.message"
        )
        val misleading = task(
            id = "misleading-labels",
            semanticCapability = semanticCapability,
            module = "artifact-rollback-secret-provider",
            action = "rollback",
            target = "docker",
            requiredCapabilities = listOf("secret.inject"),
            effectResource = "artifact.secret.rollback"
        )

        val kinds = ExecutionPlanCanonicalizer.canonicalize(
            ExecutionPlan(flowName = "label-invariance", nodes = listOf(ordinary, misleading))
        ).nodes.map { it.kind }

        assertEquals(listOf("notification", "notification"), kinds)
    }

    @Test
    fun implementationFolkloreCannotPromoteOrdinaryTask() {
        val task = task(
            id = "folklore",
            semanticCapability = StandardCapability.BUILD.name,
            module = "notify",
            action = "rollback",
            target = "artifact-store",
            requiredCapabilities = listOf("secret.read"),
            effectResource = "artifact.payload"
        )

        val canonical = ExecutionPlanCanonicalizer.canonicalize(
            ExecutionPlan(flowName = "folklore", nodes = listOf(task))
        )

        assertEquals("task", canonical.nodes.single().kind)
    }

    @Test
    fun absentOrUnknownSemanticCapabilityRemainsGenericTask() {
        assertEquals(CanonicalPlanNodeKind.TASK, CanonicalExecutionPlanSemanticsAuthority.kindForSemanticCapability(null))
        assertEquals(CanonicalPlanNodeKind.TASK, CanonicalExecutionPlanSemanticsAuthority.kindForSemanticCapability("NOT_A_STANDARD_CAPABILITY"))
    }

    @Test
    fun legacyDataAndControlKindsAreClosedAndFailOnUnknownValues() {
        val valid = ExecutionPlanCanonicalizer.canonicalize(
            ExecutionPlan(
                flowName = "closed-kinds",
                nodes = listOf(
                    DataOpNode(id = "transform", kind = "Transform"),
                    DataOpNode(id = "validate", kind = "Validate"),
                    ControlNode(id = "expect", kind = "Expect")
                )
            )
        )
        assertEquals(listOf("transform", "validate", "expect"), valid.nodes.map { it.kind })

        assertFailsWith<IllegalStateException> {
            ExecutionPlanCanonicalizer.canonicalize(
                ExecutionPlan(flowName = "bad-data-kind", nodes = listOf(DataOpNode(id = "bad", kind = "ArtifactByName")))
            )
        }
        assertFailsWith<IllegalStateException> {
            ExecutionPlanCanonicalizer.canonicalize(
                ExecutionPlan(flowName = "bad-control-kind", nodes = listOf(ControlNode(id = "bad", kind = "NotifyByName")))
            )
        }
    }

    private fun task(
        id: String,
        semanticCapability: String?,
        module: String,
        action: String,
        target: String,
        requiredCapabilities: List<String>,
        effectResource: String
    ): TaskNode = TaskNode(
        id = id,
        module = module,
        action = action,
        target = target,
        semanticCapability = semanticCapability,
        requiredCapabilities = requiredCapabilities,
        effectModel = listOf(
            SemanticEffect(
                domain = EffectDomain.SOFTWARE_DELIVERY,
                operation = EffectOperation.EXECUTE,
                resource = effectResource,
                sourceCapability = semanticCapability
            )
        )
    )
}
