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

    @Test
    fun concreteRenderersDoNotReturnToCoreManifestPackage() {
        listOf(
            "JenkinsManifestRenderer.kt",
            "GitHubActionsManifestRenderer.kt",
            "TektonManifestRenderer.kt",
            "TargetProjectionRenderingSupport.kt"
        ).forEach { name ->
            assertFalse(
                File("src/main/kotlin/org/flowlang/generators/manifest/$name").exists(),
                "$name must remain outside the Core manifest package."
            )
        }
    }

    private fun rendererSources(): String = listOf(
        "src/main/kotlin/org/flowlang/targets/builtin/JenkinsManifestRenderer.kt",
        "src/main/kotlin/org/flowlang/targets/builtin/GitHubActionsManifestRenderer.kt",
        "src/main/kotlin/org/flowlang/targets/builtin/TektonManifestRenderer.kt",
        "src/main/kotlin/org/flowlang/targets/builtin/TargetProjectionRenderingSupport.kt"
    ).joinToString("\n") { path -> File(path).readText() }
}
