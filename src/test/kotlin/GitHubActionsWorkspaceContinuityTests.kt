import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.continuity.AdapterContinuityScopedSupportIntegrityAuthority
import org.flowlang.adapters.continuity.BuiltInAdapterContinuityScopedSupport

class GitHubActionsWorkspaceContinuityTests {
    @Test
    fun boundedScopeIsExactAndRepositoryBacked() {
        val scope = BuiltInAdapterContinuityScopedSupport.githubActionsCheckoutBuildWorkspace
        assertEquals("github-actions", scope.target)
        assertEquals("git.checkout", scope.sourceAction)
        assertEquals("docker.build", scope.targetAction)
        assertEquals("source", scope.channel)

        val report = AdapterContinuityScopedSupportIntegrityAuthority(File(".")).analyze()
        assertEquals("PASS", report.status, report.findings.joinToString { "${it.code}:${it.message}" })
        assertEquals(1, report.declarationCount)
        assertTrue(scope.evidenceReferences.any { it.startsWith("src/main/") })
        assertTrue(scope.evidenceReferences.any { it.startsWith("src/test/") })
    }
}
