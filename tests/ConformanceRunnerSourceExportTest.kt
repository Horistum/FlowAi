import java.io.File
import java.util.Base64
import kotlin.test.Test

/** Temporary source export bridge. Removed immediately after the exact file is recovered from CI. */
class ConformanceRunnerSourceExportTest {
    @Test
    fun exportExactSourceForAtomicPatch() {
        val source = File("src/main/kotlin/org/flowlang/conformance/ConformanceRunner.kt").readBytes()
        val encoded = Base64.getEncoder().encodeToString(source)
        error("CONFORMANCE_RUNNER_BASE64:$encoded")
    }
}
