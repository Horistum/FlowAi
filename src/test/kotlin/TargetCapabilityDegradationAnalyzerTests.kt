import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetCapabilityDegradationAnalyzer
import org.flowlang.generators.manifest.TargetCapabilityDegradationStatus
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMappingNote
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

class TargetCapabilityDegradationAnalyzerTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = mapOf(
        "jenkins" to TargetCapability(target = "jenkins", description = "test"),
        "github-actions" to TargetCapability(target = "github-actions", description = "test"),
        "tekton" to TargetCapability(target = "tekton", description = "test")
    )

    @Test
    fun supportedManifestHasSupportedReport() {
        val report = TargetCapabilityDegradationAnalyzer.analyze(jenkinsManifest(simpleFlow()))

        assertEquals(TargetCapabilityDegradationStatus.SUPPORTED, report.status)
        assertTrue(report.valid, "supported manifests must be valid in standard mode")
        assertTrue(report.entries.isEmpty(), "fully supported simple projection should not carry degradation entries: ${report.entries}")
    }

    @Test
    fun warningMappingNoteIsDegradedButAllowedOutsideStrictMode() {
        val manifest = jenkinsManifest(simpleFlow()).copy(
            mappingNotes = listOf(
                TargetMappingNote("warning", "jenkins", "deploy", "condition.partial", "Condition is projected with target review required.")
            )
        )
        val report = TargetCapabilityDegradationAnalyzer.analyze(manifest)

        assertEquals(TargetCapabilityDegradationStatus.DEGRADED, report.status)
        assertTrue(report.valid, "degraded reports remain reviewable outside strict mode")
        assertEquals(1, report.degradedEntries.size)
        assertTrue(report.degradedEntries.single().preserved.isNotBlank())
        assertTrue(report.degradedEntries.single().approximated.isNotBlank())
        assertTrue(report.degradedEntries.single().blocked.contains("Strict mode blocks"))
    }

    @Test
    fun strictModeBlocksDegradedSemanticsBeforeRendering() {
        val manifest = jenkinsManifest(simpleFlow()).copy(
            mappingNotes = listOf(
                TargetMappingNote("warning", "jenkins", "loop", "loop.partial", "Loop body is projected, but native per-item behavior requires review.")
            )
        )
        val report = TargetCapabilityDegradationAnalyzer.analyze(manifest, strictMode = true)

        assertEquals(TargetCapabilityDegradationStatus.DEGRADED, report.status)
        assertFalse(report.valid, "strict mode must reject degraded semantics")
        val ex = assertFailsWith<IllegalStateException> {
            TargetCapabilityDegradationAnalyzer.requireAcceptable(manifest, strictMode = true)
        }
        assertTrue(ex.message!!.contains("DEGRADED loop.partial"))
    }

    @Test
    fun errorMappingNoteIsBlockedInStandardAndStrictModes() {
        val manifest = jenkinsManifest(simpleFlow()).copy(
            mappingNotes = listOf(
                TargetMappingNote("error", "jenkins", "condition", "condition.unsupported", "Condition cannot be represented safely by this target.")
            )
        )
        val standard = TargetCapabilityDegradationAnalyzer.analyze(manifest)
        val strict = TargetCapabilityDegradationAnalyzer.analyze(manifest, strictMode = true)

        assertEquals(TargetCapabilityDegradationStatus.BLOCKED, standard.status)
        assertFalse(standard.valid, "blocked semantics must fail in standard mode")
        assertFalse(strict.valid, "blocked semantics must fail in strict mode")
        assertEquals(1, standard.blockedEntries.size)
        assertTrue(standard.blockedEntries.single().blocked.contains("Standard and strict modes block"))
    }

    @Test
    fun partialStepMetadataProducesDegradedEntry() {
        val manifest = jenkinsManifest(simpleFlow()).copy(
            jobs = listOf(
                TargetJob(
                    id = "loop_job",
                    name = "loop job",
                    steps = listOf(
                        TargetStep(
                            id = "loop_1",
                            name = "loop_1",
                            type = "loop",
                            children = listOf(TargetStep(id = "echo", name = "echo", type = "action", module = "shell", action = "run", target = "local", run = "echo ok")),
                            metadata = mapOf("supportLevel" to "partial")
                        )
                    )
                )
            )
        )
        val report = TargetCapabilityDegradationAnalyzer.analyze(manifest)

        assertEquals(TargetCapabilityDegradationStatus.DEGRADED, report.status)
        assertTrue(report.valid)
        assertTrue(report.degradedEntries.any { it.nodeId == "loop_1" && it.feature == "loop.partial" }, "partial loop metadata must be reported: ${report.entries}")
    }

    @Test
    fun generatedPartialTektonManifestIsDegradedAndReviewable() {
        val manifest = tektonManifest(simpleFlow()).copy(metadata = mapOf("supportLevel" to "partial"))
        val report = TargetCapabilityDegradationAnalyzer.analyze(manifest)

        assertEquals(TargetCapabilityDegradationStatus.DEGRADED, report.status)
        assertTrue(report.valid, "partial target support remains reviewable outside strict mode")
        assertTrue(report.degradedEntries.any { it.feature == "target.partial" })
    }

    private fun simpleFlow(): String = """
        version "1.0"
        use module "shell" version "1.0"
        flow "capability_degradation" {
          steps {
            shell.run local {
              command: "echo ok"
            }
          }
        }
    """.trimIndent()

    private fun jenkinsManifest(source: String): TargetManifest {
        val ast = FlowParser().parse(source)
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "jenkins")
        return JenkinsManifestGenerator().generate(plan, compatibility)
    }

    private fun tektonManifest(source: String): TargetManifest {
        val ast = FlowParser().parse(source)
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "tekton")
        return TektonManifestGenerator().generate(plan, compatibility)
    }
}
