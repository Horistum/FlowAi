package org.flowlang.cli.honest

import java.io.File
import kotlin.test.*
import org.flowlang.cli.Json
import org.flowlang.verification.executeVerificationCli

class ContractDistributionCliCompatibilityTests {
    @Test fun packagedAndExplicitRepositoryContractsPreserveBothHostTargetOutcomes() {
        for (execute in listOf<(Array<String>) -> CliExecutionResult>(::executeCli, ::executeVerificationCli)) {
            for (target in listOf("jenkins", "github-actions")) {
                val args = arrayOf("intent", "examples/intent/checkout-build-image.intent.yaml", "--target", target, "--render")
                val packaged = assertIs<CliExecutionResult.Targeted>(execute(args))
                val external = assertIs<CliExecutionResult.Targeted>(execute(args + arrayOf("--contracts", File(".").absolutePath)))
                assertEquals(Json.mapper.writeValueAsString(external.evidence), Json.mapper.writeValueAsString(packaged.evidence))
                assertEquals(external.artifacts, packaged.artifacts)
                assertEquals(if (target == "jenkins") 0 else 3, packaged.exitCode)
                assertEquals(external.exitCode, packaged.exitCode)
                if (target == "github-actions") {
                    assertEquals(CliTargetEvidenceOutcome.REVIEW_ONLY, packaged.evidence.outcome)
                    assertTrue(packaged.artifacts.none { it.role == CliArtifactRole.RENDERED_TARGET })
                }
            }
        }
    }
}
