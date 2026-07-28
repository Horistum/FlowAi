import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.portfolio.AdapterPortfolioAuthority
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.AdapterSupportClass
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.conformance.AdapterConformanceRunner
import org.flowlang.conformance.AdapterPortfolioConformanceChecks
import org.flowlang.conformance.ConformanceRunner
import org.flowlang.conformance.ConformanceSuiteInventory
import org.flowlang.release.SemanticClosureAuthority
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterPortfolioReassessmentTests {
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
    private val authority = AdapterPortfolioAuthority(File("."), targets, BuiltInTargetProjections.registry)
    private val document = AdapterPortfolioLoader.load()

    @Test
    fun builtInPortfolioReassessmentMatchesActualComposition() {
        val report = authority.evaluate(document)

        assertEquals("PASS", report.status, report.findings.joinToString { "${it.code}:${it.target}:${it.message}" })
        assertEquals(targets.keys.sorted(), report.assessments.map { it.target })
        assertEquals(AdapterSupportClass.PROFILE_ONLY, report.assessments.single { it.target == "local" }.supportClass)
        assertEquals(AdapterSupportClass.EXECUTABLE_REFERENCE, report.assessments.single { it.target == "jenkins" }.supportClass)
        assertEquals(AdapterSupportClass.NATIVE_LEAF_ONLY, report.assessments.single { it.target == "github-actions" }.supportClass)
        assertEquals(AdapterSupportClass.NATIVE_LEAF_ONLY, report.assessments.single { it.target == "tekton" }.supportClass)
        assertEquals(AdapterSupportClass.PROFILE_ONLY, report.assessments.single { it.target == "argo-workflows" }.supportClass)
        assertEquals(AdapterSupportClass.PROFILE_ONLY, report.assessments.single { it.target == "azure-devops" }.supportClass)
    }

    @Test
    fun missingTargetRecordFailsClosed() {
        val report = authority.evaluate(document.copy(records = document.records.filterNot { it.target == "azure-devops" }))

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any {
            it.code == "ADAPTER_PORTFOLIO_TARGET_MISSING" && it.target == "azure-devops"
        })
    }

    @Test
    fun providerBackedTargetCannotBeRelabeledProfileOnly() {
        val records = document.records.map { record ->
            if (record.target == "github-actions") record.copy(supportClass = AdapterSupportClass.PROFILE_ONLY) else record
        }
        val report = authority.evaluate(document.copy(records = records))

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any {
            it.code == "ADAPTER_PORTFOLIO_PROFILE_ONLY_CLAIM_INVALID" && it.target == "github-actions"
        })
    }

    @Test
    fun executableClaimRequiresCommittedEndToEndEvidence() {
        val records = document.records.map { record ->
            if (record.target == "github-actions") {
                record.copy(supportClass = AdapterSupportClass.EXECUTABLE_REFERENCE)
            } else {
                record
            }
        }
        val report = authority.evaluate(document.copy(records = records))

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any {
            it.code == "ADAPTER_PORTFOLIO_EXECUTABLE_CLAIM_UNSUPPORTED" && it.target == "github-actions"
        })
    }

    @Test
    fun unknownPortfolioFieldsAreRejectedByTheSharedYamlBoundary() {
        val root = Files.createTempDirectory("flow-adapter-portfolio-invalid").toFile()
        try {
            val file = File(root, AdapterPortfolioLoader.PATH)
            file.parentFile.mkdirs()
            file.writeText(
                """
                kind: FlowAdapterPortfolio
                version: "1.0"
                invented: true
                records: []
                """.trimIndent()
            )

            assertFailsWith<IllegalArgumentException> { AdapterPortfolioLoader.load(root) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun adapterCertificationRunsAfterFrozenCoreClosure() {
        val summary = ConformanceRunner().run()
        val names = summary.checks.map { it.name }
        val closureIndex = names.indexOf(SemanticClosureAuthority.CHECK_ID)
        val adapterIndex = names.indexOf(AdapterConformanceRunner.INVENTORY_CHECK)
        val coreInventory = ConformanceSuiteInventory.load()

        assertTrue(closureIndex >= 0, names.joinToString())
        assertTrue(adapterIndex > closureIndex, names.joinToString())
        assertFalse(AdapterConformanceRunner.INVENTORY_CHECK in coreInventory.preClosureChecks)
        assertFalse(AdapterPortfolioConformanceChecks.PORTFOLIO_CHECK in coreInventory.preClosureChecks)
        assertTrue(summary.checks.drop(adapterIndex).all { it.passed }, summary.checks.filterNot { it.passed }.joinToString())
    }
}
