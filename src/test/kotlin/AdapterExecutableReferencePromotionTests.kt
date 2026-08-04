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
    private val authority = AdapterExecutableReferencePromotionAuthority(
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
        assertTrue(promotion.scopeIdentity.endsWith("git.checkout,docker.build"))
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
