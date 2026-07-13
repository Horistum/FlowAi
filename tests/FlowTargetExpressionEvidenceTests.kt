package org.flowlang.tests

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetExpressionEvidenceKind
import org.flowlang.capabilities.TargetExpressionFeatures
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.capabilities.TargetExpressionSupportDeclaration
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode

class FlowTargetExpressionEvidenceTests {
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    @Test
    fun everyConditionCapableBuiltInTargetCarriesValidRegistryEvidence() {
        assertTrue(targets.isNotEmpty())
        targets.values.filter { it.conditions != SupportLevel.UNSUPPORTED }.forEach { target ->
            val declaration = assertNotNull(target.expressionSupport, "${target.target} must declare expression evidence")
            assertEquals(TargetExpressionEvidenceKind.TARGET_REGISTRY, declaration.evidenceKind)
            assertTrue(declaration.evidenceReference.contains("targets/builtin-targets.yaml#expressionProfiles."))
            assertEquals(null, TargetExpressionSupport.declarationValidationReason(declaration))
        }
    }

    @Test
    fun unknownOrUndeclaredTargetFailsClosed() {
        val target = TargetCapability(target = "future-target", description = "No expression notes yet")
        val decision = TargetExpressionSupport.evaluate(target, "env == 'prod'")

        assertFalse(decision.supported)
        assertTrue(decision.reason.contains("no declared expression-support evidence"))
        assertTrue(decision.missingFeatures.contains(TargetExpressionFeatures.binary("==")))
    }

    @Test
    fun genericEvidenceWorksWithoutTargetIdWhitelisting() {
        val declaration = TargetExpressionSupportDeclaration(
            profileId = "custom-equality",
            evidenceKind = TargetExpressionEvidenceKind.TARGET_NOTES,
            evidenceReference = "notes/custom-target/expression.yaml#custom-equality",
            features = setOf(
                TargetExpressionFeatures.LITERAL,
                TargetExpressionFeatures.REFERENCE,
                TargetExpressionFeatures.binary("==")
            )
        )
        val target = TargetCapability(
            target = "future-target",
            description = "Target with explicit expression notes",
            expressionSupport = declaration
        )

        val decision = TargetExpressionSupport.evaluate(target, "env == 'prod'")

        assertTrue(decision.supported, decision.reason)
        assertEquals("custom-equality", decision.profileId)
        assertEquals(TargetExpressionEvidenceKind.TARGET_NOTES, decision.evidenceKind)
    }

    @Test
    fun declaredUnavailableAdaptersBlockConditionsWithEvidence() {
        for (targetName in listOf("argo-workflows", "azure-devops")) {
            val target = targets.getValue(targetName)
            val decision = TargetExpressionSupport.evaluate(target, "env == 'prod'")
            assertFalse(decision.supported)
            assertEquals("unavailable", decision.profileId)
            assertTrue(decision.reason.contains(target.expressionSupport!!.evidenceReference))

            val report = CompatibilityAnalyzer(targets).analyze(guardedPlan("env == 'prod'"), targetName)
            assertTrue(report.hasErrors)
            assertTrue(report.issues.any { it.feature == "condition.expression" })
        }
    }

    @Test
    fun unknownTargetCompatibilityRemainsUnsupportedBeforeProjection() {
        val report = CompatibilityAnalyzer(targets).analyze(guardedPlan("env == 'prod'"), "future-target")
        assertEquals(SupportLevel.UNSUPPORTED, report.status)
        assertTrue(report.issues.any { it.feature == "target" && it.message.contains("Unknown target") })
    }

    @Test
    fun registryRejectsTargetsWithoutExpressionProfile() {
        val root = Files.createTempDirectory("flow-expression-registry").toFile()
        try {
            File(root, "target.yaml").writeText(
                """
                kind: FlowTargetRegistry
                version: "1.0"
                targets:
                  - name: unsafe-target
                    description: Missing expression evidence
                    capabilities:
                      conditions: supported
                """.trimIndent()
            )
            val error = assertFailsWith<IllegalArgumentException> {
                TargetRegistryYamlLoader.loadDirectory(root)
            }
            assertTrue(error.message.orEmpty().contains("must declare expressionProfile"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun guardedPlan(condition: String): ExecutionPlan = ExecutionPlan(
        flowName = "expression-evidence",
        nodes = listOf(
            ConditionNode(
                id = "gate",
                condition = condition,
                then = listOf(TaskNode(id = "task", module = "standard", action = "execute", target = "all"))
            )
        )
    )
}
