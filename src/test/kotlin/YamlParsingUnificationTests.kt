import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

class YamlParsingUnificationTests {
    @Test
    fun sharedYamlBoundarySupportsStandardYamlCollectionsAndScalars() {
        val root = FlowYaml.readMap(
            """
            name: demo
            enabled: true
            count: 3
            missing: null
            inline: { mode: safe, retries: 2 }
            values: [one, two, three]
            nested:
              item: "1.0"
            """.trimIndent(),
            "shared-yaml-test"
        )

        assertEquals("demo", root["name"])
        assertEquals(true, root["enabled"])
        assertEquals(3, root["count"])
        assertEquals(null, root["missing"])
        assertEquals(mapOf("mode" to "safe", "retries" to 2), root["inline"])
        assertEquals(listOf("one", "two", "three"), root["values"])
        assertEquals(mapOf("item" to "1.0"), root["nested"])
    }

    @Test
    fun invalidYamlReportsItsSource() {
        val error = assertFailsWith<FlowYamlException> {
            FlowYaml.readMap("root:\n\tchild: value", "broken-config.yaml")
        }
        assertTrue(error.message.orEmpty().contains("broken-config.yaml"))
    }

    @Test
    fun productionHasOneJacksonYamlOwnerAndNoMiniYamlParser() {
        val sourceRoot = File("src/main/kotlin")
        val yamlOwner = File(sourceRoot, "org/flowlang/serialization/FlowYaml.kt")
        assertTrue(yamlOwner.isFile)
        assertFalse(File(sourceRoot, "org/flowlang/modules/MiniYaml.kt").exists())

        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val directJacksonOwners = kotlinFiles.filter { file ->
            val text = file.readText()
            text.contains("import com.fasterxml.jackson.dataformat.yaml.YAMLFactory") ||
                text.contains("ObjectMapper(YAMLFactory())")
        }.map { it.relativeTo(sourceRoot).path.replace(File.separatorChar, '/') }

        assertEquals(listOf("org/flowlang/serialization/FlowYaml.kt"), directJacksonOwners)

        val miniYamlReferences = kotlinFiles.filter { it.readText().contains("MiniYaml") }
            .map { it.relativeTo(sourceRoot).path.replace(File.separatorChar, '/') }
        assertTrue(miniYamlReferences.isEmpty(), "MiniYaml references remain: $miniYamlReferences")
    }
}
