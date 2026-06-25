import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.standard.CoreContractCheck

class CoreContractCheckTests {
    @Test
    fun currentModelPassesCoreContractCheck() {
        val report = CoreContractCheck.report()

        assertEquals("PASS", report.status, report.issues.joinToString())
        assertTrue(report.requiredArtifacts.all { it in report.stableArtifacts })
        assertTrue(report.candidateChecks.isNotEmpty())
    }
}
