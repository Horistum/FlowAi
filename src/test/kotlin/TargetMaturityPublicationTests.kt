import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.portfolio.AdapterPortfolioAuthority
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.TargetMaturityStage
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections

class TargetMaturityPublicationTests {
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
    private val authority = AdapterPortfolioAuthority(
        rootDir = File("."),
        targets = targets,
        projections = BuiltInTargetProjections.registry
    )
    private val document = AdapterPortfolioLoader.load()

    @Test
    fun maturityStagesAreCumulativeAndBoundedByEvidence() {
        val report = authority.evaluate(document)

        assertEquals("PASS", report.status, report.findings.joinToString { "${it.code}:${it.target}:${it.message}" })
        val byTarget = report.assessments.associateBy { it.target }
        assertEquals(
            TargetMaturityStage.entries,
            byTarget.getValue("jenkins").maturityStages
        )
        assertEquals("checkout-build-image", byTarget.getValue("jenkins").certificationScope)

        listOf("github-actions", "tekton").forEach { target ->
            assertEquals(
                listOf(
                    TargetMaturityStage.DECLARED,
                    TargetMaturityStage.ANALYZABLE,
                    TargetMaturityStage.RENDERABLE
                ),
                byTarget.getValue(target).maturityStages
            )
        }
        listOf("local", "argo-workflows", "azure-devops").forEach { target ->
            assertEquals(
                listOf(TargetMaturityStage.DECLARED, TargetMaturityStage.ANALYZABLE),
                byTarget.getValue(target).maturityStages
            )
        }
        report.assessments.forEach { assessment ->
            assertEquals(
                TargetMaturityStage.entries.take(assessment.maturityStages.size),
                assessment.maturityStages,
                "Target '${assessment.target}' publishes a non-cumulative maturity sequence."
            )
        }
    }

    @Test
    fun executableReferenceWithoutBehavioralEvidenceIsNotCertified() {
        val mutated = document.copy(records = document.records.map { record ->
            if (record.target == "jenkins") record.copy(behavioralEvidence = emptyList()) else record
        })

        val report = authority.evaluate(mutated)
        val jenkins = report.assessments.single { it.target == "jenkins" }

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any {
            it.code == "ADAPTER_PORTFOLIO_EXECUTABLE_CLAIM_UNSUPPORTED" && it.target == "jenkins"
        })
        assertEquals(
            listOf(
                TargetMaturityStage.DECLARED,
                TargetMaturityStage.ANALYZABLE,
                TargetMaturityStage.RENDERABLE,
                TargetMaturityStage.EXECUTABLE
            ),
            jenkins.maturityStages
        )
    }

    @Test
    fun supportedStructuralClaimWithoutProviderEvidenceFailsPortfolioValidation() {
        val mutatedTargets = targets.toMutableMap()
        val github = mutatedTargets.getValue("github-actions")
        mutatedTargets["github-actions"] = github.copy(
            conditions = org.flowlang.capabilities.SupportLevel.SUPPORTED,
            features = github.features + ("conditions.inline" to org.flowlang.capabilities.SupportLevel.SUPPORTED)
        )
        val mutatedAuthority = AdapterPortfolioAuthority(
            rootDir = File("."),
            targets = mutatedTargets,
            projections = BuiltInTargetProjections.registry
        )

        val report = mutatedAuthority.evaluate(document)

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any {
            it.code == "ADAPTER_PORTFOLIO_STRUCTURAL_SUPPORT_UNPROVEN" &&
                it.target == "github-actions" &&
                it.message.contains("condition.evaluate")
        })
    }
}
