import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.artifacts.StandardSurface
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.intent.KUBERNETES_MAINTENANCE
import org.flowlang.intent.StandardCapability
import org.flowlang.intent.StandardCapabilityCompatibility

class StandardCapabilityNeutralityTests {
    @Test
    fun canonicalCapabilityVocabularyContainsOnlyNeutralMaintenanceIdentity() {
        val names = enumValues<StandardCapability>().map { it.name }.toSet()

        assertTrue("CLUSTER_MAINTENANCE" in names)
        assertFalse("KUBERNETES_MAINTENANCE" in names)
        assertTrue("KUBERNETES_MAINTENANCE" in StandardCapabilityCompatibility.retiredSourceNames)
    }

    @Test
    fun retiredCapabilityNameIsConfinedToExplicitSourceCompatibilityBoundary() {
        val retiredName = "KUBERNETES_MAINTENANCE"
        val compatibilityPath = "src/main/kotlin/org/flowlang/intent/StandardCapabilityCompatibility.kt"
        val productionKotlinOccurrences = File("src/main/kotlin")
            .walkTopDown()
            .filter(File::isFile)
            .filter { it.extension == "kt" }
            .filter { retiredName in it.readText() }
            .map { it.relativeTo(File(".")).path.replace(File.separatorChar, '/') }
            .toList()
        val compatibilitySource = File(compatibilityPath).readText()
        val manifest = File("src/main/resources/standard/compatibility/capability-aliases.yaml").readText()

        assertEquals(listOf(compatibilityPath), productionKotlinOccurrences)
        assertTrue(compatibilitySource.contains("val StandardCapability.Companion.$retiredName: StandardCapability"))
        assertTrue(compatibilitySource.contains("@Deprecated"))
        assertTrue(manifest.lineSequence().any { it.trim() == "- source: $retiredName" })
        assertTrue(manifest.lineSequence().any { it.trim() == "canonical: CLUSTER_MAINTENANCE" })
    }

    @Suppress("DEPRECATION")
    @Test
    fun deprecatedKotlinAliasPreservesSourceCompatibilityWithoutBecomingCanonical() {
        assertEquals(StandardCapability.CLUSTER_MAINTENANCE, StandardCapability.KUBERNETES_MAINTENANCE)
        assertFalse("KUBERNETES_MAINTENANCE" in enumValues<StandardCapability>().map { it.name })
    }

    @Test
    fun legacyIntentCapabilityNormalizesToCanonicalClusterMaintenance() {
        val document = IntentYamlLoader.loadText(
            """
            intentVersion: "2.0"
            kind: FlowIntentDocument
            name: legacy-maintenance
            workflows:
              - name: maintenance
                kind: RUNBOOK
                steps:
                  - id: maintain
                    capability: KUBERNETES_MAINTENANCE
                    params:
                      scope: payments
            """.trimIndent(),
            "legacy-maintenance.intent.yaml"
        )

        assertEquals(
            StandardCapability.CLUSTER_MAINTENANCE,
            document.workflows.single().steps.single().capability
        )
    }

    @Test
    fun normativeSchemaAndPublicCorpusPublishOnlyCanonicalIdentity() {
        val schema = File("schemas/module.schema.json").readText()
        val publicCapabilities = StandardSurface.referenceIntentCorpus()
            .scenarios
            .flatMap { it.expectedCapabilities }
            .toSet()

        assertTrue(schema.contains("CLUSTER_MAINTENANCE"))
        assertFalse(schema.contains("KUBERNETES_MAINTENANCE"))
        assertTrue("CLUSTER_MAINTENANCE" in publicCapabilities)
        assertFalse("KUBERNETES_MAINTENANCE" in publicCapabilities)
    }
}
