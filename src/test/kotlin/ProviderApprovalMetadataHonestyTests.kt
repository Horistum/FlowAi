import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.targets.builtin.GitHubActionsManifestGenerator

class ProviderApprovalMetadataHonestyTests {
    @Test
    fun semanticApprovalJobDoesNotClaimProviderApprovalEvidence() {
        val manifest = GitHubActionsManifestGenerator().generate(
            ExecutionPlan(
                flowName = "approval-metadata",
                nodes = listOf(ApprovalNode(id = "approve"))
            ),
            CompatibilityReport(
                target = "github-actions",
                status = SupportLevel.SUPPORTED,
                capabilityStatus = SupportLevel.SUPPORTED
            )
        )
        val job = manifest.jobs.single()

        assertEquals("approval", job.metadata["semanticControl"])
        assertEquals("false", job.metadata["providerApprovalPayload"])
        assertFalse("approval" in job.metadata)
    }
}
