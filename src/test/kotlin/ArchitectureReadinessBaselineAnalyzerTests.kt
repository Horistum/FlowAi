import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.architecture.ArchitectureReadinessBaselineAnalyzer
import org.flowlang.standard.FlowStandardVersions

class ArchitectureReadinessBaselineAnalyzerTests {
    @Test
    fun repositoryBaselineIsDerivedFromLiveArchitecture() {
        val report = ArchitectureReadinessBaselineAnalyzer(File(".")).analyze()

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertTrue(report.productionKotlinTypeCount > 0)
        assertTrue(report.authorityCount > 0)
        assertTrue(report.conformanceCheckCount > 0)
        assertTrue(report.lexicalArchitectureCheckCount > 0)
        assertTrue(report.lexicalArchitectureTermCount >= report.lexicalArchitectureCheckCount)
        assertTrue(report.coreToAdapterDependencyViolations.isEmpty())
        assertEquals(FlowStandardVersions.ARTIFACT_CONTRACT_VERSIONS, report.publicArtifactContracts)

        println(
            "AR0.2_BASELINE " +
                "productionKotlinTypeCount=${report.productionKotlinTypeCount} " +
                "authorityCount=${report.authorityCount} " +
                "conformanceCheckCount=${report.conformanceCheckCount} " +
                "lexicalArchitectureCheckCount=${report.lexicalArchitectureCheckCount} " +
                "lexicalArchitectureTermCount=${report.lexicalArchitectureTermCount} " +
                "stringlyTypedSemanticCandidateCount=${report.stringlyTypedSemanticCandidates.size}"
        )
        report.stringlyTypedSemanticCandidates.forEach { println("AR0.2_STRING_CANDIDATE $it") }
    }

    @Test
    fun baselinePublishesEachArtifactContractAxisIndependently() {
        val report = ArchitectureReadinessBaselineAnalyzer(File(".")).analyze()

        assertEquals("2.0", report.publicArtifactContracts["intent"])
        assertEquals("2.2", report.publicArtifactContracts["ast"])
        assertEquals("2.4", report.publicArtifactContracts["executionPlan"])
        assertEquals("1.0", report.publicArtifactContracts["workflowExecutionPlanSet"])
        assertEquals("2.1", report.publicArtifactContracts["executionPlanLoweringEvidence"])
        assertEquals("3.0", report.publicArtifactContracts["targetManifest"])
        assertEquals("3.2", report.publicArtifactContracts["targetRegistry"])
    }
}
