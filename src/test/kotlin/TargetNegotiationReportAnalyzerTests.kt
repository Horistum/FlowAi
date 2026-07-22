import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetNegotiationOutcome
import org.flowlang.capabilities.TargetNegotiationReportAnalyzer
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode

class TargetNegotiationReportAnalyzerTests {
    @Test
    fun capabilitySupportedTargetRemainsPreliminaryWithoutManifestEvidence() {
        val plan = planWith("task.execute", "container.image")
        val negotiation = CompatibilityAnalyzer(
            mapOf(
                "jenkins" to testTargetCapability(
                    target = "jenkins",
                    description = "Jenkins target",
                    features = mapOf("container.image" to SupportLevel.SUPPORTED)
                )
            )
        ).negotiate(plan)

        val report = TargetNegotiationReportAnalyzer.explain(negotiation)

        assertEquals("DEGRADED", report.status)
        assertTrue(report.recommendedTargets.isEmpty())
        assertFalse(report.readinessEvidenceAvailable)
        assertEquals(TargetNegotiationOutcome.DEGRADED, report.targets.single().outcome)
        assertTrue(report.warnings.any { it.contains("materialization and projection readiness") })
    }

    @Test
    fun partialTargetProducesDegradedExplanationAndWorkaround() {
        val plan = planWith("approval.manual")
        val negotiation = CompatibilityAnalyzer(
            mapOf(
                "github-actions" to testTargetCapability(
                    target = "github-actions",
                    description = "GitHub Actions target",
                    approvals = SupportLevel.PARTIAL
                )
            )
        ).negotiate(plan)

        val report = TargetNegotiationReportAnalyzer.explain(negotiation)
        val target = report.targets.single()

        assertEquals("DEGRADED", report.status)
        assertEquals(TargetNegotiationOutcome.DEGRADED, target.outcome)
        assertEquals(listOf("approval.manual"), target.degradedCapabilities)
        assertTrue(target.workaroundRecommendations.any { it.contains("approval.manual") })
        assertTrue(report.warnings.any { it.contains("github-actions") })
    }

    @Test
    fun unsupportedTargetProducesBlockedExplanationWithClearReasons() {
        val plan = planWith("kubernetes.api")
        val negotiation = CompatibilityAnalyzer(
            mapOf(
                "tekton" to testTargetCapability(
                    target = "tekton",
                    description = "Tekton target",
                    features = mapOf("kubernetes.api" to SupportLevel.UNSUPPORTED)
                )
            )
        ).negotiate(plan)

        val report = TargetNegotiationReportAnalyzer.explain(negotiation)
        val target = report.targets.single()

        assertEquals("BLOCKED", report.status)
        assertEquals(TargetNegotiationOutcome.BLOCKED, target.outcome)
        assertEquals(listOf("tekton"), report.blockedTargets)
        assertEquals(listOf("kubernetes.api"), target.unsupportedCapabilities)
        assertTrue(report.rejectionReasons.any { it.target == "tekton" && it.capability == "kubernetes.api" && it.level == SupportLevel.UNSUPPORTED })
    }

    @Test
    fun runtimeRequiredCapabilityIsReportedAsBlockingReason() {
        val plan = planWith("standard.execute")
        val negotiation = CompatibilityAnalyzer(
            mapOf(
                "portable-target" to testTargetCapability(
                    target = "portable-target",
                    description = "Portable target",
                    nativeRuntime = SupportLevel.REQUIRES_RUNTIME
                )
            )
        ).negotiate(plan)

        val report = TargetNegotiationReportAnalyzer.explain(negotiation)
        val target = report.targets.single()

        assertEquals("DEGRADED", report.status)
        assertEquals(listOf("standard.execute"), target.runtimeRequiredCapabilities)
        assertTrue(report.rejectionReasons.any { it.capability == "standard.execute" && it.level == SupportLevel.REQUIRES_RUNTIME })
    }

    private fun planWith(vararg capabilities: String): ExecutionPlan = ExecutionPlan(
        flowName = "negotiation-test",
        requiredCapabilities = capabilities.toList(),
        nodes = listOf(
            TaskNode(
                id = "task_1",
                module = "standard",
                action = "execute",
                target = "target",
                requiredCapabilities = capabilities.toList()
            )
        )
    )
}
