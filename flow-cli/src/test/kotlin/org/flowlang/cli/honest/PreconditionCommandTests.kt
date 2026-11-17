package org.flowlang.cli.honest

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.artifacts.ArtifactPublicationVerifier
import org.flowlang.cli.Json

class PreconditionCommandTests {
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream(
        "/preconditions/$name.json")).bufferedReader().use { it.readText() }
    private fun workspace(block: (File) -> Unit) {
        val root = createTempDirectory("precondition-cli-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    @Test fun missingFreshEvidencePublishesAnUnresolvedSchemaValidatedReview() = workspace { root ->
        val source = File(root, "proposal.json").apply { writeText(fixture("infrastructure.proposal")) }
        val response = executeCli(arrayOf("proposal", source.path))
        val compiled = assertIs<ProposalCliResult>(response, response.diagnostics.toString())
        val contract = Json.mapper.readTree(fixture("infrastructure.requirements")) as ObjectNode
        contract.put("graphDigest", compiled.review.graphDigest)
        val requirements = File(root, "requirements.json").apply { writeText(contract.toString()) }
        val out = File(root, "result")
        val args = arrayOf("preconditions", source.path, "--source-kind", "proposal", "--requirements", requirements.path,
            "--evaluated-at", "2026-11-17T01:00:30Z", "--out", out.path)
        val result = assertIs<PreconditionCliResult>(executeCli(args))
        assertEquals(3, result.exitCode)
        assertFalse(result.review.executionAuthorized)
        assertNull(result.review.validUntil)
        assertTrue(result.review.results.all { it.nodeId != null && it.code == "PRECONDITION_OBSERVATION_MISSING" })
        assertTrue(result.artifacts.all { it.role in setOf(CliArtifactRole.DIAGNOSTIC_EVIDENCE, CliArtifactRole.TARGET_NEUTRAL_PLANNING) })
        val publication = assertNotNull(ArtifactPublicationVerifier().verify(out).publication)
        val artifact = publication.coveredFiles.single { it.path == "operation-precondition-review.json" }
        assertEquals("JSON_SCHEMA", artifact.validation)
        assertEquals("schemas/operation-precondition-review.schema.json", artifact.schema)
        val before = out.listFiles()!!.associate { it.name to it.readBytes().toList() }
        assertEquals(2, executeCli(args).exitCode)
        assertEquals(before, out.listFiles()!!.associate { it.name to it.readBytes().toList() })
        for (option in listOf(arrayOf("--target", "jenkins"), arrayOf("--render"), arrayOf("--trust", "missing.json")))
            assertEquals(2, executeCli(args + option).exitCode)
        assertEquals(2, executeCli(arrayOf("preconditions", source.path, "--source-kind", "flow", "--requirements", requirements.path)).exitCode)
    }
    @Test fun invalidContractsAndRejectedSourcesDoNotPublish() = workspace { root ->
        val bad = File(root, "invalid.json").apply { writeText("{\"private-canary\":true}") }
        val output = File(root, "output")
        val result = executeCli(arrayOf("preconditions", "missing.json", "--source-kind", "intent", "--requirements", bad.path, "--out", output.path))
        assertEquals(2, result.exitCode)
        assertFalse(output.exists())
        assertFalse(result.toString().contains("private-canary"))
        val source = File(root, "rejected.json").apply { writeText(fixture("infrastructure.proposal").replace("PROVISION", "NOT_A_CAPABILITY")) }
        val requirements = File(root, "requirements.json").apply { writeText(fixture("infrastructure.requirements")) }
        val rejected = executeCli(arrayOf("preconditions", source.path, "--source-kind", "proposal", "--requirements", requirements.path, "--out", output.path))
        assertTrue(rejected.exitCode != 0)
        assertFalse(output.exists())
    }
    @Test fun compilerRejectionCannotPublishAPreconditionAssessment() = workspace { root ->
        val proposal = Json.mapper.readTree(fixture("infrastructure.proposal")) as ObjectNode
        val body = proposal.path("response").path("normalizedIntent").path("workflows")[0].path("body")
            as com.fasterxml.jackson.databind.node.ArrayNode
        val provision = body.first { it.path("capability").asText() == "PROVISION" } as ObjectNode
        provision.putArray("requires").add("missing-step")
        val source = File(root, "unsafe.json").apply { writeText(proposal.toString()) }
        val requirements = File(root, "requirements.json").apply { writeText(fixture("infrastructure.requirements")) }
        val output = File(root, "result")
        val result = executeCli(arrayOf("preconditions", source.path, "--source-kind", "proposal",
            "--requirements", requirements.path, "--out", output.path))
        assertEquals(4, result.exitCode, result.diagnostics.toString())
        assertFalse(output.exists())
    }
    @Test fun theCurrentClockCannotReuseHistoricalFreshness() = workspace { root ->
        val source = File(root, "proposal.json").apply { writeText(fixture("data-copy.proposal")) }
        val response = executeCli(arrayOf("proposal", source.path))
        val compiled = assertIs<ProposalCliResult>(response, response.diagnostics.toString())
        val contract = Json.mapper.readTree(fixture("data-copy.requirements")) as ObjectNode
        contract.put("graphDigest", compiled.review.graphDigest)
        contract.put("issuedAt", "2000-01-01T00:00:00Z"); contract.put("expiresAt", "2000-01-01T00:05:00Z")
        val requirements = File(root, "requirements.json").apply { writeText(contract.toString()) }
        val result = assertIs<PreconditionCliResult>(executeCli(arrayOf("preconditions", source.path,
            "--source-kind", "proposal", "--requirements", requirements.path)))
        assertEquals(3, result.exitCode)
        assertTrue("PRECONDITION_ASSESSMENT_EXPIRED" in result.review.diagnostics)
        assertNull(result.review.validUntil)
    }
}
