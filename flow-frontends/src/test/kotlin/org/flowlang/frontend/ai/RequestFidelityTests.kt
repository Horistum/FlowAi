package org.flowlang.frontend.ai

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.cli.Json
import org.flowlang.compiler.CompilationResult
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes

class RequestFidelityTests {
    private val corpus = File("conformance/authoring")
    private val compiler = FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File("modules")), StandardEnvironmentSafetyPolicyNotes.policy(File(".")))
    private fun tree(id: String, suffix: String = "proposal") = Json.mapper.readTree(File(corpus, "$id.$suffix.json")) as ObjectNode
    private fun steps(proposal: ObjectNode) = proposal.path("response").path("normalizedIntent").path("workflows")[0].path("steps") as ArrayNode
    private fun workspace(action: (File) -> Unit) {
        val root = createTempDirectory("request-fidelity-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
    private fun unit(root: File, proposal: ObjectNode): org.flowlang.compiler.CompilationUnit {
        val input = File(root, "proposal.json").apply { writeText(proposal.toString()) }
        val result = ReviewedAiProposalFrontend(compiler).compile(ExternalAiProposalReader.read(input).proposal)
        return assertIs<CompilationResult.Accepted>(result, result.toString()).unit
    }
    private fun evaluate(root: File, proposal: ObjectNode, requirements: ObjectNode): RequestFidelityReview {
        val packet = File(root, "requirements.json").apply { writeText(requirements.toString()) }
        return RequestFidelityEvaluator.evaluate(RequestRequirementsReader.read(packet), unit(root, proposal))
    }

    @Test fun twelveIndependentCasesPreserveTheirExpectedBoundedOutcomes() = workspace { root ->
        val cases = Json.mapper.readTree(File(corpus, "corpus.json")).path("cases")
        assertEquals(12, cases.size())
        cases.forEach { case ->
            val id = case.path("id").asText()
            val review = evaluate(root, tree(id), tree(id, "requirements"))
            assertEquals(case.path("expectedFidelity").asText(), review.state.name, "$id: $review")
            assertEquals("unassessed", review.naturalLanguageCompleteness)
            assertNotNull(review.graphDigest)
        }
    }

    @Test fun independentOracleDetectsChangedDigestRemovedStepAndBroadenedEnvironment() = workspace { root ->
        val expected = tree("deployment-specified", "requirements")
        val digest = tree("deployment-specified").also { (steps(it)[1].path("params") as ObjectNode).put("image", "registry.example.invalid/other@sha256:" + "b".repeat(64)) }
        val removed = tree("deployment-specified").also { steps(it).remove(2) }
        val environment = tree("deployment-specified").also { (steps(it)[1].path("params") as ObjectNode).put("environment", "test") }
        for ((input, id) in listOf(digest to "digest", removed to "verify", environment to "environment")) {
            val review = evaluate(root, input, expected)
            assertEquals(RequestFidelityState.VIOLATED, review.state)
            assertEquals(FidelityRequirementState.VIOLATED, review.requirements.single { it.id == id }.state)
        }
    }

    @Test fun verificationOfAnotherResourceCannotStandInForTheRequestedCheck() = workspace { root ->
        for (id in listOf("deployment-specified", "backup-specified", "certificate-specified")) {
            val candidate = tree(id)
            val verification = steps(candidate).single { it.path("capability").asText() == "VERIFY" }
            (verification.path("params") as ObjectNode).put("resource", "unrelated-resource")
            val review = evaluate(root, candidate, tree(id, "requirements"))
            assertEquals(RequestFidelityState.VIOLATED, review.state, id)
            assertEquals(FidelityRequirementState.VIOLATED, review.requirements.single { it.id == "verified-resource" }.state, id)
        }
    }

    @Test fun stateProhibitionCoversUpsertDeletionAndExecutionBeyondNamedMaintenanceCapabilities() = workspace { root ->
        for ((capability, requirement) in listOf("PROVISION" to "no-state-upsert", "DEPROVISION" to "no-state-delete", "RUNBOOK" to "no-state-execute")) {
            val proposal = tree("certificate-prohibitions")
            if (capability == "DEPROVISION") steps(proposal).addObject().put("id", "deletion-approval").put("capability", "APPROVE")
            val operation = steps(proposal).addObject().put("id", "invented-effect").put("capability", capability)
            if (capability == "DEPROVISION") {
                operation.putArray("requires").add("deletion-approval")
                operation.putObject("params").put("target", "edge.example.invalid")
            }
            if (capability == "RUNBOOK") operation.putObject("params").put("description", "Unrequested maintenance")
            val review = evaluate(root, proposal, tree("certificate-prohibitions", "requirements"))
            assertEquals(RequestFidelityState.VIOLATED, review.state, capability)
            assertEquals(FidelityRequirementState.VIOLATED, review.requirements.single { it.id == requirement }.state, capability)
        }
    }

    @Test fun addedBuildIsRejectedEvenWhenTheProviderClaimsNoProblems() = workspace { root ->
        val proposal = tree("deployment-prohibitions")
        steps(proposal).add(Json.mapper.readTree("""{"id":"invented-build","capability":"BUILD_IMAGE","params":{"image":"unrequested"}}"""))
        val review = evaluate(root, proposal, tree("deployment-prohibitions", "requirements"))
        assertEquals(RequestFidelityState.VIOLATED, review.state)
        for (id in listOf("no-image-build", "no-image-effect"))
            assertEquals(FidelityRequirementState.VIOLATED, review.requirements.single { it.id == id }.state)
    }

    @Test fun approvalMustPrecedeTheActualOperationInItsOwnWorkflow() = workspace { root ->
        val expected = tree("deployment-specified", "requirements")
        val late = tree("deployment-specified")
        val sequence = steps(late)
        (sequence[1] as ObjectNode).putArray("requires")
        (sequence[0] as ObjectNode).putArray("requires").add("deploy")
        assertEquals(FidelityRequirementState.VIOLATED, evaluate(root, late, expected).requirements.single { it.id == "approval-first" }.state)
        val unrelated = tree("deployment-specified")
        val original = steps(unrelated)
        val approval = original.remove(0)
        (original[0] as ObjectNode).putArray("requires")
        val other = Json.mapper.createObjectNode().put("name", "unrelated").put("kind", "CUSTOM")
        other.putArray("steps").add(approval)
        (unrelated.path("response").path("normalizedIntent").path("workflows") as ArrayNode).add(other)
        assertEquals(FidelityRequirementState.VIOLATED, evaluate(root, unrelated, expected).requirements.single { it.id == "approval-first" }.state)
    }

    @Test fun identifiersDoNotSubstituteForCanonicalCapabilityMeaning() = workspace { root ->
        val renamed = tree("deployment-specified")
        steps(renamed).forEach {
            val node = it as ObjectNode
            node.put("id", node.path("id").asText() + "-renamed")
            val dependencies = node.path("requires").map { it.asText() + "-renamed" }
            node.putArray("requires").also { values -> dependencies.forEach(values::add) }
        }
        assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, evaluate(root, renamed, tree("deployment-specified", "requirements")).state)
    }

    @Test fun dynamicValuesCannotPassAnExactLiteralRequirementFromTheirDefaults() = workspace { root ->
        val proposal = tree("deployment-specified")
        val intent = proposal.path("response").path("normalizedIntent") as ObjectNode
        intent.putArray("inputs").addObject().put("name", "image").put("type", "text").put("default", steps(proposal)[1].path("params").path("image").asText())
        (steps(proposal)[1].path("params") as ObjectNode).put("image", "ref:image")
        val review = evaluate(root, proposal, tree("deployment-specified", "requirements"))
        assertEquals(RequestFidelityState.REVIEW_REQUIRED, review.state)
        assertEquals(FidelityRequirementState.REVIEW_REQUIRED, review.requirements.single { it.id == "digest" }.state)
    }

    @Test fun provisionalExtractionCannotGrantAFidelityPass() = workspace { root ->
        val packet = tree("deployment-specified", "requirements").put("origin", "MODEL_EXTRACTED").put("confirmation", "PROVISIONAL").putNull("reviewerId")
        val review = evaluate(root, tree("deployment-specified"), packet)
        assertTrue(review.requirements.all { it.state == FidelityRequirementState.SATISFIED })
        assertEquals(RequestFidelityState.REVIEW_REQUIRED, review.state)
    }

    @Test fun capturedRequirementsCannotBeChangedByReplacingTheFile() = workspace { root ->
        val file = File(root, "requirements.json").apply { writeText(tree("deployment-specified", "requirements").toString()) }
        val captured = RequestRequirementsReader.read(file)
        file.writeText("{\"confirmation\":\"PROVISIONAL\"}")
        assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, RequestFidelityEvaluator.evaluate(captured, unit(root, tree("deployment-specified"))).state)
        assertEquals(64, captured.source.sha256.length)
    }

    @Test fun callerMutationOfCompiledRequestEvidenceCannotRebindTheOracle() = workspace { root ->
        val proposal = tree("deployment-specified")
        (proposal.path("request").path("context") as ObjectNode).putObject("knownSystems").put("one", "git").put("two", "standard")
        val compiled = unit(root, proposal)
        val packet = tree("deployment-specified", "requirements")
        packet.set<ObjectNode>("request", proposal.path("request").deepCopy<ObjectNode>())
        val file = File(root, "requirements.json").apply { writeText(packet.toString()) }
        val captured = RequestRequirementsReader.read(file)
        assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, RequestFidelityEvaluator.evaluate(captured, compiled).state)
        val systems = compiled.requireReviewedAiProposalEvidence().request.context.knownSystems
        (systems as MutableMap<String, String>)["one"] = "changed-after-compilation"
        assertFailsWith<IllegalArgumentException> { RequestFidelityEvaluator.evaluate(captured, compiled) }
    }

    @Test fun originalRequestContextAndModeMustMatchTheIndependentOracle() = workspace { root ->
        for (field in listOf("userText", "context", "mode")) {
            val proposal = tree("deployment-specified")
            val request = proposal.path("request") as ObjectNode
            when (field) {
                "userText" -> request.put(field, request.path(field).asText() + " Changed request.")
                "context" -> request.putObject(field).put("target", "jenkins")
                else -> request.put(field, "EXPLAIN")
            }
            val failure = assertFailsWith<RequestRequirementsException> { evaluate(root, proposal, tree("deployment-specified", "requirements")) }
            assertEquals("FIDELITY_REQUEST_MISMATCH", failure.code)
        }
    }

    @Test fun malformedRequirementsCannotSelfConfirmOrLeakAuthoredValues() = workspace { root ->
        val valid = tree("deployment-specified", "requirements").toString()
        val variants = listOf(
            valid.replaceFirst("{", "{\"formatVersion\":1,"), valid + "{}",
            valid.replace("\"formatVersion\":1", "\"formatVersion\":4"),
            valid.replace("\"CONFIRMED\"", "\"credential-do-not-echo\""),
            valid.replace("\"repository-test-oracle\"", "null"),
            valid.replace("\"capability-count\"", "\"arbitrary-check\""),
            valid.replace("\"minimum\":1", "\"minimum\":\"1\""),
            valid.replace("\"manual approval\"", "\"uncited text\""),
            valid.replace("\"requirements\":[", "\"requirements\":[null,"),
            valid.replace("\"selector\":{", "\"forgedPass\":true,\"selector\":{")
        )
        for (variant in variants) {
            val file = File(root, "malformed.json").apply { writeText(variant) }
            val failure = assertFailsWith<RequestRequirementsException> { RequestRequirementsReader.read(file) }
            assertFalse(failure.message.orEmpty().contains("credential-do-not-echo"))
        }
    }
}
