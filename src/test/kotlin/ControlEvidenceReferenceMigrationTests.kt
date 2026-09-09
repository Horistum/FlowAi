import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.control.AdapterControlMaterializationLoader
import org.flowlang.conformance.AdapterProfileEvidenceAuthority
import org.flowlang.conformance.AdapterProfileSourceManifestLoader

/** Pins the reviewed source relocation without changing any frozen control claim. */
class ControlEvidenceReferenceMigrationTests {
    @Test
    fun controlRepinContainsOnlyReviewedImplementationReferenceMoves() {
        val source = File(AdapterControlMaterializationLoader.PATH).readText(Charsets.UTF_8)
        val pin = AdapterProfileSourceManifestLoader.load().sources.single { it.id == "controls" }
        assertEquals(AdapterControlMaterializationLoader.PATH, pin.path)
        assertEquals("1.0", pin.documentVersion)
        assertEquals(CURRENT_DIGEST, pin.sha256)
        assertEquals(CURRENT_DIGEST, sha256(source))
        var restored = source
        MOVES.forEach { (historical, current) ->
            assertFalse(historical in source, historical)
            assertTrue(current in source, current)
            assertTrue(File(current.substringBefore('#')).isFile(), current)
            restored = restored.replace(current, historical)
        }
        // The inverse mapping must recover every original byte, not just equivalent YAML.
        assertEquals(HISTORICAL_DIGEST, sha256(restored))
    }

    @Test
    fun controlsRemainRawBytePinnedAfterReviewedRelocation() {
        val root = createTempDirectory("control-reference-migration-").toFile()
        try {
            listOf("adapters", "conformance", "modules", "targets", "docs", "src", "tests", "standard", ".flow-agent")
                .forEach { directory ->
                    assertTrue(File(directory).copyRecursively(File(root, directory)), "Cannot copy $directory")
                }
            val original = AdapterProfileEvidenceAuthority(root).analyze()
            assertEquals("PASS", original.status, original.report?.findings?.joinToString(" | "))
            File(root, AdapterControlMaterializationLoader.PATH).appendText("\n# unreviewed change\n", Charsets.UTF_8)
            val changed = AdapterProfileEvidenceAuthority(root).analyze()
            assertEquals("FAIL", changed.status)
            assertEquals(1, changed.sourceErrors.size, changed.sourceErrors.joinToString(" | "))
            assertTrue(changed.sourceErrors.single().contains("frozen source 'controls' digest changed"))
        } finally {
            check(root.deleteRecursively()) { "Cannot remove control migration fixture." }
        }
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    companion object {
        private const val HISTORICAL_DIGEST = "ae3314dbbba94715afc7d30d162b549918854991b1d3ef1f726f5b95b62b9e3c"
        private const val CURRENT_DIGEST = "f19771b522f919035bc09d6a144c41eba61a91e7c3964d6f4fe5859da67c43a0"
        private val MOVES = linkedMapOf(
            "src/main/kotlin/org/flowlang/targets/builtin/BuiltInNativeProjectionCatalogs.kt#approval.manual.input" to "src/main/kotlin/org/flowlang/targets/builtin/JenkinsNativeProjectionCatalog.kt#approval.manual.input",
            "src/main/kotlin/org/flowlang/targets/builtin/BuiltInTargetProjections.kt#toJenkinsTargetSteps" to "src/main/kotlin/org/flowlang/generators/manifest/AdapterWorkflowProjectionLowering.kt#nodePreservingSteps",
            "src/main/kotlin/org/flowlang/targets/builtin/BuiltInNativeProjectionCatalogs.kt#githubActions" to "src/main/kotlin/org/flowlang/targets/builtin/GitHubActionsNativeProjectionCatalog.kt#catalog",
            "src/main/kotlin/org/flowlang/targets/builtin/BuiltInTargetProjections.kt#RetryGroupNode" to "src/main/kotlin/org/flowlang/generators/manifest/AdapterWorkflowProjectionLowering.kt#RetryGroupNode",
            "src/main/kotlin/org/flowlang/targets/builtin/BuiltInTargetProjections.kt#TryPlanNode" to "src/main/kotlin/org/flowlang/generators/manifest/AdapterWorkflowProjectionLowering.kt#TryPlanNode",
            "src/main/kotlin/org/flowlang/targets/builtin/BuiltInNativeProjectionCatalogs.kt#tekton" to "src/main/kotlin/org/flowlang/targets/builtin/TektonNativeProjectionCatalog.kt#catalog",
            "src/main/kotlin/org/flowlang/targets/builtin/BuiltInTargetProjections.kt#TektonManifestGenerator" to "src/main/kotlin/org/flowlang/targets/builtin/TektonManifestGenerator.kt#TektonManifestGenerator",
            "src/main/kotlin/org/flowlang/targets/builtin/BuiltInTargetProjections.kt#registry" to "src/main/kotlin/org/flowlang/distribution/reference/ReferenceTargetProjections.kt#registry"
        )
    }
}
