import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.control.AdapterControlFamily
import org.flowlang.adapters.control.AdapterControlMaterializationAuthority
import org.flowlang.adapters.control.AdapterControlMaterializationDocument
import org.flowlang.adapters.control.AdapterControlMaterializationLoader
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterControlTypedEvidenceIntegrityTests {
    private val rootDir = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets"))
    private val authority = AdapterControlMaterializationAuthority(
        rootDir = rootDir,
        targets = targets,
        projections = BuiltInTargetProjections.registry
    )

    @Test
    fun typedClaimCannotOmitRepositoryEvidence() {
        val malformed = document().mapClaim("jenkins", AdapterControlFamily.APPROVAL) { claim ->
            claim.copy(evidenceReferences = emptyList())
        }

        val report = authority.analyze(malformed)
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "CONTROL_EVIDENCE_MISSING" })
        assertTrue(report.findings.any { it.code == "CONTROL_SUPPORTED_IMPLEMENTATION_EVIDENCE_MISSING" })
        assertTrue(report.findings.any { it.code == "CONTROL_SUPPORTED_BEHAVIOR_EVIDENCE_MISSING" })
    }

    @Test
    fun typedClaimCannotEscapeRepositoryRoot() {
        val malformed = document().mapClaim("jenkins", AdapterControlFamily.RETRY) { claim ->
            claim.copy(evidenceReferences = listOf("../../outside-repository.txt"))
        }

        val report = authority.analyze(malformed)
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "CONTROL_EVIDENCE_PATH_OUTSIDE_REPOSITORY" })
    }

    @Test
    fun typedClaimCannotUseDuplicateEvidenceReferences() {
        val malformed = document().mapClaim("jenkins", AdapterControlFamily.APPROVAL) { claim ->
            claim.copy(evidenceReferences = claim.evidenceReferences + claim.evidenceReferences.first())
        }

        val report = authority.analyze(malformed)
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "CONTROL_EVIDENCE_DUPLICATE" })
    }

    @Test
    fun typedEvidenceWithUnknownSupportedSemanticProducesReportInsteadOfThrowing() {
        val malformed = document().mapClaim("jenkins", AdapterControlFamily.APPROVAL) { claim ->
            claim.copy(
                semantics = claim.semantics.copy(
                    supported = claim.semantics.supported + "approval.future-provider-contract"
                )
            )
        }

        val report = authority.analyze(malformed)
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "CONTROL_SEMANTIC_PARTITION_MISMATCH" })
    }

    private fun document() = AdapterControlMaterializationLoader.load(rootDir)

    private fun AdapterControlMaterializationDocument.mapClaim(
        target: String,
        family: AdapterControlFamily,
        transform: (org.flowlang.adapters.control.AdapterControlClaim) -> org.flowlang.adapters.control.AdapterControlClaim
    ): AdapterControlMaterializationDocument = copy(
        targets = targets.map { record ->
            if (record.target != target) record else record.copy(
                claims = record.claims.map { claim ->
                    if (claim.family != family) claim else transform(claim)
                }
            )
        }
    )
}
