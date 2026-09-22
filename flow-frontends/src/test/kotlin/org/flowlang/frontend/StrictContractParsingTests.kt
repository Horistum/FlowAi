package org.flowlang.frontend

import org.flowlang.serialization.*
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.modules.ModuleRegistry
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class StrictContractParsingTests {
    data class Contract(val name: String = "demo", val enabled: Boolean = true, val count: Int = 1,
        val values: Map<String, String> = emptyMap())

    private fun typedReaders(): List<(String) -> Contract> = listOf(
        { FlowYaml.read(it, Contract::class.java, "contract-input") },
        { FlowYaml.readStrict(it, Contract::class.java, "contract-input") },
        { FlowJson.read(it, Contract::class.java, "contract-input") }
    )

    @Test fun allTypedEntryPointsPreserveValidDataAndOmittedDefaults() {
        val text = """{"name":"demo","enabled":false,"count":2,"values":{"key":"value"}}"""
        typedReaders().forEach { read ->
            assertEquals(Contract("demo", false, 2, mapOf("key" to "value")), read(text))
            assertEquals(Contract(), read("{}"))
        }
        assertEquals(Contract("demo", false, 2), FlowYaml.read("name: demo\nenabled: false\ncount: 2", Contract::class.java))
    }

    @Test fun historicalReadCannotIgnoreUnknownFieldsDuplicatesOrCoercion() {
        val mutants = listOf(
            """{"unknown":true}""",
            """{"name":"first","name":"second"}""",
            """{"values":{"key":"first","key":"second"}}""",
            """{"enabled":"false"}""", """{"enabled":1}""", """{"enabled":null}""",
            """{"count":"2"}""", """{"count":2.5}""", """{"count":true}""", """{"count":null}""",
            """{"name":1}""", """{"name":true}""", """{"values":{"key":1}}""",
            "{} {}", "{} false", "{} []", "null", "", "   "
        )
        typedReaders().forEachIndexed { index, read -> mutants.forEach { text ->
            val failure = assertFails("reader=$index input=$text") { read(text) }
            assertContains(failure.message.orEmpty(), "contract-input")
        } }
    }

    @Test fun mapAndTreeReadersRejectNestedDuplicatesAndExtraDocuments() {
        val readers: List<(String) -> Any> = listOf(
            { FlowYaml.readMap(it, "untyped-input") }, { FlowJson.readTree(it, "untyped-input") }
        )
        for (read in readers) {
            assertEquals(false, runCatching { read("""{"nested":{"key":"ok"}}""") }.isFailure)
            for (text in listOf("""{"a":1,"a":2}""", """{"nested":{"a":1,"a":2}}""", "{} {}", "{}", "null").filter { it != "{}" }) {
                assertContains(assertFails { read(text) }.message.orEmpty(), "untyped-input")
            }
        }
        listOf("name: demo\n---\nname: other", "{}\n---\nnull", "{}\n---", "{}\n...\n{}").forEach {
            assertFailsWith<FlowYamlException> { FlowYaml.readMap(it, "multi.yaml") }
            assertFailsWith<FlowYamlException> { FlowYaml.read(it, Contract::class.java, "multi.yaml") }
        }
    }

    @Test fun yamlAliasesFailExplicitlyWhileLiteralValuesAndTemplatesSurvive() {
        for (text in listOf("first: &a demo\nsecond: *a", "first: &a [one]\nsecond: *a", "root: &a [*a]")) {
            assertFailsWith<FlowYamlException> { FlowYaml.readMap(text, "alias.yaml") }
        }
        assertEquals("*literal", FlowYaml.readMap("value: '*literal'")["value"])
        assertEquals(mapOf("value" to "safe"), FlowYaml.readMap("<<: {value: safe}")["<<"])
        assertEquals("demo", FlowYaml.readMap("value: &unused demo")["value"])
    }

    @Test fun nestingBudgetRejectsBeforeBindingIncludingUnknownSubtrees() {
        val accepted = "[".repeat(ContractReadLimits.MAX_DEPTH - 1) + "0" + "]".repeat(ContractReadLimits.MAX_DEPTH - 1)
        val rejected = "[".repeat(ContractReadLimits.MAX_DEPTH + 1) + "0" + "]".repeat(ContractReadLimits.MAX_DEPTH + 1)
        FlowJson.readTree(accepted)
        FlowYaml.readMap("{\"value\":$accepted}")
        assertFailsWith<FlowJsonException> { FlowJson.readTree(rejected, "deep.json") }
        assertFailsWith<FlowYamlException> { FlowYaml.readMap("{\"value\":$rejected}", "deep.yaml") }
        assertFailsWith<FlowYamlException> { FlowYaml.readStrict("{\"unknown\":$rejected}", Contract::class.java) }
    }

    @Test fun tokenBudgetRejectsWideDocumentsBeforeBuildingObjectGraphs() {
        val text = "{\"items\":[" + List(ContractReadLimits.MAX_TOKENS) { "0" }.joinToString(",") + "]}"
        assertContains(assertFailsWith<FlowYamlException> { FlowYaml.readMap(text) }.message.orEmpty(), "CONTRACT_TOKEN_LIMIT")
        assertContains(assertFailsWith<FlowJsonException> { FlowJson.readTree(text) }.message.orEmpty(), "CONTRACT_TOKEN_LIMIT")
    }

    @Test fun scalarNameAndNumberLimitsCoverBothFormats() {
        val inputs = listOf(
            "{\"value\":\"" + "x".repeat(ContractReadLimits.MAX_STRING_LENGTH + 1) + "\"}",
            "{\"" + "x".repeat(ContractReadLimits.MAX_NAME_LENGTH + 1) + "\":0}",
            "{\"value\":" + "9".repeat(ContractReadLimits.MAX_NUMBER_LENGTH + 1) + "}"
        )
        inputs.forEach {
            assertFailsWith<FlowYamlException> { FlowYaml.readMap(it) }
            assertFailsWith<FlowJsonException> { FlowJson.readTree(it) }
        }
        val accepted = "{\"value\":\"" + "x".repeat(ContractReadLimits.MAX_STRING_LENGTH) + "\"}"
        assertEquals(ContractReadLimits.MAX_STRING_LENGTH, (FlowYaml.readMap(accepted)["value"] as String).length)
        assertEquals(ContractReadLimits.MAX_STRING_LENGTH, FlowJson.readTree(accepted)["value"].textValue().length)
    }

    @Test fun documentLimitCountsUtf8BytesAndIncludesIgnoredWhitespace() {
        val tooLarge = "{}" + " ".repeat(ContractReadLimits.MAX_DOCUMENT_BYTES)
        val unicode = "# " + "é".repeat(ContractReadLimits.MAX_DOCUMENT_BYTES / 2) + "\n{}"
        assertFailsWith<FlowYamlException> { FlowYaml.readMap(tooLarge, "large.yaml") }
        assertFailsWith<FlowYamlException> { FlowYaml.readMap(unicode, "unicode.yaml") }
        assertFailsWith<FlowJsonException> { FlowJson.readTree(tooLarge, "large.json") }
        val frontend = IntentYamlFrontend(FrontendCompilerComposition.compiler(ModuleRegistry(emptyMap())))
        val failure = assertFailsWith<IllegalArgumentException> {
            frontend.compileText(tooLarge, "oversized.intent.yaml")
        }
        assertContains(failure.message.orEmpty(), "oversized.intent.yaml")
    }

    @Test fun filesUseTheSameStrictPolicyAndRejectMalformedUtf8() {
        val root = createTempDirectory("strict-contract-files").toFile()
        try {
            val file = File(root, "contract.json")
            val reads: List<() -> Any> = listOf(
                { FlowYaml.read(file, Contract::class.java) }, { FlowYaml.readStrict(file, Contract::class.java) },
                { FlowYaml.readMap(file) }, { FlowJson.read(file, Contract::class.java) }, { FlowJson.readTree(file) }
            )
            file.writeText("{}")
            reads.forEach { it() }
            for (text in listOf("""{"name":"first","name":"second"}""", "{} {}", "null", "")) {
                file.writeText(text)
                reads.forEach { assertContains(assertFails { it() }.message.orEmpty(), file.path) }
            }
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            reads.forEach { assertContains(assertFails { it() }.message.orEmpty(), file.path) }
            file.outputStream().use { out ->
                val block = ByteArray(8192) { 32 }
                repeat(ContractReadLimits.MAX_DOCUMENT_BYTES / block.size + 1) { out.write(block) }
            }
            reads.forEach { assertContains(assertFails { it() }.message.orEmpty(), file.path) }
        } finally { root.deleteRecursively() }
    }

    @Test fun equalDynamicKeysInSeparateObjectsRemainDistinct() {
        val text = """{"left":{"id":"first"},"right":{"id":"second"}}"""
        val yaml = FlowYaml.readMap(text)
        assertEquals(mapOf("id" to "first"), yaml["left"])
        assertEquals("second", FlowJson.readTree(text)["right"]["id"].textValue())
    }
}
