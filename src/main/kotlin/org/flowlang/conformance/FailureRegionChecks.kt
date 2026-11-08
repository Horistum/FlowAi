package org.flowlang.conformance

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.nio.file.Files
import org.flowlang.compiler.CompilationResult
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.*
import org.flowlang.modules.ModuleRegistry
import org.flowlang.serialization.FlowJson

/** Independent fixtures share only public input and semantic resource identities with candidates. */
internal class FailureRegionChecks(private val root: File) {
    private val corpus = File(root, "conformance/failure-regions")
    private val compiler by lazy { FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File(root, "modules"))) }
    private val cases = listOf("infrastructure-readiness", "isolated-data-recovery", "credential-maintenance")
    fun checks(): List<ConformanceCheck> = listOf(
        check("ai.recovery.portfolio") { cases.forEach { verify(it) } },
        check("ai.recovery.disposition-mutation") { verify("infrastructure-readiness", mutation = true) },
        check("ai.recovery.schemas") {
            val schema = FlowJson.readTree(File(root, "schemas/intent-v3.1.schema.json"))
            val requirements = FlowJson.readTree(File(root, "schemas/request-requirements-v4.schema.json"))
            cases.forEach { id ->
                JsonSchemaSmokeValidator.validate(FlowJson.readTree(File(corpus, "$id.intent.json")), schema)
                JsonSchemaSmokeValidator.validate(FlowJson.readTree(File(corpus, "$id.requirements.json")), requirements)
            }
        }
    )
    private fun check(name: String, action: () -> Unit): ConformanceCheck = runCatching(action).let {
        ConformanceCheck(name, it.isSuccess, it.exceptionOrNull()?.message)
    }
    private fun verify(id: String, mutation: Boolean = false) {
        val directory = Files.createTempDirectory("structured-conformance-").toFile()
        try {
            val tree = FlowJson.readTree(File(corpus, "$id.proposal.json")) as ObjectNode
            if (mutation) (tree.path("response").path("normalizedIntent").path("workflows")[0].path("body").last() as ObjectNode).put("disposition", "recover")
            val file = File(directory, "proposal.json").apply { writeText(tree.toString()) }
            val result = ReviewedAiProposalFrontend(compiler).compile(ExternalAiProposalReader.read(file).proposal)
            require(result is CompilationResult.Accepted) { "Failure-region candidate must reach an authorized graph: $id" }
            val review = RequestFidelityEvaluator.evaluate(RequestRequirementsReader.read(File(corpus, "$id.requirements.json")), result.unit)
            require(review.state == if (mutation) RequestFidelityState.VIOLATED else RequestFidelityState.REQUIREMENTS_SATISFIED) {
                "Failure-region independent obligations did not detect the represented meaning: $id/${review.state}"
            }
        } finally { directory.deleteRecursively() }
    }
}
