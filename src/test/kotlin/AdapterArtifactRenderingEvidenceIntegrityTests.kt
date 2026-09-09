import org.flowlang.distribution.reference.ReferenceAdapterEvidence
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.rendering.AdapterArtifactFormat
import org.flowlang.adapters.rendering.AdapterArtifactRenderingClaimStatus
import org.flowlang.adapters.rendering.AdapterArtifactRenderingEvidenceIntegrityAuthority
import org.flowlang.adapters.rendering.AdapterArtifactRenderingEvidenceLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterArtifactRenderingEvidenceIntegrityTests {
    private val root = File(".")
    private val authority = ReferenceAdapterEvidence.renderingIntegrity(
        rootDir = root,
        projections = BuiltInTargetProjections.registry
    )

    @Test
    fun repositoryRenderingEvidenceIsCompleteAndValid() {
        val report = authority.analyze()

        assertEquals("PASS", report.status, report.findings.joinToString { it.code + ":" + it.message })
        assertEquals(6, report.targetCount)
    }

    @Test
    fun reviewArtifactCannotReuseExecutableTargetFileName() {
        val document = AdapterArtifactRenderingEvidenceLoader.load(root)
        val jenkins = document.targets.single { it.target == "jenkins" }
        val mutated = document.copy(
            targets = document.targets.map { record ->
                if (record.target == "jenkins") {
                    record.copy(review = requireNotNull(record.executable))
                } else {
                    record
                }
            }
        )

        val report = authority.analyze(mutated)

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "ADAPTER_RENDERING_REVIEW_IMPERSONATES_EXECUTABLE" })
        assertEquals("Jenkinsfile", jenkins.executable?.fileName)
    }

    @Test
    fun supportedRendererRequiresIndependentBehaviorEvidence() {
        val document = AdapterArtifactRenderingEvidenceLoader.load(root)
        val mutated = document.copy(
            targets = document.targets.map { record ->
                if (record.target == "jenkins") {
                    record.copy(evidenceReferences = record.evidenceReferences.filter { it.startsWith("src/main/") })
                } else {
                    record
                }
            }
        )

        val report = authority.analyze(mutated)

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "ADAPTER_RENDERING_BEHAVIOR_EVIDENCE_MISSING" })
    }

    @Test
    fun semanticReferenceCannotBePromotedToExecutableRendering() {
        val document = AdapterArtifactRenderingEvidenceLoader.load(root)
        val mutated = document.copy(
            targets = document.targets.map { record ->
                if (record.target == "local") {
                    record.copy(
                        status = AdapterArtifactRenderingClaimStatus.SUPPORTED,
                        executable = AdapterArtifactFormat("local.flow", "application/yaml")
                    )
                } else {
                    record
                }
            }
        )

        val report = authority.analyze(mutated)

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "ADAPTER_RENDERING_PROFILE_ONLY_PROMOTED" })
        assertTrue(report.findings.any { it.code == "ADAPTER_RENDERING_SUPPORTED_WITHOUT_PROVIDER" })
    }
}
