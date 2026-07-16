import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManifestHonestyTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

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
    fun databaseWriteRequiresAdapterMaterialization() {
        val steps = allSteps(jenkinsManifest("examples/api-sync.flow"))
        val upsert = steps.firstOrNull { it.module == "database" && it.action == "upsert" }
        assertNotNull(upsert, "api-sync must contain the database.upsert step")
        assertTrue(upsert.materialization.status == TargetMaterializationStatus.ADAPTER_REQUIRED, "database.upsert must require notes-driven materialization: ${upsert.materialization}")
        val note = upsert.mappingNotes.firstOrNull { it.feature == "materialization.adapter-required" }
        assertNotNull(note, "database.upsert must carry a materialization.adapter-required note")
        assertTrue(note.level == "warning", "adapter-required note must be warning-level, got ${note.level}")
    }

    @Test
    fun dataOperationsAreSemanticOnlyUntilMaterializedByNotes() {
        val steps = allSteps(jenkinsManifest("examples/api-sync.flow"))
        for (type in listOf("transform", "validate", "aggregate")) {
            val step = steps.firstOrNull { it.type == type }
            assertNotNull(step, "api-sync must project a $type step")
            assertTrue(step.mappingNotes.any { it.feature == "dataop.not-materialised" && it.level == "warning" }, "$type must carry a dataop.not-materialised warning note; got ${step.mappingNotes}")
            assertTrue(step.materialization.status == TargetMaterializationStatus.SEMANTIC_ONLY, "$type must be semantic-only until notes-driven materialization exists: ${step.materialization}")
        }
    }

    @Test
    fun notificationRequiresAdapterMaterialization() {
        val steps = allSteps(jenkinsManifest("examples/api-sync.flow"))
        val notify = steps.firstOrNull { it.module == "notify" && it.action == "send" }
        assertNotNull(notify, "api-sync must contain the notify.send step")
        assertTrue(notify.materialization.status == TargetMaterializationStatus.ADAPTER_REQUIRED, "notify.send must require notes-driven materialization: ${notify.materialization}")
        assertTrue(notify.mappingNotes.any { it.feature == "materialization.adapter-required" }, "notify.send must carry an adapter-required materialization note; got ${notify.mappingNotes}")
    }

    @Test
    fun databaseReadRequiresAdapterMaterialization() {
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
        assertTrue(query.materialization.status == TargetMaterializationStatus.ADAPTER_REQUIRED, "database.query must require notes-driven materialization: ${query.materialization}")
    }

    @Test
    fun rollbackIsNotesProjectedOnlyAfterConnectedMaterializationEvidenceExists() {
        val intent = IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
        val ast = IntentToAstPlanner(registry).plan(intent)
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "jenkins")
        val steps = allSteps(JenkinsManifestGenerator().generate(plan, compatibility))
        val rollback = steps.firstOrNull { it.module == "standard" && it.action == "rollback" }
        assertNotNull(rollback, "build-test-deploy must contain the standard.rollback step")
        assertTrue(rollback.materialization.status == TargetMaterializationStatus.NOTES_PROJECTED, "rollback must use connected notes-backed materialization evidence: ${rollback.materialization}")
        assertTrue(rollback.mappingNotes.any { it.feature == "materialization.notes-projected" }, "rollback must carry a notes-projected materialization note; got ${rollback.mappingNotes}")
        assertNotNull(rollback.materialization.requirements["projectionPlan"], "rollback must cite the projection plan created by materialization resolver")
    }
}
