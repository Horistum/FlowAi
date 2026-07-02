import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression guards for manifest HONESTY: a projected pipeline must never claim success for work
 * it does not perform. Before these fixes:
 *  - an unmapped module action (e.g. database.upsert) rendered as `echo 'Flow executes …'` — a green
 *    placebo with no side effect and no mapping note (data writes silently did not happen);
 *  - transform/validate/aggregate steps projected as silent no-ops with no mapping note;
 *  - runtime result interpolation (`${summary.total}`) rendered as literal text with no warning;
 *  - database.query ignored the system's `url` config entirely (no connection string).
 * The Generator Contract requires a mapping note whenever target syntax cannot represent a Flow
 * node faithfully; these tests pin that contract.
 */
class ManifestHonestyTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = mapOf("jenkins" to TargetCapability(target = "jenkins", description = "test"))

    private fun jenkinsManifest(flowFile: String): TargetManifest {
        val ast = FlowParser().parse(File(flowFile))
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "jenkins")
        return JenkinsManifestGenerator().generate(plan, compatibility)
    }

    private fun jenkinsManifestFromSource(src: String): TargetManifest {
        val ast = FlowParser().parse(src)
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "jenkins")
        return JenkinsManifestGenerator().generate(plan, compatibility)
    }

    private fun allSteps(manifest: TargetManifest): List<TargetStep> {
        fun flatten(s: TargetStep): List<TargetStep> = listOf(s) + s.children.flatMap { flatten(it) }
        return manifest.jobs.flatMap { it.steps }.flatMap { flatten(it) }
    }

    @Test
    fun unmappedActionFailsLoudlyInsteadOfPlaceboSuccess() {
        val steps = allSteps(jenkinsManifest("examples/api-sync.flow"))
        val upsert = steps.firstOrNull { it.module == "database" && it.action == "upsert" }
        assertNotNull(upsert, "api-sync must contain the database.upsert step")
        val run = upsert.run.orEmpty()
        assertFalse(run.contains("Flow executes"), "the green placebo echo must be gone: $run")
        assertTrue(run.contains(">&2") && run.contains("exit 1"), "an unmapped action must fail loudly (stderr + non-zero): $run")
        val note = upsert.mappingNotes.firstOrNull { it.feature == "command.unmapped" }
        assertNotNull(note, "an unmapped action must carry a command.unmapped mapping note")
        assertTrue(note.level == "error", "the unmapped-action note must be error-level, got ${note.level}")
    }

    @Test
    fun dataOperationsCarryNotMaterialisedNotes() {
        val steps = allSteps(jenkinsManifest("examples/api-sync.flow"))
        for (type in listOf("transform", "validate", "aggregate")) {
            val step = steps.firstOrNull { it.type == type }
            assertNotNull(step, "api-sync must project a $type step")
            assertTrue(
                step.mappingNotes.any { it.feature == "dataop.not-materialised" && it.level == "warning" },
                "$type must carry a dataop.not-materialised warning note; got ${step.mappingNotes}"
            )
        }
    }

    @Test
    fun runtimeResultInterpolationCarriesWarningNote() {
        val steps = allSteps(jenkinsManifest("examples/api-sync.flow"))
        val notify = steps.firstOrNull { it.module == "notify" && it.action == "send" }
        assertNotNull(notify, "api-sync must contain the notify.send step")
        val note = notify.mappingNotes.firstOrNull { it.feature == "interpolation.runtime-result" }
        assertNotNull(note, "a command interpolating step results must carry an interpolation.runtime-result note")
        assertTrue(note.message.contains("summary.total"), "the note must name the unresolved reference: ${note.message}")
    }

    @Test
    fun databaseQueryUsesSystemUrlThroughSecretMechanism() {
        val src = """
            use module "database" version "1.0"
            flow "report" {
              systems { system "wh" { type: database url: secret("WAREHOUSE_URL") } }
              steps {
                database.query wh { sql: "select 1" } -> r
              }
            }
        """.trimIndent()
        val steps = allSteps(jenkinsManifestFromSource(src))
        val query = steps.firstOrNull { it.module == "database" && it.action == "query" }
        assertNotNull(query, "the flow must contain the database.query step")
        val run = query.run.orEmpty()
        assertTrue(run.contains("psql"), "database.query must render a psql command: $run")
        assertTrue(run.contains("-d \"\$FLOW_SECRET_WAREHOUSE_URL\""), "the system url secret must be materialised as the connection string: $run")
    }

    @Test
    fun rollbackCarriesAdapterNote() {
        val registryLocal = registry
        val intent = IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
        val ast = IntentToAstPlanner(registryLocal).plan(intent)
        val plan = FlowPlanner(registryLocal).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "jenkins")
        val steps = allSteps(JenkinsManifestGenerator().generate(plan, compatibility))
        val rollback = steps.firstOrNull { it.module == "standard" && it.action == "rollback" }
        assertNotNull(rollback, "build-test-deploy must contain the standard.rollback step")
        assertTrue(
            rollback.mappingNotes.any { it.feature == "semantic.rollback-adapter" },
            "rollback must carry a note that no state is reverted without an adapter; got ${rollback.mappingNotes}"
        )
    }
}
