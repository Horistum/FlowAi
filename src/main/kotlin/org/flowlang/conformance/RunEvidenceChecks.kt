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

/** Independently authored run obligations cannot be satisfied by a plan alone. */
internal class RunEvidenceChecks(private val root: File) {
    private val corpus = File(root, "conformance/run-evidence")
    private val compiler by lazy { FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File(root,"modules")), StandardEnvironmentSafetyPolicyNotes.policy(root)) }
    private val cases = listOf("service-readiness", "data-recovery", "credential-recovery")
    fun checks(): List<ConformanceCheck> = listOf(
        check("ai.run-evidence.missing-run") { cases.forEach { verify(it, false) } },
        check("ai.run-evidence.wrong-region") { cases.forEach { verify(it, true) } },
        check("ai.run-evidence.schemas") {
            val schema = FlowJson.readTree(File(root,"schemas/operation-outcome-requirements-v2.schema.json"))
            cases.forEach { JsonSchemaSmokeValidator.validate(FlowJson.readTree(File(corpus,"$it.requirements.json")),schema) }
        }
    )
    private fun check(name: String, action: () -> Unit) = runCatching(action).let { ConformanceCheck(name,it.isSuccess,it.exceptionOrNull()?.message) }
    private fun verify(id: String, wrong: Boolean) {
        val temp = Files.createTempDirectory("run-evidence-conformance-").toFile()
        try {
            val compiled = ReviewedAiProposalFrontend(compiler).compile(ExternalAiProposalReader.read(File(corpus,"$id.proposal.json")).proposal)
            require(compiled is CompilationResult.Accepted)
            val requirements = FlowJson.readTree(File(corpus,"$id.requirements.json")) as ObjectNode
            requirements.put("graphDigest",compiled.unit.graphDigest.value)
            if (wrong) (requirements.path("execution").path("recoveries")[0] as ObjectNode).put("disposition","PROPAGATE").put("expected","PROPAGATED")
            val file = File(temp,"requirements.json").apply {writeText(requirements.toString())}
            val review = OperationOutcomeReviewer.evaluate(compiled.unit,OperationOutcomeReader.requirements(file),evaluatedAt=Instant.parse("2026-11-08T08:10:00Z"))
            require(review.state == if (wrong) OperationOutcomeState.VIOLATED else OperationOutcomeState.UNRESOLVED)
            require(!review.executionAuthorized && review.formatVersion == 2)
        } finally {temp.deleteRecursively()}
    }
}
