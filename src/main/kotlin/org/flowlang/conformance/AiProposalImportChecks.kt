package org.flowlang.conformance

import java.io.File
import org.flowlang.cli.honest.ProposalCliResult
import org.flowlang.cli.honest.executeCli
import org.flowlang.compiler.CompilationResult
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.ExternalAiProposalReader
import org.flowlang.frontend.ai.ReviewedAiProposalFrontend
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.modules.ModuleRegistry

internal class AiProposalImportChecks(private val root: File) {
    fun checks(): List<ConformanceCheck> = listOf(
        check("ai.proposal.import-convergence") {
            val compiler = FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File(root, "modules")))
            val imported = ExternalAiProposalReader.read(File(root, "examples/proposals/accepted.json"))
            val proposal = (ReviewedAiProposalFrontend(compiler).compile(imported.proposal) as CompilationResult.Accepted).unit
            val direct = (IntentYamlFrontend(compiler).compile(File(root, "examples/intent/checkout-build-image.intent.yaml")) as CompilationResult.Accepted).unit
            require(proposal.graphDigest == direct.graphDigest && proposal.source.sha256 != direct.source.sha256)
        },
        check("ai.proposal.compiler-rejection") {
            val result = executeCli(arrayOf("proposal", File(root, "examples/proposals/rejected.json").path)) as ProposalCliResult
            require(result.exitCode == 4 && result.review.semanticAcceptance == "rejected")
            require(result.review.diagnostics.any { it.code == "UNKNOWN_STEP_DEPENDENCY" })
            require(result.review.graphDigest == null && result.review.requestFidelity == "unassessed")
        },
        check("ai.proposal.existing-image-blocker") {
            val result = executeCli(arrayOf("proposal", File(root, "examples/proposals/existing-image-blocked.json").path,
                "--target", "github-actions", "--render")) as ProposalCliResult
            require(result.exitCode in setOf(3, 4) && !result.review.renderAuthorized)
            require(result.review.requestFidelity == "unassessed")
        }
    )

    private fun check(name: String, verify: () -> Unit): ConformanceCheck {
        val result = runCatching(verify)
        return ConformanceCheck(name, result.isSuccess, result.exceptionOrNull()?.message)
    }
}
