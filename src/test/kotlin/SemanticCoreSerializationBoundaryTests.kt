import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SemanticCoreSerializationBoundaryTests {
    @Test
    fun everySemanticCorePackageIsFreeOfJacksonAndYamlImports() {
        val root = File(".").canonicalFile
        val sourceRoot = File(root, "src/main/kotlin/org/flowlang")
        val semanticPackages = listOf(
            "ast",
            "capabilities",
            "controls",
            "effects",
            "identity",
            "intent",
            "lowering",
            "materialization",
            "modules",
            "notes",
            "planner",
            "projection",
            "safety",
            "semantic",
            "standard",
            "topology",
            "validator"
        )
        val missingDirectories = semanticPackages.filterNot { File(sourceRoot, it).isDirectory }
        assertTrue(missingDirectories.isEmpty(), "Semantic boundary references missing packages: $missingDirectories")

        val forbiddenTokens = listOf(
            "com.fasterxml.jackson",
            "org.yaml.snakeyaml",
            "YAMLFactory",
            "ObjectMapper"
        )
        val offenders = semanticPackages.flatMap { packageName ->
            File(sourceRoot, packageName).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file -> forbiddenTokens.any(file.readText()::contains) }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        }.sorted()

        assertTrue(
            offenders.isEmpty(),
            "Semantic Core packages must not depend on Jackson or YAML infrastructure: ${offenders.joinToString()}"
        )
    }
}
