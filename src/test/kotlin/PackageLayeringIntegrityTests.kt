import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.core.SemanticCorePackageBoundary

class PackageLayeringIntegrityTests {
    private val sourceRoot = File("src/main/kotlin/org/flowlang")

    @Test
    fun standardLayerDoesNotDependOnScenarioArtifactOrConformanceImplementations() {
        assertNoImports(
            packageName = "standard",
            forbiddenPrefixes = listOf(
                "org.flowlang.scenarios",
                "org.flowlang.artifacts",
                "org.flowlang.conformance"
            )
        )
    }

    @Test
    fun artifactContractsDoNotDependOnConformanceImplementationPackage() {
        assertNoImports(
            packageName = "artifacts",
            forbiddenPrefixes = listOf("org.flowlang.conformance")
        )
    }

    @Test
    fun productionConformanceUsesTheCanonicalSemanticPackageBoundary() {
        val source = File(sourceRoot, "conformance/StandardArchitectureNormalizationChecks.kt").readText()
        val packageBlock = requireNotNull(
            Regex(
                "val semanticPackages = listOf\\((.*?)\\)\\s*val missingPackages",
                RegexOption.DOT_MATCHES_ALL
            ).find(source)
        ) { "Production conformance no longer exposes its semanticPackages inventory." }
        val tokenBlock = requireNotNull(
            Regex(
                "val forbiddenTokens = listOf\\((.*?)\\)\\s*val offenders",
                RegexOption.DOT_MATCHES_ALL
            ).find(source)
        ) { "Production conformance no longer exposes its serialization token inventory." }

        assertEquals(SemanticCorePackageBoundary.packages, quotedValues(packageBlock.groupValues[1]))
        assertEquals(SemanticCorePackageBoundary.forbiddenSerializationTokens, quotedValues(tokenBlock.groupValues[1]))
    }

    @Test
    fun stableScenarioQualityEvidencePathDoesNotRestoreACompatibilityFacade() {
        val marker = File(sourceRoot, "standard/ScenarioPackQualityAnalyzer.kt")
        val text = marker.readText()
        assertTrue(text.contains("org.flowlang.conformance.ScenarioPackQualityAnalyzer"))
        assertFalse(text.lineSequence().any { it.trim().startsWith("import ") })
        assertFalse(Regex("""\b(class|object|interface|typealias|fun|val|var)\b""").containsMatchIn(codeWithoutComments(text)))
    }

    @Test
    fun scenarioNormalizationContainsNoTargetSupportVerdicts() {
        val file = File(sourceRoot, "scenarios/ScenarioPacks.kt")
        val text = file.readText()
        listOf(
            "likely-full",
            "depends-on-environment-gates-and-runtime-workarounds",
            "check-approval-and-rollback-capabilities"
        ).forEach { inventedVerdict ->
            assertFalse(text.contains(inventedVerdict), "Scenario normalization still publishes invented target verdict '$inventedVerdict'.")
        }
    }

    @Test
    fun genericContainerRegistryIsNeverRewrittenToDockerInSemanticSources() {
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                text.contains("containerRegistry") &&
                    (text.contains("\"containerRegistry\" -> \"docker\"") ||
                        text.contains("\"dockerRegistry\", \"containerRegistry\" -> \"docker\""))
            }
            .map { it.relativeTo(File(".")).path }
            .toList()

        assertTrue(offenders.isEmpty(), "Generic containerRegistry is still coerced to Docker in: ${offenders.joinToString()}.")
    }

    private fun assertNoImports(packageName: String, forbiddenPrefixes: List<String>) {
        val directory = File(sourceRoot, packageName)
        require(directory.isDirectory) { "Missing source package directory: ${directory.path}" }
        val offenders = directory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val imports = file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import ") }
                    .map { it.removePrefix("import ").substringBefore(" as ") }
                val forbidden = imports.filter { imported ->
                    forbiddenPrefixes.any { prefix -> imported == prefix || imported.startsWith("$prefix.") }
                }
                forbidden.takeIf { it.isNotEmpty() }?.let {
                    file.relativeTo(File(".")).path to it
                }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            offenders.joinToString("\n") { (file, imports) -> "$file imports ${imports.joinToString()}" }
        )
    }

    private fun quotedValues(value: String): List<String> =
        value.split(',')
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() }

    private fun codeWithoutComments(text: String): String =
        text.replace(Regex("""(?s)/\*.*?\*/"""), "")
            .lineSequence()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
}
