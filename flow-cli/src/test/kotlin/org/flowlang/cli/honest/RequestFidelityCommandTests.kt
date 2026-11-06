package org.flowlang.cli.honest

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.artifacts.ArtifactPublicationVerifier
import org.flowlang.cli.Json

class RequestFidelityCommandTests {
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/flow/reference-contracts/examples/proposals/$name.json")).bufferedReader().use { it.readText() }
    private fun workspace(action: (File) -> Unit) {
        val root = createTempDirectory("fidelity-cli-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
    private fun run(root: File, proposal: String = fixture("faithful"), requirements: String = fixture("reviewed-requirements"), vararg options: String): ProposalCliResult {
        val input = File(root, "input.json").apply { writeText(proposal) }
        val oracle = File(root, "requirements.json").apply { writeText(requirements) }
        val result = executeCli(arrayOf("proposal", input.path, "--requirements", oracle.path, *options))
        return assertIs<ProposalCliResult>(result, result.diagnostics.toString())
    }
    private fun changed(text: String, edit: (ObjectNode) -> Unit): String = (Json.mapper.readTree(text) as ObjectNode).also(edit).toString()

    @Test fun confirmedRequirementsPublishVersionedBoundedEvidenceAtomically() = workspace { root ->
        val out = File(root, "review")
        val result = run(root, options = arrayOf("--out", out.path))
        assertEquals(0, result.exitCode)
        assertEquals(2, result.review.formatVersion)
        assertEquals("requirements-satisfied", result.review.requestFidelity)
        assertEquals("not-requested", result.review.targetDisposition)
        val evidence = Json.mapper.readTree(File(out, "request-fidelity-review.json"))
        assertEquals("confirmed-requirements-only", evidence.path("scope").asText())
        assertEquals("unassessed", evidence.path("naturalLanguageCompleteness").asText())
        assertEquals("caller-declared", evidence.path("reviewerAttribution").asText())
        assertEquals(result.review.graphDigest, evidence.path("graphDigest").asText())
        val publication = assertNotNull(ArtifactPublicationVerifier().verify(out).publication)
        assertTrue(publication.coveredFiles.filter { it.path in setOf("proposal-review.json", "request-fidelity-review.json") }.all { it.validation == "JSON_SCHEMA" })
        assertTrue(result.presentation.items.filterIsInstance<CliPresentationItem.Section>().any { it.title == "REQUEST FIDELITY REVIEW" })
        val before = out.listFiles()!!.associate { it.name to it.readBytes().toList() }
        assertEquals(2, executeCli(arrayOf("proposal", File(root, "input.json").path, "--requirements", File(root, "requirements.json").path, "--out", out.path)).exitCode)
        assertEquals(before, out.listFiles()!!.associate { it.name to it.readBytes().toList() })
    }

    @Test fun changedDigestAndProvisionalReviewWithholdEveryTargetArtifact() = workspace { root ->
        val changed = changed(fixture("faithful")) { (it.path("response").path("normalizedIntent").path("workflows")[0].path("steps")[1].path("params") as ObjectNode).put("image", "different") }
        for ((proposal, requirements, exit) in listOf(
            Triple(changed, fixture("reviewed-requirements"), 4),
            Triple(fixture("faithful"), fixture("reviewed-requirements").replace("CONFIRMED", "PROVISIONAL"), 3))) {
            val result = run(root, proposal, requirements, "--target", "jenkins", "--render")
            assertEquals(exit, result.exitCode)
            assertEquals("accepted", result.review.semanticAcceptance)
            assertEquals("REQUEST_FIDELITY", result.review.stage)
            assertEquals("withheld-for-fidelity", result.review.targetDisposition)
            assertFalse(result.review.renderAuthorized)
            assertTrue(result.artifacts.any { it.name == "workflow-execution-plan-set.json" })
            assertTrue(result.artifacts.none { it.role in setOf(CliArtifactRole.RENDERED_TARGET, CliArtifactRole.TARGET_MANIFEST, CliArtifactRole.REVIEW_DOCUMENT) })
        }
    }

    @Test fun compilerRejectionCannotBecomeFidelityAcceptance() = workspace { root ->
        val bad = changed(fixture("faithful")) { (it.path("response").path("normalizedIntent").path("workflows")[0].path("steps")[1] as ObjectNode).putArray("requires").add("missing") }
        val out = File(root, "review")
        val result = run(root, bad, options = arrayOf("--out", out.path))
        assertEquals(4, result.exitCode)
        assertEquals("rejected", result.review.semanticAcceptance)
        assertEquals("not-evaluated", result.review.requestFidelity)
        assertNull(result.review.graphDigest)
        val evidence = Json.mapper.readTree(File(out, "request-fidelity-review.json"))
        assertTrue(evidence.path("requirements").all { it.path("state").asText() == "NOT_EVALUATED" })
        assertEquals(setOf("proposal-review.json", "request-fidelity-review.json"), result.review.artifacts.toSet())
        assertNotNull(ArtifactPublicationVerifier().verify(out).publication)
    }

    @Test fun staleAndMalformedRequirementsFailBeforeCompilationWithSafeDiagnostics() = workspace { root ->
        for (requirements in listOf(
            changed(fixture("reviewed-requirements")) { (it.path("request") as ObjectNode).put("userText", "credential-do-not-echo") },
            changed(fixture("reviewed-requirements")) { it.put("confirmation", "credential-do-not-echo") })) {
            val out = File(root, "review-${requirements.hashCode()}")
            val result = run(root, requirements = requirements, options = arrayOf("--out", out.path))
            assertEquals(2, result.exitCode)
            assertEquals("INPUT", result.review.stage)
            assertEquals("unassessed", result.review.requestFidelity)
            assertEquals("not-requested", result.review.targetDisposition)
            assertEquals(listOf("proposal-review.json"), result.review.artifacts)
            assertFalse(File(out, "proposal-review.json").readText().contains("credential-do-not-echo"))
        }
    }

    @Test fun fidelityPassDoesNotOverrideProviderReviewOrTargetSelection() = workspace { root ->
        val proposal = changed(fixture("faithful")) { (it.path("response").path("report") as ObjectNode).putArray("missingDecisions").add("Review the request") }
        val result = run(root, proposal, options = arrayOf("--target", "jenkins", "--render"))
        assertEquals("requirements-satisfied", result.review.requestFidelity)
        assertEquals(3, result.exitCode)
        assertEquals("withheld-for-review", result.review.targetDisposition)
        assertEquals(2, run(root, options = arrayOf("--render")).exitCode)
    }

    @Test fun candidateCannotEmbedItsOwnRequirementsOrForgeAPass() = workspace { root ->
        val forged = changed(fixture("faithful")) { it.put("requestFidelity", "requirements-satisfied") }
        assertEquals(2, run(root, forged).exitCode)
        val embedded = changed(fixture("faithful")) { it.set<ObjectNode>("requirements", Json.mapper.readTree(fixture("reviewed-requirements"))) }
        assertEquals(2, run(root, embedded).exitCode)
    }

    @Test fun satisfiedRequirementsStillUseTheExistingRenderAuthorityAndReceipt() = workspace { root ->
        val proposal = fixture("accepted")
        val request = Json.mapper.readTree(proposal).path("request")
        // An independently authored bounded subset, not a claim that the entire request is covered.
        val requirements = """{
          "formatVersion":1,"request":$request,"origin":"REVIEWER_AUTHORED","confirmation":"CONFIRMED","reviewerId":"test-reviewer",
          "requirements":[
            {"id":"checkout","sourceQuote":"Check out","check":{"kind":"capability-count","selector":{"capability":"CHECKOUT"},"minimum":1,"maximum":1}},
            {"id":"build","sourceQuote":"build the reference image","check":{"kind":"capability-count","selector":{"capability":"BUILD_IMAGE"},"minimum":1,"maximum":1}},
            {"id":"order","sourceQuote":"Check out","check":{"kind":"before","before":{"capability":"CHECKOUT"},"after":{"capability":"BUILD_IMAGE"}}}
          ]
        }"""
        val out = File(root, "rendered")
        val result = run(root, proposal, requirements, "--target", "jenkins", "--render", "--out", out.path)
        assertEquals(0, result.exitCode)
        assertEquals("requirements-satisfied", result.review.requestFidelity)
        assertTrue(result.review.renderAuthorized)
        assertTrue(result.artifacts.any { it.role == CliArtifactRole.RENDERED_TARGET })
        assertTrue(File(out, "target-artifact-evidence.json").isFile)
        assertNotNull(ArtifactPublicationVerifier().verify(out).publication)
    }

    @Test fun noRequirementsKeepsVersionOneAndUnassessedFidelity() = workspace { root ->
        val input = File(root, "input.json").apply { writeText(fixture("faithful")) }
        val result = assertIs<ProposalCliResult>(executeCli(arrayOf("proposal", input.path)))
        assertEquals(1, result.review.formatVersion)
        assertEquals("unassessed", result.review.requestFidelity)
        assertFalse(result.review.artifacts.contains("request-fidelity-review.json"))
    }

    @Test fun missingTargetProviderPreservesVersionTwoFidelityAndAtomicPlansWithoutRendering() = workspace { root ->
        for (render in listOf(false, true)) {
            val out = File(root, "review-$render")
            val options = listOf("--target", "local", "--out", out.path) + if (render) listOf("--render") else emptyList()
            val result = run(root, options = options.toTypedArray())
            assertEquals(4, result.exitCode)
            assertEquals(2, result.review.formatVersion)
            assertEquals("MATERIALIZATION", result.review.stage)
            assertEquals("requirements-satisfied", result.review.requestFidelity)
            assertEquals("blocked", result.review.targetDisposition)
            assertEquals(render, result.review.renderRequested)
            assertFalse(result.review.renderAuthorized)
            assertNotNull(result.review.graphDigest)
            assertEquals("PROPOSAL_TARGET_PROJECTION_UNAVAILABLE", result.review.diagnostics.last().code)
            val expected = setOf("proposal-review.json", "request-fidelity-review.json", "workflow-execution-plan-set.json",
                "execution-plan.json", "canonical-execution-plan.json")
            assertEquals(expected, result.review.artifacts.toSet())
            val receipt = assertNotNull(ArtifactPublicationVerifier().verify(out).publication)
            assertEquals(expected, receipt.coveredFiles.map { it.path }.toSet())
            val schemaReceipts = receipt.coveredFiles.associateBy { it.path }
            for ((name, schema) in mapOf("proposal-review.json" to "schemas/proposal-review-v2.schema.json",
                    "request-fidelity-review.json" to "schemas/request-fidelity-review.schema.json")) {
                assertEquals("JSON_SCHEMA", schemaReceipts.getValue(name).validation)
                assertEquals(schema, schemaReceipts.getValue(name).schema)
            }
            val input = File(root, "input.json").readBytes()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) }
            assertEquals(digest, result.review.inputSource!!.sha256)
        }
        val provisional = run(root, requirements = fixture("reviewed-requirements").replace("CONFIRMED", "PROVISIONAL"),
            options = arrayOf("--target", "local", "--render"))
        assertEquals(3, provisional.exitCode)
        assertEquals("withheld-for-fidelity", provisional.review.targetDisposition)
    }
}
