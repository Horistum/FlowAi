import java.io.File
import kotlin.test.*
import org.flowlang.cli.Json
import org.flowlang.conformance.JsonSchemaSmokeValidator
import org.flowlang.controls.*
import org.flowlang.intent.*
import org.flowlang.modules.ModuleRegistry

class IntentDecisionEvidenceBoundaryTests {
    private val analyzer = IntentDecisionAnalyzer(ModuleRegistry())
    private fun intent(vararg steps: IntentStep, policies: List<IntentPolicy> = emptyList()) = IntentDocument(
        name = "decision-evidence", workflows = listOf(IntentWorkflow("main", IntentWorkflowKind.CUSTOM, steps.toList())), policies = policies)

    @Test fun everyClosedSafetyRequirementUsesTheCanonicalAssessment() {
        SafetyRequirement.entries.forEach { requirement ->
            val document = intent(IntentStep("operation", StandardCapability.CUSTOM),
                policies = listOf(IntentPolicy("required-control", IntentPolicyType.SAFETY, requirement.normalized)))
            val canonical = CanonicalControlRequirementAuthority.assess(document)
            val report = analyzer.analyze(document)
            assertEquals(ControlDecisionStatus.BLOCKED, canonical.decision.status, requirement.name)
            assertFalse(report.validForLowering, requirement.name)
            val gate = report.safetyGates.single()
            assertEquals("blocked", gate.status, requirement.name)
            assertTrue(gate.blocksLowering)
            assertEquals(requirement.normalized, gate.policy)
        }
    }

    @Test fun concreteChangeTicketEvidenceIsNotLost() {
        val document = intent(IntentStep("operation", StandardCapability.CUSTOM,
            params = mapOf("changeTicket" to IntentString("OPS-1842"))),
            policies = listOf(IntentPolicy("ticket", IntentPolicyType.SAFETY, "requiresChangeTicket")))
        val report = analyzer.analyze(document)
        assertTrue(report.validForLowering)
        assertEquals("satisfied", report.safetyGates.single().status)
    }

    @Test fun pendingPolicyEvidenceIsNeitherSatisfiedNorExecutionPermission() {
        val document = intent(IntentStep("operation", StandardCapability.CUSTOM),
            policies = listOf(IntentPolicy("custom-rule", IntentPolicyType.SAFETY, "riskScore < 3")))
        val canonical = CanonicalControlRequirementAuthority.assess(document)
        val report = analyzer.analyze(document)
        assertEquals(ControlDecisionStatus.PENDING, canonical.decision.status)
        assertEquals("pending", report.safetyGates.single().status)
        assertFalse(report.safetyGates.single().blocksLowering)
        assertEquals("1.1", report.decisionModelVersion)
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(report),
            Json.mapper.readTree(File("schemas/intent-decision-report.schema.json")))
    }

    @Test fun unrelatedBackupCannotProduceAGreenMigrationDecision() {
        val document = intent(IntentStep("backup", StandardCapability.BACKUP),
            IntentStep("migration", StandardCapability.DATABASE_MIGRATE))
        val report = analyzer.analyze(document)
        assertFalse(report.validForLowering)
        assertTrue(report.missingDecisions.any { it.field == "safety.backup" })
        assertFalse(report.risks.single { it.id.contains("database-migration") }.mitigated)
        val scoped = document.copy(workflows = listOf(document.workflows.single().copy(steps = listOf(
            document.workflows.single().steps[0], document.workflows.single().steps[1].copy(requires = listOf("backup"))))))
        assertFalse(analyzer.analyze(scoped).missingDecisions.any { it.field == "safety.backup" })
    }

    @Test fun environmentClassificationDoesNotGuessFromArbitraryText() {
        val deploy = IntentStep("deploy", StandardCapability.DEPLOY, params = mapOf("environment" to IntentString("dev")))
        val document = intent(deploy).copy(inputs = listOf(IntentInput("applicationName", default = IntentString("prod"))))
        assertFalse(analyzer.analyze(document).safetyGates.any { it.policy == "forbidProductionWithoutApproval" })
        listOf("prod-eu", "production-east", "product", "reproducible").forEach { environment ->
            val report = analyzer.analyze(intent(deploy.copy(params = mapOf("environment" to IntentString(environment)))))
            assertFalse(report.validForLowering, environment)
            assertTrue(report.safetyGates.any { it.policy == "resolveEnvironmentClassification" && it.status == "blocked" }, environment)
            assertFalse(report.safetyGates.any { it.policy == "forbidProductionWithoutApproval" }, environment)
        }
    }

    @Test fun explicitSensitiveAliasesUseTheSamePolicyAsTheCompiler() {
        listOf("prod", "production", "live").forEach { environment ->
            val document = intent(IntentStep("deploy", StandardCapability.DEPLOY,
                params = mapOf("environment" to IntentString(environment))))
            val report = analyzer.analyze(document)
            assertFalse(report.validForLowering, environment)
            assertTrue(report.safetyGates.any { it.policy == "forbidProductionWithoutApproval" && it.blocksLowering })
        }
    }

    @Test fun partialGlobalApprovalCannotBeReportedAsSatisfied() {
        val document = intent(IntentStep("approve", StandardCapability.APPROVE),
            IntentStep("a", StandardCapability.CUSTOM, requires = listOf("approve")),
            IntentStep("b", StandardCapability.CUSTOM),
            policies = listOf(IntentPolicy("approval", IntentPolicyType.SAFETY, "requiresApproval")))
        val report = analyzer.analyze(document)
        assertFalse(report.validForLowering)
        assertEquals("blocked", report.safetyGates.single().status)
    }
}
