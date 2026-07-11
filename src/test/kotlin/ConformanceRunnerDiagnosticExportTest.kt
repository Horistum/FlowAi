import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ConformanceRunnerDiagnosticExportTest {
    @Test
    fun exportCurrentRunnerSourceForReviewedPatch() {
        val source = File("src/main/kotlin/org/flowlang/conformance/ConformanceRunner.kt")
        val target = File("build/test-results/test/diagnostics/ConformanceRunner.kt")
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
        assertTrue(target.isFile && target.length() == source.length())
    }
}
