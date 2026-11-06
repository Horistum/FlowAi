package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import org.flowlang.compiler.CompilationResult
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.*
import org.flowlang.intent.IntentDocument
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.scenarios.ScenarioPackRegistry
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.serialization.FlowJson

/** Repository-owned test expectations; no candidate model is invoked or allowed to rewrite the oracle. */
internal class RequestFidelityCorpus(private val root: File) {
    private val directory = File(root, "conformance/authoring")
    private val compiler by lazy { FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File(root, "modules")), StandardEnvironmentSafetyPolicyNotes.policy(root)) }

    fun checks(): List<ConformanceCheck> = listOf(
        check("ai.fidelity.independent-corpus", ::verifyInventory),
        check("ai.fidelity.bounded-outcomes", ::verifyOutcomes),
        check("ai.fidelity.constraint-mutations", ::verifyMutations)
    )

    private fun check(name: String, action: () -> Unit): ConformanceCheck {
        val result = runCatching(action)
        return ConformanceCheck(name, result.isSuccess, result.exceptionOrNull()?.message)
    }

    private fun cases(): List<JsonNode> = FlowJson.readTree(File(directory, "corpus.json")).path("cases").toList()
    private fun file(case: JsonNode, field: String): File {
        val name = case.path(field).asText()
        require(name.matches(Regex("[a-z][a-z0-9-]*\\.(requirements|proposal)\\.json"))) { "Unsafe authoring corpus path." }
        return File(directory, name).also { require(it.isFile && !Files.isSymbolicLink(it.toPath())) { "Missing regular authoring corpus file." } }
    }
    private fun captured(case: JsonNode): CapturedRequestRequirements {
        val captured = RequestRequirementsReader.read(file(case, "requirements"))
        require(captured.source.sha256 == case.path("requirementsSha256").asText()) { "Independent requirement bytes changed: ${case.path("id").asText()}" }
        return captured
    }

    fun verifyInventory() {
        val manifest = FlowJson.readTree(File(directory, "corpus.json"))
        require(manifest.fieldNames().asSequence().toSet() == setOf("formatVersion", "purpose", "oracleProvenance", "modelEvaluated", "heldOutPolicy", "cases"))
        require(manifest.path("formatVersion").isIntegralNumber && manifest.path("formatVersion").intValue() == 1)
        require(manifest.path("modelEvaluated").isBoolean && !manifest.path("modelEvaluated").booleanValue())
        require(listOf("purpose", "oracleProvenance", "heldOutPolicy").all { manifest.path(it).isTextual && manifest.path(it).asText().isNotBlank() })
        val cases = cases()
        require(cases.size == 12 && cases.map { it.path("id").asText() }.distinct().size == 12)
        require(cases.count { it.path("split").asText() == "held-out" } == 6 && cases.count { it.path("split").asText() == "development" } == 6)
        require(cases.map { it.path("requirements").asText() }.distinct().size == 12 &&
            cases.map { it.path("proposal").asText() }.distinct().size == 12)
        val requests = mutableSetOf<String>()
        val domains = cases.groupBy { it.path("domain").asText() }
        require(domains.keys == setOf("deployment", "backup-restore", "certificate-maintenance"))
        domains.values.forEach { domain ->
            require(domain.size == 4 && domain.map { it.path("category").asText() }.toSet() == setOf("specified", "ambiguous", "prohibitions", "unsupported"))
            require(domain.count { it.path("split").asText() == "held-out" } == 2)
        }
        cases.forEach { case ->
            require(case.fieldNames().asSequence().toSet() == setOf("id", "domain", "category", "split", "requirements", "requirementsSha256", "proposal", "expectedFidelity", "expectedExit", "structuralDifference"))
            require(case.fields().asSequence().all { (key, value) -> if (key == "expectedExit") value.isIntegralNumber else value.isTextual })
            val id = case.path("id").asText()
            require(id.matches(Regex("[a-z][a-z0-9-]*")))
            require(case.path("requirements").asText() == "$id.requirements.json" && case.path("proposal").asText() == "$id.proposal.json")
            require(case.path("structuralDifference").asText().isNotBlank())
            require(case.path("requirementsSha256").asText().matches(Regex("[a-f0-9]{64}")))
            val expected = RequestFidelityState.valueOf(case.path("expectedFidelity").asText())
            require(case.path("expectedExit").isIntegralNumber && case.path("expectedExit").intValue() == when (expected) {
                RequestFidelityState.REQUIREMENTS_SATISFIED -> 0
                RequestFidelityState.REVIEW_REQUIRED -> 3
                RequestFidelityState.UNSUPPORTED -> 4
                else -> error("Corpus baseline must not disguise an accidental violation or compilation failure.")
            })
            val request = ExternalAiProposalReader.read(file(case, "proposal")).proposal.request
            require(requests.add(request.userText)) { "Corpus requests must be independently distinct." }
            captured(case).requireRequest(request)
        }
    }

    fun verifyOutcomes() {
        verifyInventory()
        val baselineShapes = ScenarioPackRegistry.packs.flatMap { pack -> pack.definition.exampleRequests.map { text ->
            val request = AiIntentRequest(text)
            shape(pack.normalize(request, pack.match(text, request.context)).intent)
        } }.toSet()
        require(baselineShapes.isNotEmpty())
        var novel = 0
        cases().forEach { case ->
            val proposal = ExternalAiProposalReader.read(file(case, "proposal")).proposal
            val result = ReviewedAiProposalFrontend(compiler).compile(proposal)
            require(result is CompilationResult.Accepted) { "Corpus proposal no longer compiles: ${case.path("id").asText()}" }
            val review = RequestFidelityEvaluator.evaluate(captured(case), result.unit)
            require(review.state.name == case.path("expectedFidelity").asText()) { "Unexpected fidelity outcome for ${case.path("id").asText()}: ${review.state}" }
            require(review.naturalLanguageCompleteness == "unassessed" && review.graphDigest == result.unit.graphDigest.value)
            if (shape(proposal.response.normalizedIntent) !in baselineShapes) novel++
        }
        require(novel >= 6) { "Only $novel corpus cases differ structurally from registered scenario-pack examples." }
    }

    /** Ignore names and literal values; retain workflow partition, capability multiplicity and dependency endpoints. */
    private fun shape(intent: IntentDocument): String = intent.workflows.map { workflow ->
        val capabilities = workflow.steps.associate { it.id to it.capability.name }
        val edges = workflow.steps.flatMap { step -> step.requires.map { dependency -> "${capabilities[dependency]}->${step.capability.name}" } }.sorted()
        workflow.steps.map { it.capability.name }.sorted().joinToString(",") + ":" + edges.joinToString(",")
    }.sorted().joinToString("|")

    fun verifyMutations() {
        verifyInventory()
        val temporary = createTempDirectory("fidelity-mutations-").toFile()
        try {
            fun steps(tree: ObjectNode) = tree.path("response").path("normalizedIntent").path("workflows")[0].path("steps") as ArrayNode
            val mutations: List<Triple<String, String, (ObjectNode) -> Unit>> = listOf(
                Triple("deployment-specified", "digest") { (steps(it)[1].path("params") as ObjectNode).put("image", "different-digest") },
                Triple("deployment-specified", "environment") { (steps(it)[1].path("params") as ObjectNode).put("environment", "test") },
                Triple("deployment-specified", "verify") { steps(it).remove(2) },
                Triple("deployment-specified", "verified-resource") { (steps(it)[2].path("params") as ObjectNode).put("resource", "unrelated-resource") },
                Triple("backup-specified", "verified-resource") { (steps(it)[1].path("params") as ObjectNode).put("resource", "unrelated-resource") },
                Triple("certificate-specified", "verified-resource") { (steps(it)[1].path("params") as ObjectNode).put("resource", "unrelated-resource") },
                Triple("deployment-specified", "approval-first") {
                    (steps(it)[1] as ObjectNode).putArray("requires")
                    (steps(it)[0] as ObjectNode).putArray("requires").add("deploy")
                },
                Triple("deployment-prohibitions", "no-image-build") { steps(it).addObject().put("id", "invented-build").put("capability", "BUILD_IMAGE") }
            )
            mutations.forEach { (id, requirement, mutate) ->
                val case = cases().single { it.path("id").asText() == id }
                val tree = FlowJson.readTree(file(case, "proposal")) as ObjectNode
                mutate(tree)
                val modified = File(temporary, "proposal.json").apply { writeText(tree.toString()) }
                val proposal = ExternalAiProposalReader.read(modified).proposal
                val result = ReviewedAiProposalFrontend(compiler).compile(proposal)
                require(result is CompilationResult.Accepted) { "Mutation must reach fidelity review: $requirement" }
                val review = RequestFidelityEvaluator.evaluate(captured(case), result.unit)
                require(review.state == RequestFidelityState.VIOLATED && review.requirements.single { it.id == requirement }.state == FidelityRequirementState.VIOLATED) {
                    "Controlled mutation escaped its independent requirement: $requirement"
                }
            }
        } finally { temporary.deleteRecursively() }
    }
}
