import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.conformance.ReferenceCorpusExecutionHarness
import org.flowlang.intent.IntentPolicyType
import org.flowlang.intent.StandardCapability
import org.flowlang.standard.FlowStandardVersions

class FlowReferenceCorpusExecutionHarnessTests {
    @Test
    fun referenceCorpusHarnessExecutesEveryScenarioThroughRealPipeline() {
        val corpus = StandardSurface.referenceIntentCorpus()
        val report = ReferenceCorpusExecutionHarness().execute(corpus)

        assertEquals("0.8.0", FlowStandardVersions.FLOW_STANDARD_VERSION)
        assertEquals("PASS", report.status, report.failures.joinToString("\n"))
        assertEquals(corpus.scenarios.size, report.scenarioCount)
        assertEquals(report.acceptedCount, report.lowerableAcceptedCount)
        assertEquals(report.blockedCount, report.blockedLoweringCount)
    }

    @Test
    fun referenceCorpusHarnessKeepsKnownRegressionsClosed() {
        val report = ReferenceCorpusExecutionHarness().execute()

        val prodWithoutApproval = report.scenarios.first { it.id == "prod-deploy-without-approval" }
        assertEquals("BLOCKED", prodWithoutApproval.actualStatus)
        assertFalse(StandardCapability.APPROVE.name in prodWithoutApproval.capabilities)
        assertFalse(IntentPolicyType.APPROVAL.name in prodWithoutApproval.policyTypes)
        assertFalse(prodWithoutApproval.lowerable)

        val rollback = report.scenarios.first { it.id == "rollback-with-verification" }
        assertEquals("ACCEPTED", rollback.actualStatus)
        assertEquals("rollback", rollback.selectedPack)
        assertFalse(StandardCapability.DEPLOY.name in rollback.capabilities)
        assertTrue(rollback.lowerable)

        val secret = report.scenarios.first { it.id == "secret-rotation-with-audit" }
        assertTrue(StandardCapability.VERIFY.name in secret.capabilities)
        assertTrue(secret.lowerable)

        val buildTestDeploy = report.scenarios.first { it.id == "portable-build-test-deploy" }
        assertEquals("deployment", buildTestDeploy.selectedPack)
        assertTrue(StandardCapability.BUILD_IMAGE.name in buildTestDeploy.capabilities)
        assertTrue(buildTestDeploy.lowerable)
    }

    @Test
    fun v070GateIsPublishedAsARequiredStandardCandidateCheck() {
        val gate = "v0.7.0.reference-corpus-execution-harness"
        val contract = StandardSurface.referenceCorpusExecutionHarness()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }

        assertEquals("PASS", contract.status)
        assertTrue(contract.assertions.any { it.id == "corpus.no-auto-approval" && it.blocking })
        assertTrue(gate in StandardReleaseProfile.report().requiredConformanceChecks)
        assertTrue(gate in StandardSurface.standardExportManifest().releaseGateChecks)
        assertTrue(gate in candidate.requiredChecks)
    }
}
