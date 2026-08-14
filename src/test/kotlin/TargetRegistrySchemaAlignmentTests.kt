import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.cli.Json
import org.flowlang.conformance.JsonSchemaSmokeValidator
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException
import org.flowlang.targets.TargetRegistryContractVocabulary
import org.flowlang.targets.TargetRegistryYamlLoader

class TargetRegistrySchemaAlignmentTests {
    private val schema by lazy { Json.mapper.readTree(File("schemas/target-registry.schema.json")) }

    @Test
    fun shippedRegistryIsAcceptedBySchemaAndProductionLoader() {
        val file = File("targets/builtin-targets.yaml")
        validateSchema(file)
        val loaded = TargetRegistryYamlLoader.load(file)
        assertEquals("3.2", loaded.version)
        assertTrue(loaded.targets.isNotEmpty())
    }

    @Test
    fun capabilityAndSupportVocabulariesAreSharedExactly() {
        val defs = schema.path("\$defs")
        val schemaCapabilities = defs.path("capabilitySupportMap").path("properties").fieldNames().asSequence().toSet()
        val schemaSupport = defs.path("supportLevel").path("enum").map { it.asText() }.toSet()
        assertEquals(TargetRegistryContractVocabulary.capabilityNames, schemaCapabilities)
        assertEquals(TargetRegistryContractVocabulary.supportLevels.keys, schemaSupport)
    }

    @Test
    fun projectionRulesMayBeOmittedBecauseProductionDefaultsThem() = withRegistry(
        """
        kind: FlowTargetRegistry
        version: "3.2"
        expressionProfiles:
          - id: full
            description: Full expression support
            supportsAll: true
        targets:
          - name: minimal
            expressionProfile: full
        """.trimIndent()
    ) { file ->
        validateSchema(file)
        TargetRegistryYamlLoader.load(file)
    }

    @Test
    fun unknownCapabilityIsRejectedByBothBoundaries() = assertRejectedByBoth(
        """
        kind: FlowTargetRegistry
        version: "3.2"
        expressionProfiles:
          - id: full
            description: Full expression support
            supportsAll: true
        targets:
          - name: broken
            expressionProfile: full
            capabilities:
              imaginaryCapability: supported
        """.trimIndent()
    )

    @Test
    fun undocumentedSupportAliasesAreRejectedByBothBoundaries() = assertRejectedByBoth(
        """
        kind: FlowTargetRegistry
        version: "3.2"
        expressionProfiles:
          - id: full
            description: Full expression support
            supportsAll: true
        targets:
          - name: broken
            expressionProfile: full
            capabilities:
              parallel: yes
        """.trimIndent()
    )

    @Test
    fun contradictoryExpressionProfileIsRejectedByBothBoundaries() = assertRejectedByBoth(
        """
        kind: FlowTargetRegistry
        version: "3.2"
        expressionProfiles:
          - id: contradictory
            description: Contradictory expression support
            supportsAll: true
            features: [node.literal]
        targets:
          - name: broken
            expressionProfile: contradictory
        """.trimIndent()
    )

    @Test
    fun duplicateAuthoredExpressionFeatureIsRejectedByBothBoundaries() = assertRejectedByBoth(
        """
        kind: FlowTargetRegistry
        version: "3.2"
        expressionProfiles:
          - id: partial
            description: Partial expression support
            features: [node.literal, node.literal]
        targets:
          - name: broken
            expressionProfile: partial
        """.trimIndent()
    )

    @Test
    fun topologyAliasesAreRejectedByBothBoundaries() = assertRejectedByBoth(
        """
        kind: FlowTargetRegistry
        version: "3.2"
        expressionProfiles:
          - id: full
            description: Full expression support
            supportsAll: true
        targets:
          - name: broken
            expressionProfile: full
            topology:
              evidenceReference: test:topology
              capabilities:
                workflowScope: yes
                branchIsolation: supported
                attemptIsolation: supported
                workflowLifetime: supported
                suspendResume: supported
                ephemeralWorkspace: supported
                durableState: supported
                valuePropagation: supported
                workspacePropagation: supported
                statePropagation: supported
                failurePropagation: supported
        """.trimIndent()
    )

    private fun assertRejectedByBoth(yaml: String) = withRegistry(yaml) { file ->
        assertFailsWith<IllegalArgumentException> { validateSchema(file) }
        assertProductionRejected(file)
    }

    private fun assertProductionRejected(file: File) {
        val failure = runCatching { TargetRegistryYamlLoader.load(file) }.exceptionOrNull()
            ?: throw AssertionError("Production loader unexpectedly accepted invalid target registry '${file.path}'.")
        assertTrue(
            failure is FlowYamlException || failure is IllegalArgumentException || failure is IllegalStateException,
            "Expected an authored-input validation rejection but got ${failure::class.qualifiedName}: ${failure.message}"
        )
    }

    private fun validateSchema(file: File) {
        val instance = Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(FlowYaml.readMap(file))
        JsonSchemaSmokeValidator.validate(instance, schema)
    }

    private fun withRegistry(yaml: String, assertions: (File) -> Unit) {
        val root = Files.createTempDirectory("flow-si07-registry").toFile()
        try {
            val file = File(root, "target.yaml")
            file.writeText(yaml.trimEnd() + "\n")
            assertions(file)
        } finally {
            root.deleteRecursively()
        }
    }
}
