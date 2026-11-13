package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class RequestFidelityConformanceTests {
    @Test fun versionFiveExplicitParameterScopeIsFrozenOutOfEarlierSchemasAndReaders() {
        val mapper = org.flowlang.serialization.FlowJson
        val schema = mapper.readTree(File("schemas/request-requirements-v5.schema.json"))
        val legacy = mapper.readTree(File("schemas/request-requirements-v4.schema.json"))
        val root = createTempDirectory("requirements-v5-").toFile()
        try {
            for (kind in listOf("literal-parameter", "value-from")) {
                val value = mapper.readTree(File("conformance/failure-regions/infrastructure-readiness.requirements.json")) as com.fasterxml.jackson.databind.node.ObjectNode
                value.put("formatVersion", 5)
                val entry = value.putArray("requirements").addObject().put("id", "parameter")
                    .put("sourceQuote", value["request"]["userText"].asText())
                val check = entry.putObject("check").put("kind", kind).put("parameter", "resource")
                    .put("evaluationScope", "SELECTED_OPERATIONS")
                if (kind == "literal-parameter") {
                    check.putObject("selector").put("capability", "VERIFY"); check.put("expected", "worker")
                } else {
                    check.putObject("producer").put("capability", "PROVISION")
                    check.putObject("consumer").put("capability", "VERIFY")
                }
                org.flowlang.artifacts.ArtifactSchemaValidator.validate(value, schema)
                val file = File(root, "requirements.json").apply { writeText(value.toString()) }
                org.flowlang.frontend.ai.RequestRequirementsReader.read(file)
                value.put("formatVersion", 4)
                assertFailsWith<IllegalArgumentException> { org.flowlang.artifacts.ArtifactSchemaValidator.validate(value, legacy) }
                file.writeText(value.toString())
                assertFailsWith<org.flowlang.frontend.ai.RequestRequirementsException> {
                    org.flowlang.frontend.ai.RequestRequirementsReader.read(file)
                }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun versionTwoSchemaAndReaderAgreeOnSystemRequirementsAndBounds() {
        val mapper = org.flowlang.serialization.FlowJson
        val schema = mapper.readTree(File("schemas/request-requirements-v2.schema.json"))
        val oldSchema = mapper.readTree(File("schemas/request-requirements.schema.json"))
        val files = File("examples/system-automation").listFiles()!!.filter { it.name.endsWith(".requirements.json") }
        assertEquals(3, files.size)
        files.forEach { file ->
            val value = mapper.readTree(file)
            org.flowlang.artifacts.ArtifactSchemaValidator.validate(value, schema)
            org.flowlang.frontend.ai.RequestRequirementsReader.read(file)
            assertFailsWith<IllegalArgumentException> { org.flowlang.artifacts.ArtifactSchemaValidator.validate(value, oldSchema) }
        }
        val original = mapper.readTree(files.first()) as com.fasterxml.jackson.databind.node.ObjectNode
        val selected = original.path("requirements").first { it.path("check").path("kind").asText() == "capability-count" }
        original.putArray("requirements").add(selected)
        val mutations = listOf<(com.fasterxml.jackson.databind.node.ObjectNode) -> Unit>(
            { it.put("formatVersion", 99) },
            { (it.path("requirements")[0].path("check").path("selector") as com.fasterxml.jackson.databind.node.ObjectNode)
                .putObject("parameters").apply { repeat(17) { put("field$it", "x") } } },
            { (it.path("requirements")[0].path("check").path("selector") as com.fasterxml.jackson.databind.node.ObjectNode)
                .putObject("parameters").put("resource", "x".repeat(1001)) },
            { (it.path("requirements")[0].path("check").path("selector") as com.fasterxml.jackson.databind.node.ObjectNode)
                .putObject("parameters").put("invalid-key", "x") }
        )
        val root = createTempDirectory("requirements-v2-").toFile()
        try {
            mutations.forEach { mutate ->
                val value = original.deepCopy().also(mutate)
                assertFailsWith<IllegalArgumentException> { org.flowlang.artifacts.ArtifactSchemaValidator.validate(value, schema) }
                val file = File(root, "requirements.json").apply { writeText(value.toString()) }
                assertFailsWith<org.flowlang.frontend.ai.RequestRequirementsException> {
                    org.flowlang.frontend.ai.RequestRequirementsReader.read(file)
                }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun versionFourPreservesExistingSystemChecksWithoutWideningVersionTwoSchema() {
        val mapper = org.flowlang.serialization.FlowJson
        val current = mapper.readTree(File("schemas/request-requirements-v4.schema.json"))
        val legacy = mapper.readTree(File("schemas/request-requirements-v2.schema.json"))
        val root = createTempDirectory("requirements-v4-compatibility-").toFile()
        try {
            File("examples/system-automation").listFiles()!!.filter { it.name.endsWith(".requirements.json") }.forEach { source ->
                val value = mapper.readTree(source) as com.fasterxml.jackson.databind.node.ObjectNode
                value.put("formatVersion", 4)
                org.flowlang.artifacts.ArtifactSchemaValidator.validate(value, current)
                assertFailsWith<IllegalArgumentException> { org.flowlang.artifacts.ArtifactSchemaValidator.validate(value, legacy) }
                val file = File(root, source.name).apply { writeText(value.toString()) }
                org.flowlang.frontend.ai.RequestRequirementsReader.read(file)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun corpusMeetsIndependentInventoryOutcomeAndMutationGates() {
        val checks = RequestFidelityCorpus(File(".")).checks()
        assertEquals(3, checks.size)
        assertTrue(checks.all { it.passed }, checks.filterNot { it.passed }.joinToString { "${it.name}: ${it.message}" })
    }
    @Test fun publishedRequirementsSchemaAcceptsTheCorpusAndRetainsRequestVocabulary() {
        val schema = org.flowlang.serialization.FlowJson.readTree(File("schemas/request-requirements.schema.json"))
        val proposalSchema = org.flowlang.serialization.FlowJson.readTree(File("schemas/ai-proposal.schema.json"))
        assertEquals(proposalSchema.path("properties").path("request"), schema.path("properties").path("request"))
        File("conformance/authoring").listFiles()!!.filter { it.name.endsWith(".requirements.json") }.forEach { file ->
            org.flowlang.artifacts.ArtifactSchemaValidator.validate(org.flowlang.serialization.FlowJson.readTree(file), schema)
        }
        val invalid = org.flowlang.serialization.FlowJson.readTree(File("examples/proposals/reviewed-requirements.json")) as com.fasterxml.jackson.databind.node.ObjectNode
        val requirements = invalid.path("requirements") as com.fasterxml.jackson.databind.node.ArrayNode
        val first = requirements[0].deepCopy<com.fasterxml.jackson.databind.JsonNode>()
        while (requirements.size() <= 256) requirements.add(first)
        assertFailsWith<IllegalArgumentException> { org.flowlang.artifacts.ArtifactSchemaValidator.validate(invalid, schema) }
    }

    @Test fun changedOracleBytesAndBrokenRequestBindingAreRejected() {
        val root = createTempDirectory("corpus-integrity-").toFile()
        try {
            File("conformance/authoring").copyRecursively(File(root, "conformance/authoring"))
            val requirements = File(root, "conformance/authoring/deployment-specified.requirements.json")
            val original = requirements.readText()
            requirements.appendText("\n")
            assertFailsWith<IllegalArgumentException> { RequestFidelityCorpus(root).verifyInventory() }
            requirements.writeText(original)
            val proposal = File(root, "conformance/authoring/deployment-specified.proposal.json")
            proposal.writeText(proposal.readText().replace("After manual approval", "Before manual approval"))
            assertFailsWith<IllegalArgumentException> { RequestFidelityCorpus(root).verifyInventory() }
        } finally { root.deleteRecursively() }
    }
}
