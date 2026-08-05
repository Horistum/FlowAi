import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.artifacts.StandardSurface
import org.flowlang.intent.IntentYamlLoader
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
