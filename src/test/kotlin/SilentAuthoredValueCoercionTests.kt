import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.ast.ParallelNode
import org.flowlang.parser.FlowParser
import org.flowlang.parser.ParseException
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.targets.TargetRegistryYamlLoader

class SilentAuthoredValueCoercionTests {
    @Test
    fun parallelFailFastSeparatesOmissionExplicitValuesAndMalformedAuthoredValues() {
        assertTrue(parallel("parallel { branch \"a\" { skip \"ok\" } }").failFast)
        assertTrue(parallel("parallel failFast true { branch \"a\" { skip \"ok\" } }").failFast)
        assertFalse(parallel("parallel failFast false { branch \"a\" { skip \"ok\" } }").failFast)

        val malformedIdentifiers = listOf("TRUE", "False", "yes", "no", "enabled", "disabled", "banana")
        malformedIdentifiers.forEach { authored ->
            val error = assertFailsWith<ParseException> {
                parallel("parallel failFast $authored { branch \"a\" { skip \"ok\" } }")
            }
            assertTrue(error.message.orEmpty().contains("parallel.failFast"), error.message)
            assertTrue(error.message.orEmpty().contains(authored), error.message)
            assertEquals(3, error.line)
            assertEquals(19, error.column)
        }
    }

    @Test
    fun parallelFailFastRejectsNonIdentifierValuesAtTheAuthoredToken() {
        listOf("1", "0", "\"true\"").forEach { authored ->
            val error = assertFailsWith<ParseException> {
                parallel("parallel failFast $authored { branch \"a\" { skip \"ok\" } }")
            }
            assertTrue(error.message.orEmpty().contains("expected true/false"), error.message)
            assertEquals(3, error.line)
            assertEquals(19, error.column)
        }
    }

    @Test
    fun strictTypedYamlPreservesOmissionDefaultsButRejectsCrossTypeScalarCoercion() {
        val omitted = FlowYaml.readStrict("{}", StrictScalarFixture::class.java, "<strict-omitted>")
        assertEquals("default-id", omitted.id)
        assertTrue(omitted.enabled)
        assertEquals(3, omitted.count)

        listOf(
            "id: 42\nenabled: true\ncount: 3\n",
            "id: true\nenabled: true\ncount: 3\n",
            "id: ok\nenabled: 1\ncount: 3\n",
            "id: ok\nenabled: \"false\"\ncount: 3\n",
            "id: ok\nenabled: true\ncount: 1.9\n",
            "id: ok\nenabled: true\ncount: \"3\"\n"
        ).forEachIndexed { index, yaml ->
            val error = assertFailsWith<FlowYamlException> {
                FlowYaml.readStrict(yaml, StrictScalarFixture::class.java, "<strict-malformed-$index>")
            }
            assertTrue(error.message.orEmpty().contains("Invalid YAML"), error.message)
        }
    }

    @Test
    fun targetRegistryRejectsMalformedScalarTypesInsteadOfNormalizingThem() {
        val valid = targetRegistry(
            """
            kind: FlowTargetRegistry
            version: "${FlowStandardVersions.TARGET_REGISTRY_VERSION}"
            expressionProfiles:
              - id: strict
                description: Strict target expression evidence.
                supportsAll: true
            targets: []
            """.trimIndent()
        )
        assertTrue(valid.expressionProfiles.single().supportsAll)
        assertEquals("strict", valid.expressionProfiles.single().id)

        val malformed = listOf(
            "supportsAll: 1" to "supportsAll: true",
            "id: 42" to "id: strict",
            "description: 42" to "description: Strict target expression evidence."
        )
        malformed.forEach { (badField, goodField) ->
            val yaml = """
                kind: FlowTargetRegistry
                version: "${FlowStandardVersions.TARGET_REGISTRY_VERSION}"
                expressionProfiles:
                  - id: strict
                    description: Strict target expression evidence.
                    supportsAll: true
                targets: []
            """.trimIndent().replace(goodField, badField)
            assertFailsWith<FlowYamlException> { targetRegistry(yaml) }
        }
    }

    @Test
    fun targetRegistryRejectsUnknownAuthoredFieldsRatherThanDroppingThem() {
        val yaml = """
            kind: FlowTargetRegistry
            version: "${FlowStandardVersions.TARGET_REGISTRY_VERSION}"
            expressionProfiles: []
            targets: []
            authorTypoThatUsedToDisappear: true
        """.trimIndent()

        assertFailsWith<FlowYamlException> { targetRegistry(yaml) }
    }

    @Test
    fun shippedTargetRegistryRemainsAcceptedByTheStrictBoundary() {
        val document = TargetRegistryYamlLoader.load(File("targets/builtin-targets.yaml"))
        assertEquals(FlowStandardVersions.TARGET_REGISTRY_VERSION, document.version)
        assertTrue(document.targets.isNotEmpty())
    }

    private fun parallel(statement: String): ParallelNode {
        val source = """
            flow "test" {
              steps {
            $statement
              }
            }
        """.trimIndent()
        return FlowParser().parse(source, "<parallel-test>").flow.steps.single() as ParallelNode
    }

    private fun targetRegistry(yaml: String) = createTempDirectory("flow-si06-target-registry-")
        .resolve("registry.yaml")
        .toFile()
        .also { it.writeText(yaml) }
        .let(TargetRegistryYamlLoader::load)

    private data class StrictScalarFixture(
        val id: String = "default-id",
        val enabled: Boolean = true,
        val count: Int = 3
    )
}
