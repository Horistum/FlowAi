import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestContractValidator
import org.flowlang.generators.manifest.TargetMappingNote
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

class TargetManifestContractValidatorTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = mapOf(
        "jenkins" to TargetCapability(target = "jenkins", description = "test"),
        "github-actions" to TargetCapability(target = "github-actions", description = "test"),
        "tekton" to TargetCapability(target = "tekton", description = "test")
    )

    @Test
    fun generatedMainTargetManifestsSatisfyProjectionContract() {
        val ast = FlowParser().parse(File("examples/api-sync.flow"))
        val plan = FlowPlanner(registry).plan(ast)
        val manifests = listOf(
            JenkinsManifestGenerator().generate(plan, CompatibilityAnalyzer(targets).analyze(plan, "jenkins")),
            GitHubActionsManifestGenerator().generate(plan, CompatibilityAnalyzer(targets).analyze(plan, "github-actions")),
            TektonManifestGenerator().generate(plan, CompatibilityAnalyzer(targets).analyze(plan, "tekton"))
        )

        for (manifest in manifests) {
            val report = TargetManifestContractValidator.validate(manifest)
            assertTrue(report.valid, "${manifest.target} manifest must satisfy projection contract: ${report.issues}")
        }
    }

    @Test
    fun malformedManifestIsRejectedBeforeRendering() {
        val manifest = validManifest().copy(
            target = "",
            metadata = emptyMap(),
            jobs = listOf(
                TargetJob(
                    id = "Bad Id",
                    steps = listOf(
                        TargetStep(
                            id = "same",
                            type = "action",
                            module = "database",
                            action = "upsert",
                            target = "warehouse",
                            run = "echo 'Flow executes database.upsert on warehouse'"
                        ),
                        TargetStep(
                            id = "same",
                            type = "action",
                            module = "database",
                            action = "query",
                            target = "warehouse",
                            run = "psql --set ON_ERROR_STOP=1 -c 'select 1'"
                        )
                    )
                )
            )
        )

        val report = TargetManifestContractValidator.validate(manifest)
        assertFalse(report.valid, "malformed manifest must fail the projection contract")
        val codes = report.issues.map { it.code }.toSet()
        assertTrue("TARGET_BLANK" in codes, "target must be validated: $codes")
        assertTrue("MANIFEST_METADATA_MISSING" in codes, "required metadata must be validated: $codes")
        assertTrue("JOB_ID_INVALID" in codes, "job ids must be renderer-safe: $codes")
        assertTrue("STEP_ID_DUPLICATE" in codes, "duplicate step ids must be rejected: $codes")
        assertTrue("ACTION_PLACEBO_COMMAND" in codes, "green placeholder action commands must be rejected: $codes")
    }

    @Test
    fun mappingNotesHaveStrictShape() {
        val manifest = validManifest().copy(
            mappingNotes = listOf(
                TargetMappingNote(level = "debug", target = "", nodeId = "", feature = "", message = "")
            )
        )

        val report = TargetManifestContractValidator.validate(manifest)
        assertFalse(report.valid, "invalid mapping notes must fail the contract")
        val codes = report.issues.map { it.code }.toSet()
        assertTrue("MAPPING_NOTE_LEVEL_INVALID" in codes, "mapping note levels must be constrained: $codes")
        assertTrue("MAPPING_NOTE_TARGET_BLANK" in codes, "mapping note targets must be present: $codes")
        assertTrue("MAPPING_NOTE_NODE_BLANK" in codes, "mapping note node ids must be present: $codes")
        assertTrue("MAPPING_NOTE_FEATURE_BLANK" in codes, "mapping note features must be present: $codes")
        assertTrue("MAPPING_NOTE_MESSAGE_BLANK" in codes, "mapping note messages must be present: $codes")
    }

    private fun validManifest(): TargetManifest = JenkinsManifestGenerator().generate(
        FlowPlanner(registry).plan(FlowParser().parse("""
            version "1.0"
            use module "shell" version "1.0"
            flow "contract" {
              steps {
                shell.run local {
                  command: "echo ok"
                }
              }
            }
        """.trimIndent())),
        CompatibilityAnalyzer(targets).analyze(
            FlowPlanner(registry).plan(FlowParser().parse("""
                version "1.0"
                use module "shell" version "1.0"
                flow "contract" {
                  steps {
                    shell.run local {
                      command: "echo ok"
                    }
                  }
                }
            """.trimIndent())),
            "jenkins"
        )
    )
}
