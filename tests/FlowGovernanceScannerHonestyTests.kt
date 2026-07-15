import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.architecture.ArchitectureGovernanceAnalyzer

class FlowGovernanceScannerHonestyTests {
    @Test
    fun targetRendererUsesReadableTargetNativeVocabulary() {
        val source = rendererSources()

        assertTrue(source.contains("withCredentials"))
        assertTrue(source.contains("credentialsId"))
        assertTrue(source.contains("secretKeyRef"))
        assertTrue(source.contains("secrets." + '$' + "name"))
        assertFalse(source.contains("private fun w(vararg codes"))
        assertFalse(source.contains("codes.map { it.toChar() }"))
    }

    @Test
    fun plannerNamesBlockedParameterDirectly() {
        val source = File("src/main/kotlin/org/flowlang/intent/IntentToAstPlanner.kt").readText()

        assertTrue(source.contains("blockedParamNames = setOf(\"command\")"))
        assertFalse(source.contains("\"com\" + \"mand\""))
    }

    @Test
    fun repositoryGovernancePassesWithReadableDiagnosticsAndTargetVocabulary() {
        val report = ArchitectureGovernanceAnalyzer(File(".")).analyze()

        assertTrue(report.status == "PASS", report.issues.joinToString { "${it.code}: ${it.path}" })
        assertFalse(report.issues.any { it.code == "ARCHITECTURE_FORBIDDEN_SYMBOL_IN_SOURCE" })
    }

    private fun rendererSources(): String = listOf(
        "src/main/kotlin/org/flowlang/generators/manifest/JenkinsManifestRenderer.kt",
        "src/main/kotlin/org/flowlang/generators/manifest/GitHubActionsManifestRenderer.kt",
        "src/main/kotlin/org/flowlang/generators/manifest/TektonManifestRenderer.kt",
        "src/main/kotlin/org/flowlang/generators/manifest/TargetProjectionRenderingSupport.kt"
    ).joinToString("\n") { path -> File(path).readText() }
}
