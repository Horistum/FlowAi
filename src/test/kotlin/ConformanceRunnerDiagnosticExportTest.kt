import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ConformanceRunnerDiagnosticExportTest {
    @Test
    fun exportCurrentRunnerSourceForReviewedPatch() {
        val source = File("src/main/kotlin/org/flowlang/conformance/ConformanceRunner.kt")
        assertTrue(source.isFile)
        println("FLOW_CONFORMANCE_RUNNER_SOURCE_BEGIN")
        print(source.readText())
        println("FLOW_CONFORMANCE_RUNNER_SOURCE_END")
    }
}
