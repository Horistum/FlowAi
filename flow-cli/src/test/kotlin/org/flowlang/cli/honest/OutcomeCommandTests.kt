package org.flowlang.cli.honest

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.artifacts.ArtifactPublicationVerifier
import org.flowlang.cli.Json

class OutcomeCommandTests {
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream(
        "/flow/reference-contracts/conformance/operation-outcomes/$name.json")).bufferedReader().use { it.readText() }
    private fun workspace(block: (File) -> Unit) {
        val root = createTempDirectory("outcome-cli-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    @Test fun missingEvidencePublishesBoundedDiagnosticReportAtomically() = workspace { root ->
        val source = File(root, "proposal.json").apply { writeText(fixture("infrastructure.proposal")) }
        val compiled = assertIs<ProposalCliResult>(executeCli(arrayOf("proposal", source.path)))
        val contract = Json.mapper.readTree(fixture("infrastructure.requirements")) as ObjectNode
        contract.put("graphDigest", compiled.review.graphDigest)
        val requirements = File(root, "requirements.json").apply { writeText(contract.toString()) }
        val out = File(root, "result")
        val args = arrayOf("outcomes", source.path, "--source-kind", "proposal", "--requirements", requirements.path,
            "--evaluated-at", "2026-11-08T02:10:00Z", "--out", out.path)
        val result = assertIs<OutcomeCliResult>(executeCli(args))
        assertEquals(3, result.exitCode)
        assertFalse(result.review.executionAuthorized)
        assertTrue(result.artifacts.all { it.role in setOf(CliArtifactRole.DIAGNOSTIC_EVIDENCE, CliArtifactRole.TARGET_NEUTRAL_PLANNING) })
        val publication = assertNotNull(ArtifactPublicationVerifier().verify(out).publication)
        assertEquals("JSON_SCHEMA", publication.coveredFiles.single { it.path == "operation-outcome-review.json" }.validation)
        val before = out.listFiles()!!.associate { it.name to it.readBytes().toList() }
        assertEquals(2, executeCli(args).exitCode)
        assertEquals(before, out.listFiles()!!.associate { it.name to it.readBytes().toList() })
        assertEquals(2, executeCli(args + arrayOf("--target", "azure-devops")).exitCode)
        assertEquals(2, executeCli(args + "--render").exitCode)
        assertEquals(2, executeCli(arrayOf("outcomes", source.path, "--source-kind", "flow", "--requirements", requirements.path)).exitCode)
    }
    @Test fun invalidContractCannotPublishOrExposeItsContents() = workspace { root ->
        val file = File(root, "requirements.json").apply { writeText("{\"secret-do-not-echo\":true}") }
        val output = File(root, "out")
        val result = executeCli(arrayOf("outcomes", "missing.json", "--source-kind", "intent", "--requirements", file.path, "--out", output.path))
        assertEquals(2, result.exitCode)
        assertFalse(output.exists())
        assertFalse(result.toString().contains("secret-do-not-echo"))
        assertTrue(result.diagnostics.all { !it.toString().contains("secret-do-not-echo") })
    }
}
