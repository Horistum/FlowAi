import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Blocking JUnit bridge for additional conformance functions that are authored
 * with the historical H harness. These tests are intentionally separate from
 * FlowSpecJUnitTest so a failure names the broken layer directly.
 */
class FlowConformanceBridgeJUnitTest {
    @Test
    fun intentConformanceScenariosPass() = runHarnessGroup("Intent conformance", ::intentConformanceTests)

    @Test
    fun targetConformanceScenariosPass() = runHarnessGroup("Target conformance", ::targetConformanceTests)

    @Test
    fun betaConformanceScenariosPass() = runHarnessGroup("Beta conformance", ::betaConformanceTests)

    @Test
    fun rc4SemanticGeneratorRegressionScenariosPass() = runHarnessGroup("RC4 semantic generator regression", ::rc4SemanticGeneratorRegressionTests)

    private fun runHarnessGroup(label: String, block: () -> Unit) {
        H.reset()
        block()
        val ok = H.report()
        assertTrue(ok, "$label scenarios failed: " + H.fails.take(120).joinToString(" | "))
    }
}
