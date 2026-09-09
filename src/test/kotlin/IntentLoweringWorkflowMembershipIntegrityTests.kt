import org.flowlang.frontend.FrontendCompilerComposition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.cli.Json
import org.flowlang.adapters.testing.PlanningEvidenceValidatorFixture as ExecutionPlanMaterializationValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.lowering.IntentLoweringAuthority
import org.flowlang.lowering.IntentLoweringReport
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner

class IntentLoweringWorkflowMembershipIntegrityTests {
    @Test
    fun lowering21CertifiesWorkflowStepMembership() {
        val plan = referencePlan()
        val source = requireNotNull(plan.sourceIntent)
        val report = requireNotNull(plan.loweringReport)
        val membership = source.fields.single { it.identity == "workflow/main/step/work" }

        assertEquals("2.1", IntentLoweringReport.CONTRACT_VERSION)
        assertEquals("2.1", report.contractVersion)
        assertEquals("$.workflows[0].steps[0].id", membership.sourcePath)
        assertEquals("plan/source-intent/workflow/main/step/work", membership.targetIdentity)
        assertEquals("workflow-step-membership", membership.valueKind)
        assertTrue(report.evidence.any { evidence ->
            evidence.sourceIdentity == membership.identity &&
                evidence.targetIdentity == membership.targetIdentity &&
                evidence.sourceDigest == membership.sourceDigest &&
                evidence.targetDigest == membership.expectedTargetDigest
        })

        val schema = Json.mapper.readTree(File("schemas/execution-plan.schema.json"))
        assertEquals(
            "2.1",
            schema.path("\$defs").path("intentLoweringReport")
                .path("properties").path("contractVersion").path("const").asText()
        )
    }

    @Test
    fun loweringReportRejectsRemovedWorkflowMembership() {
        val plan = referencePlan()
        val source = requireNotNull(plan.sourceIntent)
        val tampered = plan.copy(
            sourceIntent = source.copy(
                workflows = source.workflows.map { workflow -> workflow.copy(stepIds = emptyList()) }
            ),
            loweringReport = null
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            IntentLoweringAuthority.report(tampered)
        }

        assertTrue(
            failure.message.orEmpty().contains("plan/source-intent/workflow/main/step/work"),
            failure.message.orEmpty()
        )
    }

    @Test
    fun materializationRejectsWorkflowMembershipTamperingBeforeTrustingControlScope() {
        val plan = referencePlan()
        val source = requireNotNull(plan.sourceIntent)
        val tampered = plan.copy(
            sourceIntent = source.copy(
                workflows = source.workflows.map { workflow -> workflow.copy(stepIds = emptyList()) }
            )
        )

        val issues = ExecutionPlanMaterializationValidator.validate(tampered, ModuleRegistry())

        assertTrue(
            issues.any { it.code == "planning.lowering.target-value.mismatch" },
            issues.joinToString { "${it.code}:${it.message}" }
        )
    }

    private fun referencePlan() = FlowPlanner(ModuleRegistry()).plan(
        FrontendCompilerComposition.intentPlanner().plan(
            IntentDocument(
                name = "workflow-membership-integrity",
                workflows = listOf(
                    IntentWorkflow(
                        name = "main",
                        kind = IntentWorkflowKind.CUSTOM,
                        steps = listOf(IntentStep("work", StandardCapability.CUSTOM))
                    )
                )
            )
        )
    )
}
