import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
