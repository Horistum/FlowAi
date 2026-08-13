package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.flowlang.cli.Json
import org.flowlang.conformance.CanonicalExecutionPlanKindConformanceOracle
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
        StandardCapability.entries.forEach { capability ->
            val expected = CanonicalExecutionPlanKindConformanceOracle.expectedTaskKind(capability.name)
            val actual = CanonicalExecutionPlanSemanticsAuthority.kindForSemanticCapability(capability.name).wireValue
            assertEquals(expected, actual, "Unexpected canonical task kind for ${capability.name}")
        }
    }

    @Test
    fun implementationLabelsCannotChangeAnyCanonicalTaskClassification() {
        val cases = linkedMapOf(
            StandardCapability.ROLLBACK to "rollback",
            StandardCapability.NOTIFY to "notification",
            StandardCapability.PACKAGE to "artifact",
            StandardCapability.SECRET_ROTATE to "secret",
            StandardCapability.BUILD to "task"
        )

        cases.forEach { (semanticCapability, expectedKind) ->
            val baseline = task(
                id = "baseline-${semanticCapability.name.lowercase()}",
                semanticCapability = semanticCapability.name,
                module = "standard",
                action = "execute",
                target = "local",
                requiredCapabilities = emptyList(),
                effectResource = "ordinary.resource"
            )
            val misleading = task(
                id = "misleading-${semanticCapability.name.lowercase()}",
                semanticCapability = semanticCapability.name,
                module = "notify-artifact-secret-provider",
                action = "rollback",
                target = "artifact-store",
                requiredCapabilities = listOf("secret.inject", "artifact.publish", "rollback.execute"),
                effectResource = "artifact.secret.rollback.notification"
            )

            val kinds = ExecutionPlanCanonicalizer.canonicalize(
                ExecutionPlan(
                    flowName = "label-invariance-${semanticCapability.name.lowercase()}",
                    nodes = listOf(baseline, misleading)
                )
            ).nodes.map { it.kind }

            assertEquals(
                listOf(expectedKind, expectedKind),
                kinds,
                "Implementation metadata changed canonical kind for ${semanticCapability.name}"
            )
        }
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
    fun absentOrUnknownSemanticCapabilityRemainsGenericTaskInBothIndependentBoundaries() {
        listOf(null, "NOT_A_STANDARD_CAPABILITY").forEach { capability ->
            assertEquals(
                "task",
                CanonicalExecutionPlanKindConformanceOracle.expectedTaskKind(capability)
            )
            assertEquals(
                CanonicalPlanNodeKind.TASK,
                CanonicalExecutionPlanSemanticsAuthority.kindForSemanticCapability(capability)
            )
        }
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
