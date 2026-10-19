import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.cli.Json
import org.flowlang.generators.manifest.*
import org.flowlang.adapters.maturity.AdapterTargetMaturityEvidenceLoader
import org.flowlang.adapters.maturity.AdapterTargetMaturityPublisher
import org.flowlang.adapters.maturity.TargetMaturityStage
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.SupportLevel
import org.flowlang.conformance.ArchitectureRecoveryConformanceRunner
import org.flowlang.conformance.AdapterTargetMaturityConformanceChecks
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
    fun currentDistributionPublishesReferenceMaturityWithoutRuntimeCertification() {
        val report = publisher.analyze()

        assertEquals(
            "PASS",
            report.status,
            report.findings.joinToString { "${it.code}:${it.target}:${it.scopeId}:${it.message}" }
        )
        val byTarget = report.assessments.associateBy { it.target }
        assertEquals("2.0", report.reportVersion)
        assertEquals("REPOSITORY_REFERENCE_INVENTORY", report.evidenceBasis)
        assertFalse(report.runtimeCertificationAssessed)
        assertFalse(report.executionAuthorized)
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
            assertFalse(TargetMaturityStage.REFERENCE_EXECUTABLE in assessment.targetWideStages)
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
            assertEquals(TargetMaturityStage.REFERENCE_EXECUTABLE, scope.highestStage)
            assertTrue(scope.limitations.isNotEmpty())
        }
    }

    @Test
    fun missingBehaviorReferencesInvalidateExecutableReferenceMaturity() {
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
        assertEquals(TargetMaturityStage.RENDERABLE, jenkins.highestStage)
        assertFalse(jenkins.valid)
        assertTrue(report.findings.any {
            it.code == "TARGET_MATURITY_BEHAVIOR_EVIDENCE_MISSING" && it.target == "jenkins"
        })
    }

    @Test
    fun existingUnrelatedTestFilesCannotManufactureRuntimeCertification() {
        val document = AdapterTargetMaturityEvidenceLoader.load(rootDir)
        val report = publisher.evaluate(document.copy(scopes = document.scopes.map {
            it.copy(behavioralEvidenceReferences = listOf("src/test/kotlin/TargetMaturityCompositionTests.kt"))
        }))
        // Existing paths are inventory evidence only. No native result was supplied or executed.
        assertEquals("PASS", report.status)
        assertTrue(report.assessments.flatMap { it.scopes }.all {
            it.highestStage == TargetMaturityStage.REFERENCE_EXECUTABLE
        })
        val json = Json.mapper.writeValueAsString(report)
        assertFalse(json.contains("BEHAVIORALLY_CERTIFIED"))
        assertFalse(report.runtimeCertificationAssessed)
        assertFalse(report.executionAuthorized)
    }

    @Test
    fun invalidReferenceScopesCannotRetainExecutableReferenceMaturity() {
        val document = AdapterTargetMaturityEvidenceLoader.load(rootDir)
        val original = document.scopes.single { it.target == "jenkins" }
        for (invalid in listOf(
            original.copy(sourceReference = "https://example.invalid/proof"),
            original.copy(scenarioId = "different-scenario"),
            original.copy(implementationEvidenceReferences = listOf("src/main/Missing.kt")),
            original.copy(limitations = emptyList())
        )) {
            val report = publisher.evaluate(document.copy(scopes = document.scopes.map {
                if (it.target == original.target) invalid else it
            }))
            val scope = report.assessments.single { it.target == original.target }.scopes.single()
            assertEquals("FAIL", report.status)
            assertFalse(scope.valid)
            assertEquals(TargetMaturityStage.RENDERABLE, scope.highestStage)
        }
    }

    @Test
    fun invalidDocumentHeadersInvalidateEveryReferenceScope() {
        val document = AdapterTargetMaturityEvidenceLoader.load(rootDir)
        for ((invalid, code) in listOf(
            document.copy(kind = "UnrecognizedEvidence") to "TARGET_MATURITY_KIND_INVALID",
            document.copy(version = "999.0") to "TARGET_MATURITY_VERSION_INVALID"
        )) {
            val report = publisher.evaluate(invalid)
            assertEquals("FAIL", report.status)
            assertTrue(report.findings.any { it.code == code })
            val scopes = report.assessments.flatMap { it.scopes }
            assertEquals(document.scopes.size, scopes.size)
            scopes.forEach {
                assertFalse(it.valid)
                assertEquals(TargetMaturityStage.RENDERABLE, it.highestStage)
            }
        }
    }

    @Test
    fun duplicateScopeIdentitiesCannotRetainExecutableReferenceMaturity() {
        val document = AdapterTargetMaturityEvidenceLoader.load(rootDir)
        val duplicate = document.scopes.single { it.target == "jenkins" }
        val report = publisher.evaluate(document.copy(scopes = document.scopes + duplicate))
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any {
            it.code == "TARGET_MATURITY_SCOPE_DUPLICATE" && it.target == duplicate.target && it.scopeId == duplicate.scopeId
        })
        val jenkins = report.assessments.single { it.target == duplicate.target }.scopes
        assertEquals(2, jenkins.size)
        jenkins.forEach {
            assertFalse(it.valid)
            assertEquals(TargetMaturityStage.RENDERABLE, it.highestStage)
        }
        val unrelated = report.assessments.single { it.target == "github-actions" }.scopes.single()
        assertTrue(unrelated.valid)
        assertEquals(TargetMaturityStage.REFERENCE_EXECUTABLE, unrelated.highestStage)
    }

    @Test
    fun structuralPublicationFollowsProviderScopeRatherThanTargetNames() {
        val registry = BuiltInTargetProjections.registry
        val original = registry.requireProvider("jenkins")
        val catalog = original.nativeProjectionCatalog
        val changed = TargetNativeProjectionCatalog.of(original.target, catalog.definitions,
            catalog.approvalDefinitions, catalog.structuralDefinitions.map {
                if (it.structure == TargetStructuralProjectionKind.PARALLEL)
                    it.copy(supportScope = TargetStructuralProjectionSupportScope.TARGET_WIDE) else it
            }.reversed())
        val generator = object : TargetManifestGenerator by original.generator {
            override val nativeProjectionCatalog = changed
        }
        val replacement = TargetProjectionProvider(generator, original.renderer)
        val composed = TargetProjectionRegistry.of(registry.targetIds.map {
            if (it == original.target) replacement else registry.requireProvider(it)
        })
        val baseline = publisher.analyze().assessments.single { it.target == "jenkins" }
        assertEquals(TargetStructuralProjectionSupportScope.PLAN_SCOPED,
            baseline.structuralProjections.single { it.structure == TargetStructuralProjectionKind.PARALLEL }.supportScope)
        val report = AdapterTargetMaturityPublisher(rootDir, targets, composed).analyze()
        val assessment = report.assessments.single { it.target == "jenkins" }
        assertEquals(changed.structuralDefinitions.sortedBy { it.structure.name }, assessment.structuralProjections)
        assertEquals("FAIL", report.status) // An unmatched target-wide claim still fails reconciliation.
        assertTrue(report.findings.any { it.code == "TARGET_MATURITY_PROVIDER_STRUCTURE_UNDECLARED" })
        assertEquals(TargetMaturityStage.RENDERABLE, assessment.highestTargetWideStage)
        val absent = AdapterTargetMaturityPublisher(rootDir, targets, TargetProjectionRegistry.empty()).analyze()
        assertEquals("FAIL", absent.status)
        assertTrue(absent.assessments.all { it.structuralProjections.isEmpty() })
        assertTrue(absent.assessments.flatMap { it.scopes }.none {
            it.highestStage == TargetMaturityStage.REFERENCE_EXECUTABLE
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
    fun planScopedLoopCannotCertifyTargetWideLoopSupport() {
        val mutated = targets + ("jenkins" to targets.getValue("jenkins").copy(dynamicLoops = SupportLevel.SUPPORTED))
        val report = AdapterTargetMaturityPublisher(rootDir, mutated, BuiltInTargetProjections.registry).analyze()
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "TARGET_MATURITY_SUPPORTED_STRUCTURE_UNOWNED" &&
            it.target == "jenkins" && it.message.contains("loop.dynamic") })
        val check = AdapterTargetMaturityConformanceChecks(rootDir, mutated, BuiltInTargetProjections.registry).checks()
            .single { it.name == AdapterTargetMaturityConformanceChecks.STRUCTURAL_EVIDENCE_CHECK }
        assertFalse(check.passed)
    }

    @Test
    fun planScopedRetryCannotCertifyTargetWideRetrySupport() {
        val mutated = targets + ("jenkins" to targets.getValue("jenkins").copy(retry = SupportLevel.SUPPORTED))
        val report = AdapterTargetMaturityPublisher(rootDir, mutated, BuiltInTargetProjections.registry).analyze()
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "TARGET_MATURITY_SUPPORTED_STRUCTURE_UNOWNED" &&
            it.target == "jenkins" && it.message.contains("retry.task") })
        val check = AdapterTargetMaturityConformanceChecks(rootDir, mutated, BuiltInTargetProjections.registry).checks()
            .single { it.name == AdapterTargetMaturityConformanceChecks.STRUCTURAL_EVIDENCE_CHECK }
        assertFalse(check.passed)
    }

    @Test
    fun targetWideStructuralEvidenceStillRequiresAnHonestRegistryDeclaration() {
        val mutated = targets + ("jenkins" to targets.getValue("jenkins").copy(conditions = SupportLevel.UNSUPPORTED))
        val report = AdapterTargetMaturityPublisher(rootDir, mutated, BuiltInTargetProjections.registry).analyze()
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "TARGET_MATURITY_PROVIDER_STRUCTURE_UNDECLARED" &&
            it.target == "jenkins" && it.message.contains("condition") })
        val check = AdapterTargetMaturityConformanceChecks(rootDir, mutated, BuiltInTargetProjections.registry).checks()
            .single { it.name == AdapterTargetMaturityConformanceChecks.STRUCTURAL_EVIDENCE_CHECK }
        assertFalse(check.passed)
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
