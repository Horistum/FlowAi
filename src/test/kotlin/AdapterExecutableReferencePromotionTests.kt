import org.flowlang.adapters.continuity.BuiltInAdapterContinuityScopedSupport
import org.flowlang.distribution.reference.ReferenceAdapterEvidence
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.portfolio.AdapterExecutableReferencePromotionAuthority
import org.flowlang.adapters.portfolio.AdapterExecutableReferencePromotionLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterExecutableReferencePromotionTests {
    private val root = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
    private val authority = ReferenceAdapterEvidence.promotion(
        rootDir = root,
        targets = targets,
        projections = BuiltInTargetProjections.registry
    )

    @Test
    fun repositoryPromotionIsBoundedAndRepositoryBacked() {
        val report = authority.analyze()
        assertEquals("PASS", report.status, report.findings.joinToString { "${it.code}:${it.message}" })
        val promotion = report.promotions.single()
        assertEquals("github-actions", promotion.target)
        assertEquals("checkout-build-image", promotion.scenarioId)
        assertTrue(promotion.snapshotReference.contains("github-actions"))
        assertEquals(BuiltInAdapterContinuityScopedSupport.githubActionsCheckoutBuildWorkspace.identity, promotion.scopeIdentity)
    }

    @Test
    fun obsoleteTaskSequenceScopeCannotPromoteTheEndpointContract() {
        val document = AdapterExecutableReferencePromotionLoader.load(root)
        for (scope in listOf("git.checkout,docker.build", "UNCONDITIONAL_PRODUCER_SINGLE_SOURCE_WORKSPACE")) {
            val stale = document.copy(promotions = document.promotions.map {
                it.copy(scopeIdentity = "github-actions|ARTIFACT|artifact.shared-workspace|git.checkout|docker.build|source|$scope")
            })
            val report = authority.evaluate(stale)
            assertEquals("FAIL", report.status)
            assertTrue(report.findings.any { it.code == "ADAPTER_PROMOTION_SCOPE_UNKNOWN" })
        }
        // The active overlay still certifies the original reference scenario only.
        assertEquals(listOf("checkout-build-image"), document.promotions.map { it.scenarioId })
    }

    @Test
    fun unknownScopeCannotPromoteExecutableReference() {
        val document = AdapterExecutableReferencePromotionLoader.load(root)
        val invalid = document.copy(
            promotions = document.promotions.map { it.copy(scopeIdentity = "github-actions|unknown") }
        )
        val report = authority.evaluate(invalid)
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "ADAPTER_PROMOTION_SCOPE_UNKNOWN" })
    }
}
