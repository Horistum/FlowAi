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
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowJson
import kotlin.io.path.createTempDirectory

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
        },
        check("ai.proposal.system-resource-fidelity") {
            val vector = FlowYaml.readMap(File(root, "architecture-recovery/conformance/system-automation-fidelity.yaml"))
            val expected = vector["expected"] as Map<*, *>
            require(expected["requiredCheck"] == "ai.proposal.system-resource-fidelity")
            val cases = vector["cases"] as List<*>
            require(cases == listOf("infrastructure-foundation", "data-recovery-rehearsal", "environment-retirement"))
            for (case in cases) {
                val result = executeCli(arrayOf("proposal", File(root, "examples/system-automation/$case.proposal.json").path,
                    "--requirements", File(root, "examples/system-automation/$case.requirements.json").path)) as ProposalCliResult
                require(result.exitCode == 0 && result.review.semanticAcceptance == expected["semanticAcceptance"])
                require(result.review.requestFidelity == expected["requestFidelity"] && result.review.graphDigest != null)
                require(result.review.renderAuthorized == expected["renderAuthorized"])
            }
            val temporary = createTempDirectory("system-fidelity-vector-").toFile()
            try {
                val candidate = FlowJson.readTree(File(root, "examples/system-automation/infrastructure-foundation.proposal.json"))
                val compute = candidate.path("response").path("normalizedIntent").path("workflows")[0].path("steps")
                    .single { it.path("id").asText() == "compute" }
                (compute.path("params").path("stack") as com.fasterxml.jackson.databind.node.ObjectNode)
                    .put("network", "ref:identity_state")
                val file = File(temporary, "wrong-producer.json").apply { writeText(candidate.toString()) }
                val result = executeCli(arrayOf("proposal", file.path, "--requirements",
                    File(root, "examples/system-automation/infrastructure-foundation.requirements.json").path)) as ProposalCliResult
                require(result.exitCode == 4 && result.review.semanticAcceptance == expected["semanticAcceptance"])
                require(result.review.requestFidelity == expected["wrongProducerFidelity"] && !result.review.renderAuthorized)
            } finally { temporary.deleteRecursively() }
        },
        check("ai.proposal.unavailable-target-preserves-plan") {
            val vector = FlowYaml.readMap(File(root, "architecture-recovery/conformance/proposal-target-readiness.yaml"))
            val expected = vector["expected"] as Map<*, *>
            require(expected["requiredCheck"] == "ai.proposal.unavailable-target-preserves-plan")
            require(expected["targetProfiles"] == listOf("local", "argo-workflows", "azure-devops"))
            for (target in listOf("local", "argo-workflows", "azure-devops")) {
                val result = executeCli(arrayOf("proposal", File(root, "examples/proposals/faithful.json").path,
                    "--requirements", File(root, "examples/proposals/reviewed-requirements.json").path,
                    "--target", target, "--render")) as ProposalCliResult
                require(result.exitCode == 4 && result.review.stage == "MATERIALIZATION")
                require(result.review.semanticAcceptance == "accepted" && result.review.requestFidelity == "requirements-satisfied")
                require(result.review.targetDisposition == "blocked" && !result.review.renderAuthorized)
                require(result.review.graphDigest != null && "canonical-execution-plan.json" in result.review.artifacts)
                require("target-manifest.json" !in result.review.artifacts)
                require(result.review.diagnostics.any { it.code == "PROPOSAL_TARGET_PROJECTION_UNAVAILABLE" })
                require(expected["exitCode"] == result.exitCode && expected["stage"] == result.review.stage)
                require(expected["semanticAcceptance"] == result.review.semanticAcceptance &&
                    expected["requestFidelity"] == result.review.requestFidelity)
                require(expected["targetDisposition"] == result.review.targetDisposition &&
                    expected["renderAuthorized"] == result.review.renderAuthorized)
                require(result.review.diagnostics.any { it.code == expected["diagnostic"] })
            }
        }
    )

    private fun check(name: String, verify: () -> Unit): ConformanceCheck {
        val result = runCatching(verify)
        return ConformanceCheck(name, result.isSuccess, result.exceptionOrNull()?.message)
    }
}
