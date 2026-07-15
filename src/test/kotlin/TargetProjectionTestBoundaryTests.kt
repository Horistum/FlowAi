import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetProjectionTestBoundaryTests {
    @Test
    fun testsImportConcreteProjectionsOnlyFromTheEdgePackage() {
        val testSources = testSourceFiles()
        val concreteProjectionTypes = listOf(
            "JenkinsManifestGenerator",
            "JenkinsManifestRenderer",
            "GitHubActionsManifestGenerator",
            "GitHubActionsManifestRenderer",
            "TektonManifestGenerator",
            "TektonManifestRenderer",
            "TargetExpressionTranslator",
            "TargetExpressionTranslationException"
        )
        val forbiddenImports = concreteProjectionTypes.map { name ->
            "import org.flowlang.generators.manifest.$name"
        }

        val offenders = testSources.flatMap { file ->
            val source = file.readText()
            forbiddenImports.filter(source::contains).map { token -> "${file.path}: $token" }
        }

        assertTrue(offenders.isEmpty(), "Concrete target test imports must remain at the edge: ${offenders.joinToString()}")
        assertTrue(
            testSources.any { it.readText().contains("import org.flowlang.targets.builtin.JenkinsManifestGenerator") },
            "The boundary check must observe real edge projection imports."
        )

        val wildcardScenarioSource = File("tests/FlowSpecPlannerScenarioTests.kt").readText()
        concreteProjectionTypes.take(6).forEach { name ->
            assertTrue(
                wildcardScenarioSource.contains("import org.flowlang.targets.builtin.$name"),
                "The wildcard planner scenario must explicitly import edge projection type '$name'."
            )
        }
    }

    @Test
    fun testsAndCoreUseOnlyInjectedManifestPipelineInstances() {
        val testSources = testSourceFiles()
        val staticCallOffenders = testSources.filter {
            it.readText().contains("TargetManifestGenerationPipeline.generate(")
        }
        val pipelineSource = File(
            "src/main/kotlin/org/flowlang/generators/manifest/TargetProjectionProvider.kt"
        ).readText()
        val pipelineDeclaration = pipelineSource.substringAfter("class TargetManifestGenerationPipeline(")

        assertTrue(staticCallOffenders.isEmpty(), "Static manifest generation must not return: ${staticCallOffenders.map { it.path }}")
        assertFalse(
            Regex("\\bcompanion\\s+object\\b").containsMatchIn(pipelineDeclaration),
            "Core pipeline must not expose a companion generate delegate."
        )
        assertFalse(File("src/main/kotlin/org/flowlang/conformance/LegacyProjectionCompileBridge.kt").exists())
        assertFalse(File(".github/workflows/v0963-test-import-migration.yml").exists())
        assertFalse(File(".github/workflows/v0963-wildcard-test-import-migration.yml").exists())
    }

    private fun testSourceFiles(): List<File> = listOf(
        File("src/test/kotlin"),
        File("tests")
    ).flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
