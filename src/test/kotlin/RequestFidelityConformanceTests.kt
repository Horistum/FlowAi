package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class RequestFidelityConformanceTests {
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
