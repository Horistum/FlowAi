import kotlin.test.*
import com.fasterxml.jackson.databind.JsonNode
import org.flowlang.cli.Json
import org.flowlang.cli.honest.CliExecutionResult
import org.flowlang.cli.honest.CliPresentationItem
import org.flowlang.cli.honest.executeCli
import org.flowlang.verification.executeVerificationCli
import org.flowlang.conformance.AdapterTargetMaturityConformanceChecks
import org.flowlang.conformance.ArchitectureRecoveryConformanceRunner
import org.flowlang.conformance.ConformanceRunner

class TargetMaturityCompositionTests {
    @Test
    fun targetsCommandPublishesCurrentMaturityReport() {
        for (execute in listOf<(Array<String>) -> CliExecutionResult>(::executeCli, ::executeVerificationCli)) {
            val result = assertIs<CliExecutionResult.Completed>(execute(arrayOf("targets")))
            assertEquals(0, result.exitCode)
            val sections = result.presentation.items.filterIsInstance<CliPresentationItem.Section>()
            assertTrue(sections.any { it.title == "FLOW TARGET CAPABILITY DECLARATIONS" })
            val report = Json.mapper.valueToTree<JsonNode>(sections.single { it.title == "FLOW TARGET MATURITY REPORT" }.value)
            assertEquals("2.0", report["reportVersion"].asText())
            assertEquals("PASS", report["status"].asText())
            assertEquals("REPOSITORY_REFERENCE_INVENTORY", report["evidenceBasis"].asText())
            assertEquals(false, report["runtimeCertificationAssessed"].booleanValue())
            assertEquals(false, report["executionAuthorized"].booleanValue())
            assertFalse(report.toString().contains("BEHAVIORALLY_CERTIFIED"))
            val targets = report["assessments"].associateBy { it["target"].asText() }
            for ((target, construct) in listOf("jenkins" to "PARALLEL", "github-actions" to "CONDITION")) {
                val mapping = targets.getValue(target)["structuralProjections"].single { it["structure"].asText() == construct }
                assertEquals("PLAN_SCOPED", mapping["supportScope"].asText())
            }
            assertEquals(0, targets.getValue("argo-workflows")["structuralProjections"].size())
            assertTrue(result.artifacts.isEmpty())
        }
    }

    @Test
    fun standaloneConformanceIncludesArchitectureRecoveryPhase() {
        val summary = ConformanceRunner().run()
        val names = summary.checks.map { it.name }
        val inventoryIndex = names.indexOf(ArchitectureRecoveryConformanceRunner.INVENTORY_CHECK)
        val publicationIndex = names.indexOf(
            AdapterTargetMaturityConformanceChecks.PUBLICATION_CHECK
        )

        assertTrue(inventoryIndex >= 0, names.joinToString())
        assertTrue(publicationIndex > inventoryIndex, names.joinToString())
        assertTrue(
            summary.checks
                .filter { it.name.startsWith("architecture-recovery.") }
                .all { it.passed },
            summary.checks.filterNot { it.passed }.joinToString { "${it.name}:${it.message}" }
        )
    }
}
