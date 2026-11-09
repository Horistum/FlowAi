package org.flowlang.conformance

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.flowlang.compiler.CompilationResult
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.*
import org.flowlang.frontend.outcomes.*
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.serialization.FlowJson

/** Missing observations and wrong scoped addresses cannot become successful operation outcomes. */
internal class OperationOutcomeChecks(private val root: File) {
    private val corpus = File(root, "conformance/operation-outcomes")
    private val compiler by lazy { FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File(root, "modules")),
        StandardEnvironmentSafetyPolicyNotes.policy(root)) }
    private val cases = listOf("infrastructure", "data-copy", "credential-rotation")
    fun checks(): List<ConformanceCheck> = listOf(
        check("ai.outcomes.missing-observations") { cases.forEach { verify(it, false) } },
        check("ai.outcomes.wrong-resource") { cases.forEach { verify(it, true) } },
        check("ai.outcomes.schemas") {
            val schema = FlowJson.readTree(File(root, "schemas/operation-outcome-requirements.schema.json"))
            cases.forEach { JsonSchemaSmokeValidator.validate(FlowJson.readTree(File(corpus, "$it.requirements.json")), schema) }
        }
    )
    private fun check(name: String, action: () -> Unit) = runCatching(action).let {
        ConformanceCheck(name, it.isSuccess, it.exceptionOrNull()?.message)
    }
    private fun verify(id: String, wrongResource: Boolean) {
        val directory = Files.createTempDirectory("outcomes-conformance-").toFile()
        try {
            val result = ReviewedAiProposalFrontend(compiler).compile(ExternalAiProposalReader.read(File(corpus, "$id.proposal.json")).proposal)
            require(result is CompilationResult.Accepted) { "Outcome fixture must compile: $id/$result" }
            val requirements = FlowJson.readTree(File(corpus, "$id.requirements.json")) as ObjectNode
            requirements.put("graphDigest", result.unit.graphDigest.value)
            if (wrongResource) (requirements.path("resources")[0].path("identity") as ObjectNode).put("scope", "different-scope")
            val file = File(directory, "requirements.json").apply { writeText(requirements.toString()) }
            val review = OperationOutcomeReviewer.evaluate(result.unit, OperationOutcomeReader.requirements(file),
                evaluatedAt = Instant.parse("2026-11-08T02:10:00Z"))
            require(review.state == if (wrongResource) OperationOutcomeState.VIOLATED else OperationOutcomeState.UNRESOLVED) {
                "Invalid outcome acceptance: $id/${review.state}"
            }
            require(!review.executionAuthorized)
        } finally { directory.deleteRecursively() }
    }
}
