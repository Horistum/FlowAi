import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.control.AdapterControlFamily
import org.flowlang.adapters.control.AdapterControlMaterializationAuthority
import org.flowlang.adapters.control.AdapterControlMaterializationLoader
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterControlEvidenceAnchorTests {
    private val rootDir = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets"))

    @Test
    fun typedEvidenceWithMissingSourceAnchorFailsIntegrity() {
        val document = AdapterControlMaterializationLoader.load(rootDir)
        val malformed = document.copy(
            targets = document.targets.map { record ->
                if (record.target != "jenkins") record else record.copy(
                    claims = record.claims.map { claim ->
                        if (claim.family != AdapterControlFamily.SCHEDULING) claim else claim.copy(
                            evidenceReferences = claim.evidenceReferences.map { reference ->
                                if (reference.startsWith("src/main/")) {
                                    reference.substringBefore('#') + "#missingAnchor"
                                } else {
                                    reference
                                }
                            }
                        )
                    }
                )
            }
        )
        val report = AdapterControlMaterializationAuthority(
            rootDir = rootDir,
            targets = targets,
            projections = BuiltInTargetProjections.registry,
            documentOverride = malformed
        ).analyze(malformed)

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { finding ->
            finding.code == "CONTROL_EVIDENCE_ANCHOR_UNRESOLVED" &&
                finding.target == "jenkins" &&
                finding.family == AdapterControlFamily.SCHEDULING.name
        })
    }
}
