import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetRenderBlockedException
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode

private data class LegacyProjectionNegativeFixture(
    val fixtureVersion: String = "",
    val kind: String = "",
    val classification: String = "",
    val compiled: Boolean = true,
    val purpose: String = "",
    val cases: List<LegacyProjectionCase> = emptyList()
)

private data class LegacyProjectionCase(
    val id: String = "",
    val module: String = "",
    val action: String = "",
    val expectedMaterialization: String = "",
    val expectedRenderMode: String = ""
)

class LegacyShellGeneratorFixtureTests {
    private val fixtureFile = File("conformance/negative-fixtures/legacy-jenkins-shell-projections.yaml")
    private val fixture = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .readValue(fixtureFile, LegacyProjectionNegativeFixture::class.java)

    @Test
    fun legacyJenkinsGeneratorIsAbsentFromActiveKotlinSourceSets() {
        val activeRoots = listOf(File("src/main/kotlin"), File("src/test/kotlin"), File("tests"))
        val offenders = activeRoots
            .filter { it.exists() }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { it.nameWithoutExtension == "JenkinsGenerator" }
            .map { it.invariantSeparatorsPath }

        assertTrue(
            offenders.isEmpty(),
            "Legacy JenkinsGenerator must not be compiled from active Kotlin source sets: $offenders"
        )
    }

    @Test
    fun archivedFixtureIsExplicitlyNegativeAndNonCompiled() {
        assertTrue(fixtureFile.isFile)
        assertEquals("1.0", fixture.fixtureVersion)
        assertEquals("LegacyProjectionNegativeFixture", fixture.kind)
        assertEquals("ARCHIVED_NEGATIVE", fixture.classification)
        assertFalse(fixture.compiled)
        assertTrue(fixture.purpose.contains("canonical materialization resolver"))
        assertTrue(fixture.cases.isNotEmpty())
        assertEquals(fixture.cases.size, fixture.cases.map { it.id }.toSet().size, "Fixture case ids must be unique.")
        assertTrue(
            fixture.cases.map { it.module }.toSet().containsAll(setOf("shell", "docker", "helm", "argocd", "kubernetes")),
            "Archived fixture must cover every concrete legacy projection family named by the repair scope."
        )
    }

    @Test
    fun archivedCasesUseCanonicalMaterializationAndRenderPolicy() {
        fixture.cases.forEach { case ->
            val manifest = JenkinsManifestGenerator().generate(
                ExecutionPlan(
                    flowName = "legacy-negative-${case.id}",
                    nodes = listOf(
                        TaskNode(
                            id = case.id,
                            module = case.module,
                            action = case.action,
                            target = "fixture"
                        )
                    )
                ),
                CompatibilityReport(target = "jenkins", status = SupportLevel.SUPPORTED)
            )
            val step = manifest.jobs.single().steps.single()
            val expectedMaterialization = TargetMaterializationStatus.valueOf(case.expectedMaterialization)
            val expectedRenderMode = TargetRenderMode.valueOf(case.expectedRenderMode)

            assertEquals(expectedMaterialization, step.materialization.status, case.id)
            assertNull(step.run, "${case.id} must not carry legacy executable text")

            val readiness = TargetRenderPolicy.evaluate(manifest)
            assertEquals(expectedRenderMode, readiness.mode, case.id)

            when (expectedRenderMode) {
                TargetRenderMode.FAIL_FAST -> {
                    val failure = assertFailsWith<TargetRenderBlockedException>(case.id) {
                        JenkinsManifestRenderer().render(manifest)
                    }
                    assertEquals(TargetRenderMode.FAIL_FAST, failure.readiness.mode, case.id)
                }
                TargetRenderMode.REVIEW_ONLY -> {
                    val rendered = JenkinsManifestRenderer().render(manifest)
                    assertTrue(rendered.contains("kind: TargetProjectionReview"), case.id)
                    assertTrue(rendered.contains("renderMode: REVIEW_ONLY"), case.id)
                    assertTrue(rendered.contains("executable: false"), case.id)
                    assertFalse(rendered.contains("pipeline {"), case.id)
                    assertFalse(rendered.contains("agent any"), case.id)
                }
                TargetRenderMode.EXECUTABLE -> error("Archived legacy case '${case.id}' must never claim executable readiness.")
            }
        }
    }
}
