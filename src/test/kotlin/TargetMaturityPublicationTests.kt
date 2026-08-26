import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.maturity.AdapterTargetMaturityEvidenceLoader
import org.flowlang.adapters.maturity.AdapterTargetMaturityPublisher
import org.flowlang.adapters.maturity.TargetMaturityStage
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.SupportLevel
import org.flowlang.conformance.ArchitectureRecoveryConformanceRunner
import org.flowlang.targets.builtin.BuiltInTargetProjections

class TargetMaturityPublicationTests {
    private val rootDir = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets"))
    private val publisher = AdapterTargetMaturityPublisher(
        rootDir,
        targets,
        BuiltInTargetProjections.registry
    )

    @Test
    fun currentDistributionPublishesScopedCumulativeMaturityWithoutBroadExecutability() {
        val report = publisher.analyze()

        assertEquals(
            "PASS",
            report.status,
            report.findings.joinToString { "${it.code}:${it.target}:${it.scopeId}:${it.message}" }
        )
        val byTarget = report.assessments.associateBy { it.target }
        assertEquals(
            mapOf(
                "local" to TargetMaturityStage.ANALYZABLE,
                "jenkins" to TargetMaturityStage.RENDERABLE,
                "github-actions" to TargetMaturityStage.RENDERABLE,
                "tekton" to TargetMaturityStage.RENDERABLE,
                "argo-workflows" to TargetMaturityStage.ANALYZABLE,
                "azure-devops" to TargetMaturityStage.ANALYZABLE
            ),
            byTarget.mapValues { it.value.highestTargetWideStage }
        )
        byTarget.values.forEach { assessment ->
            assertFalse(TargetMaturityStage.EXECUTABLE in assessment.targetWideStages)
            assertFalse(TargetMaturityStage.BEHAVIORALLY_CERTIFIED in assessment.targetWideStages)
            assertEquals(
                TargetMaturityStage.entries.take(assessment.targetWideStages.size),
                assessment.targetWideStages
            )
        }

        val scoped = report.assessments.flatMap { it.scopes }
        assertEquals(
            setOf("jenkins::checkout-build-image", "github-actions::checkout-build-image"),
            scoped.map { "${it.target}::${it.scopeId}" }.toSet()
        )
        scoped.forEach { scope ->
            assertTrue(scope.valid)
            assertEquals(TargetMaturityStage.entries.toList(), scope.stages)
            assertEquals(TargetMaturityStage.BEHAVIORALLY_CERTIFIED, scope.highestStage)
            assertTrue(scope.limitations.isNotEmpty())
        }
    }

    @Test
    fun missingBehaviorEvidenceCannotRemainBehaviorallyCertified() {
        val document = AdapterTargetMaturityEvidenceLoader.load(rootDir)
        val mutated = document.copy(
            scopes = document.scopes.map { scope ->
                if (scope.target == "jenkins") {
                    scope.copy(
                        behavioralEvidenceReferences =
                            listOf("src/test/kotlin/DeliberatelyMissingMaturityEvidence.kt")
                    )
                } else {
                    scope
                }
            }
        )

        val report = publisher.evaluate(mutated)
        val jenkins = report.assessments.single { it.target == "jenkins" }.scopes.single()

        assertEquals("FAIL", report.status)
        assertEquals(TargetMaturityStage.EXECUTABLE, jenkins.highestStage)
        assertFalse(jenkins.valid)
        assertTrue(report.findings.any {
            it.code == "TARGET_MATURITY_BEHAVIOR_EVIDENCE_MISSING" && it.target == "jenkins"
        })
    }

    @Test
    fun profileOnlyTargetCannotBorrowAnExecutableScenario() {
        val document = AdapterTargetMaturityEvidenceLoader.load(rootDir)
        val source = document.scopes.single { it.target == "jenkins" }
        val forged = source.copy(
            target = "argo-workflows",
            scopeId = "forged-checkout-build-image",
            sourceReference =
                "${AdapterPortfolioLoader.PATH}#records.argo-workflows"
        )

        val report = publisher.evaluate(document.copy(scopes = document.scopes + forged))
        val assessment = report.assessments
            .single { it.target == "argo-workflows" }
            .scopes
            .single()

        assertEquals("FAIL", report.status)
        assertEquals(TargetMaturityStage.ANALYZABLE, assessment.highestStage)
        assertFalse(assessment.valid)
        assertTrue(report.findings.any {
            it.code == "TARGET_MATURITY_SCOPE_PROVIDER_MISSING" &&
                it.target == "argo-workflows"
        })
    }

    @Test
    fun supportedStructuralClaimRequiresProviderOwnedDefinition() {
        val document = AdapterTargetMaturityEvidenceLoader.load(rootDir)
        val mutatedTargets = targets + (
            "github-actions" to targets.getValue("github-actions").copy(
                conditions = SupportLevel.SUPPORTED
            )
        )
        val report = AdapterTargetMaturityPublisher(
            rootDir,
            mutatedTargets,
            BuiltInTargetProjections.registry
        ).evaluate(document)

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any {
            it.code == "TARGET_MATURITY_SUPPORTED_STRUCTURE_UNOWNED" &&
                it.target == "github-actions"
        })
    }

    @Test
    fun maturityEvidenceLoaderRejectsUnknownFields() {
        val root = Files.createTempDirectory("flow-target-maturity-invalid").toFile()
        try {
            val file = File(root, AdapterTargetMaturityEvidenceLoader.PATH)
            file.parentFile.mkdirs()
            file.writeText(
                """
                kind: FlowAdapterTargetMaturityEvidence
                version: "1.0"
                invented: true
                scopes: []
                """.trimIndent()
            )

            assertFailsWith<IllegalArgumentException> {
                AdapterTargetMaturityEvidenceLoader.load(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun architectureRecoveryConformanceInventoryAndChecksPassTogether() {
        val checks = ArchitectureRecoveryConformanceRunner(
            rootDir,
            targets,
            BuiltInTargetProjections.registry
        ).checks()

        assertTrue(checks.isNotEmpty())
        assertTrue(
            checks.all { it.passed },
            checks.filterNot { it.passed }.joinToString { "${it.name}:${it.message}" }
        )
    }
}
