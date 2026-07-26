import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.flowlang.artifacts.ArtifactEvidenceAnalyzer
import org.flowlang.artifacts.FlowArtifactBundleAnalyzer
import org.flowlang.artifacts.FlowArtifactEntry
import org.flowlang.artifacts.FlowArtifactRole
import org.flowlang.artifacts.StandardContractIndexAnalyzer
import org.flowlang.artifacts.StandardSurface
import org.flowlang.artifacts.StandardSurfaceStatusAuthority
import org.flowlang.targets.TargetRegistryYamlLoader
import java.io.File

class PublicArtifactEvidenceIntegrityTests {
    @Test
    fun knownArtifactProducersAndVersionsComeFromExactContracts() {
        val bundle = referenceBundle()
        val evidence = ArtifactEvidenceAnalyzer().analyze(bundle)
        val index = StandardContractIndexAnalyzer().analyze(bundle)

        assertTrue(evidence.missingEvidence.isEmpty(), evidence.missingEvidence.joinToString())
        assertEquals(
            "flow.standard.compliance",
            evidence.evidence.single { it.artifact == "standard-compliance-report.json" }.producer
        )
        assertEquals(
            "0.3.19",
            index.contracts.single { it.artifact == "standard-compliance-report.json" }.introducedIn
        )
        assertTrue(evidence.evidence.none { it.producer == "flow-public-pipeline" })
    }

    @Test
    fun danglingDerivedFromBlocksEvidenceCompleteness() {
        val bundle = referenceBundle()
        val broken = bundle.copy(
            artifacts = bundle.artifacts.map { artifact ->
                if (artifact.name == "standard-compliance-report.json") {
                    artifact.copy(derivedFrom = listOf("missing-artifact.json"))
                } else {
                    artifact
                }
            }
        )

        val evidence = ArtifactEvidenceAnalyzer().analyze(broken)

        assertTrue(evidence.missingEvidence.contains("standard-compliance-report.json"))
    }

    @Test
    fun unknownPublicArtifactHasNoGenericProducerOrFictionalVersion() {
        val bundle = referenceBundle()
        val unknown = bundle.copy(
            artifacts = bundle.artifacts + FlowArtifactEntry(
                name = "invented-public-report.json",
                role = FlowArtifactRole.REPORT,
                schema = "schemas/invented-public-report.schema.json",
                required = true,
                derived = true,
                pipelineIndex = bundle.artifacts.size + 1,
                derivedFrom = listOf("normalized-intent.json")
            ),
            requiredArtifacts = bundle.requiredArtifacts + "invented-public-report.json",
            pipeline = bundle.pipeline + "invented-public-report.json"
        )

        assertFails { ArtifactEvidenceAnalyzer().analyze(unknown) }
        assertFails { StandardContractIndexAnalyzer().analyze(unknown) }
    }

    @Test
    fun publicStatusesHaveNegativeCounterparts() {
        val surface = StandardSurface.publicSurface()
        val policy = StandardSurface.compatibilityMigrationPolicy()
        val corpus = StandardSurface.referenceIntentCorpus()
        val export = StandardSurface.standardExportBundle()
        val levels = StandardSurface.conformanceLevels()
        val manifest = StandardSurface.standardExportManifest()

        assertEquals("PASS", surface.status)
        assertEquals("FAIL", StandardSurfaceStatusAuthority.publicSurface(emptyList(), surface.requiredChangeGates))
        assertEquals(
            "FAIL",
            StandardSurfaceStatusAuthority.compatibilityMigrationPolicy(
                policy.compatibilityRules.map { rule ->
                    if (rule.category == "breaking") rule.copy(allowedInMinor = true) else rule
                },
                policy.deprecationWindowMinorReleases,
                policy.migrationArtifacts,
                policy.breakingChangeGate
            )
        )
        assertEquals(
            "FAIL",
            StandardSurfaceStatusAuthority.referenceIntentCorpus(
                corpus.scenarios,
                emptyList(),
                corpus.negativeScenarioIds
            )
        )
        assertEquals(
            "FAIL",
            StandardSurfaceStatusAuthority.standardExportBundle(
                export.requiredDirectories,
                emptyList(),
                export.requiredArtifacts,
                export.packageName
            )
        )
        assertEquals(
            "FAIL",
            StandardSurfaceStatusAuthority.conformanceLevels(
                "missing-level",
                levels.levels,
                levels.levelOrder
            )
        )
        assertEquals(
            "FAIL",
            StandardSurfaceStatusAuthority.standardExportManifest(
                manifest.candidate,
                manifest.requiredDocuments,
                manifest.requiredJsonArtifacts,
                manifest.requiredSchemas,
                manifest.requiredDirectories,
                emptyList(),
                manifest.evidenceArtifacts,
                manifest.selfVerificationCommands,
                manifest.verificationInputs
            )
        )
    }

    @Test
    fun approvalSemanticsFollowProviderOwnedCatalogs() {
        val matrix = StandardSurface.targetSemanticsMatrix()
        val approvals = matrix.entries.single { it.feature == "approvals" }
        val strict = matrix.entries.single { it.feature == "strict-manual-approval" }

        assertEquals("native", approvals.semanticsByTarget.getValue("jenkins"))
        assertEquals("adapter-required-review-only", approvals.semanticsByTarget.getValue("github-actions"))
        assertEquals("adapter-required-review-only", approvals.semanticsByTarget.getValue("tekton"))
        assertEquals(approvals.semanticsByTarget, strict.semanticsByTarget)
        assertEquals(
            TargetRegistryYamlLoader.loadDirectory(File("targets")).keys,
            matrix.targetIds.toSet()
        )
    }

    private fun referenceBundle() = FlowArtifactBundleAnalyzer().intentBundle(
        flowName = "artifact-integrity",
        target = "target-neutral",
        strict = false,
        hasManifest = false,
        renderedArtifact = null
    )
}
