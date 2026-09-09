import org.flowlang.distribution.reference.ReferenceAdapterEvidence
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.continuity.AdapterContinuityClaimStatus
import org.flowlang.adapters.continuity.AdapterContinuityEvidenceIntegrityAuthority
import org.flowlang.adapters.continuity.AdapterContinuityEvidenceLoader
import org.flowlang.adapters.continuity.AdapterContinuityFamily
import org.flowlang.adapters.continuity.AdapterContinuitySemanticContract
import org.flowlang.adapters.continuity.AdapterContinuitySemanticPartition
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterContinuityEvidenceIntegrityTests {
    private val root = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
    private val authority = ReferenceAdapterEvidence.continuityIntegrity(
        rootDir = root,
        targets = targets,
        projections = BuiltInTargetProjections.registry
    )

    @Test
    fun currentContinuityEvidencePassesIntegrityAnalysis() {
        val report = authority.analyze()

        assertEquals("PASS", report.status, report.findings.joinToString { it.code + ":" + it.target + ":" + it.family })
        assertEquals(targets.size, report.targetCount)
        assertEquals(targets.size * AdapterContinuityFamily.entries.size, report.claimCount)
    }

    @Test
    fun duplicateFamilyFailsClosed() {
        val document = AdapterContinuityEvidenceLoader.load(root)
        val jenkins = document.targets.single { it.target == "jenkins" }
        val duplicate = document.copy(
            targets = document.targets.map { record ->
                if (record.target == "jenkins") record.copy(claims = record.claims + jenkins.claims.first()) else record
            }
        )

        val report = authority.analyze(duplicate)

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "ADAPTER_CONTINUITY_FAMILY_DUPLICATE" && it.target == "jenkins" })
    }

    @Test
    fun supportedClaimRequiresIndependentBehaviorEvidence() {
        val document = AdapterContinuityEvidenceLoader.load(root)
        val malformed = document.copy(
            targets = document.targets.map { record ->
                if (record.target != "jenkins") record else record.copy(
                    claims = record.claims.map { claim ->
                        if (claim.family != AdapterContinuityFamily.ARTIFACT) claim else claim.copy(
                            evidenceReferences = claim.evidenceReferences.filterNot { it.startsWith("src/test/") }
                        )
                    }
                )
            }
        )

        val report = authority.analyze(malformed)

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any {
            it.code == "ADAPTER_CONTINUITY_BEHAVIOR_EVIDENCE_MISSING" &&
                it.target == "jenkins" && it.family == "ARTIFACT"
        })
    }

    @Test
    fun profileOnlyTargetCannotBePromotedByAuthoredEvidence() {
        val document = AdapterContinuityEvidenceLoader.load(root)
        val malformed = document.copy(
            targets = document.targets.map { record ->
                if (record.target != "argo-workflows") record else record.copy(
                    claims = record.claims.map { claim ->
                        if (claim.family != AdapterContinuityFamily.DATA) claim else claim.copy(
                            status = AdapterContinuityClaimStatus.SUPPORTED,
                            semantics = AdapterContinuitySemanticPartition(
                                supported = setOf(AdapterContinuitySemanticContract.DATA_VALUE),
                                unsupported = emptyMap(),
                                unknown = emptyMap()
                            )
                        )
                    }
                )
            }
        )

        val report = authority.analyze(malformed)

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any {
            it.code == "ADAPTER_CONTINUITY_PROFILE_ONLY_PROMOTED" && it.target == "argo-workflows"
        })
        assertTrue(report.findings.any {
            it.code == "ADAPTER_CONTINUITY_PROVIDER_MISSING" && it.target == "argo-workflows"
        })
    }

    @Test
    fun semanticPartitionMustExactlyMatchItsClosedFamilyContract() {
        val document = AdapterContinuityEvidenceLoader.load(root)
        val malformed = document.copy(
            targets = document.targets.map { record ->
                if (record.target != "jenkins") record else record.copy(
                    claims = record.claims.map { claim ->
                        if (claim.family != AdapterContinuityFamily.DATA) claim else claim.copy(
                            semantics = claim.semantics.copy(
                                unsupported = claim.semantics.unsupported + ("data.folklore" to "Invented semantic")
                            )
                        )
                    }
                )
            }
        )

        val report = authority.analyze(malformed)

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any {
            it.code == "ADAPTER_CONTINUITY_SEMANTIC_UNKNOWN" && it.target == "jenkins" && it.family == "DATA"
        })
    }
}
