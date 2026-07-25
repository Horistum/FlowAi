import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.topology.ExecutionTopologyProfile

class ProviderApprovalMetadataHonestyTests {
    @Test
    fun semanticApprovalJobDoesNotClaimProviderApprovalEvidence() {
        val target = TargetCapability(
            target = "github-actions",
            description = "Approval metadata test target.",
            approvals = SupportLevel.SUPPORTED,
            features = mapOf(
                "approval.manual" to SupportLevel.SUPPORTED,
                "approval.inline" to SupportLevel.SUPPORTED
            ),
            topologyProfile = ExecutionTopologyProfile.fullySupported(
                "github-actions",
                "test:github-actions#topology"
            )
        )
        val targets = mapOf(target.target to target)
        val plan = ExecutionPlan(
            flowName = "approval-metadata",
            nodes = listOf(ApprovalNode(id = "approve"))
        )
        val manifest = BuiltInTargetProjections.pipeline(targets).generate(
            testMaterializationRequest(plan, target.target, targets)
        )
        val job = manifest.jobs.single()

        assertEquals("approval", job.metadata["semanticControl"])
        assertEquals("false", job.metadata["providerApprovalPayload"])
        assertFalse("approval" in job.metadata)
    }
}
