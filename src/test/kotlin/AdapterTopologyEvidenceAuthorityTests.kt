import org.flowlang.distribution.reference.ReferenceAdapterEvidence
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.topology.AdapterTopologyClaimContract
import org.flowlang.adapters.topology.AdapterTopologyClaimStatus
import org.flowlang.adapters.topology.AdapterTopologyEvidenceAuthority
import org.flowlang.adapters.topology.AdapterTopologyEvidenceLoader
import org.flowlang.adapters.topology.AdapterTopologyProfileFactory
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologySupportStatus

class AdapterTopologyEvidenceAuthorityTests {
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
    private val document = AdapterTopologyEvidenceLoader.load()
    private val authority = ReferenceAdapterEvidence.topology(
        File("."),
        targets,
        BuiltInTargetProjections.registry,
        AdapterPortfolioLoader.load()
    )

    @Test
    fun builtInTopologyEvidenceIsCompleteAndHonest() {
        val report = authority.evaluate(document)

        assertEquals("PASS", report.status, report.findings.joinToString { "${it.code}:${it.target}:${it.claim}:${it.message}" })
        assertEquals(targets.keys.sorted(), report.assessments.map { it.target })
        report.assessments.forEach { assessment ->
            assertEquals(
                AdapterTopologyClaimContract.requiredClaimKeys,
                assessment.claims.map { it.key }.toSet(),
                assessment.target
            )
        }
    }

    @Test
    fun profileOnlyAdaptersAreDeliberatelyUnknown() {
        listOf("argo-workflows", "azure-devops").forEach { target ->
            val claims = document.records.single { it.target == target }.claims
            val profile = requireNotNull(targets.getValue(target).topologyProfile)
            assertTrue(claims.all { it.status == AdapterTopologyClaimStatus.UNKNOWN }, target)
            assertTrue(
                profile.declarations.all { it.status == ExecutionTopologySupportStatus.UNKNOWN },
                target
            )
        }
    }

    @Test
    fun runtimeProfilesComeFromAdapterEvidenceNotInlineRegistryClaims() {
        val jenkins = requireNotNull(targets.getValue("jenkins").topologyProfile)
        val argo = requireNotNull(targets.getValue("argo-workflows").topologyProfile)

        assertEquals(
            ExecutionTopologySupportStatus.UNKNOWN,
            jenkins.declarations.single { it.kind == ExecutionTopologyKind.ATTEMPT_ISOLATION }.status
        )
        assertEquals(
            ExecutionTopologySupportStatus.UNKNOWN,
            argo.declarations.single { it.kind == ExecutionTopologyKind.WORKFLOW_SCOPE }.status
        )
        targets.forEach { (target, capability) ->
            requireNotNull(capability.topologyProfile).declarations.forEach { declaration ->
                assertEquals(
                    AdapterTopologyClaimContract.registryEvidenceReference(target, declaration.kind.registryKey),
                    declaration.evidenceReference
                )
                assertTrue(declaration.detail.isNullOrBlank())
            }
        }
    }

    @Test
    fun executableJenkinsRequirementsRemainSupported() {
        val claims = document.records.single { it.target == "jenkins" }.claims.associateBy { it.key }
        setOf("workflowScope", "workflowLifetime", "ephemeralWorkspace", "workspacePropagation").forEach { key ->
            assertEquals(AdapterTopologyClaimStatus.SUPPORTED, claims.getValue(key).status, key)
        }
    }

    @Test
    fun missingClaimFailsClosed() {
        val records = document.records.map { record ->
            if (record.target == "github-actions") {
                record.copy(claims = record.claims.filterNot { it.key == "workspacePropagation" })
            } else {
                record
            }
        }
        val report = authority.evaluate(document.copy(records = records))

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any {
            it.code == "ADAPTER_TOPOLOGY_CLAIM_MISSING" &&
                it.target == "github-actions" &&
                it.claim == "workspacePropagation"
        })
    }

    @Test
    fun profileOnlyTargetCannotBePromotedByAuthoredStatus() {
        val records = document.records.map { record ->
            if (record.target == "argo-workflows") {
                record.copy(claims = record.claims.map { claim ->
                    if (claim.key == "workflowScope") claim.copy(status = AdapterTopologyClaimStatus.SUPPORTED) else claim
                })
            } else {
                record
            }
        }
        val report = authority.evaluate(document.copy(records = records))

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any {
            it.code == "ADAPTER_TOPOLOGY_PROFILE_ONLY_PROMOTED" &&
                it.target == "argo-workflows" &&
                it.claim == "workflowScope"
        })
    }

    @Test
    fun selfReferentialEvidenceFailsClosed() {
        val records = document.records.map { record ->
            if (record.target == "tekton") {
                record.copy(claims = record.claims.map { claim ->
                    if (claim.key == "workflowScope") {
                        claim.copy(evidenceReferences = listOf("targets/builtin-targets.yaml#targets.tekton.topology"))
                    } else {
                        claim
                    }
                })
            } else {
                record
            }
        }
        val report = authority.evaluate(document.copy(records = records))

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any {
            it.code == "ADAPTER_TOPOLOGY_EVIDENCE_SELF_REFERENTIAL" &&
                it.target == "tekton" &&
                it.claim == "workflowScope"
        })
    }

    @Test
    fun profileFactoryRejectsIncompleteRecords() {
        val incomplete = document.records.single { it.target == "jenkins" }.copy(
            claims = document.records.single { it.target == "jenkins" }.claims
                .filterNot { it.key == "failurePropagation" }
        )

        assertFailsWith<IllegalArgumentException> { AdapterTopologyProfileFactory.profile(incomplete) }
    }

    @Test
    fun loaderRejectsUnknownClaimKeys() {
        val root = Files.createTempDirectory("flow-adapter-topology-unknown").toFile()
        try {
            val file = File(root, AdapterTopologyEvidenceLoader.PATH)
            file.parentFile.mkdirs()
            file.writeText(
                """
                kind: FlowAdapterTopologyEvidence
                version: "1.0"
                records:
                  - target: fake
                    claims:
                      inventedTopology:
                        facet: scope
                        status: unknown
                        mechanism: "Invented"
                        evidenceReferences: ["docs/CORE_ADAPTER_SPLIT.md"]
                        limitations: ["Invented"]
                """.trimIndent()
            )

            assertFailsWith<IllegalArgumentException> { AdapterTopologyEvidenceLoader.load(root) }
        } finally {
            root.deleteRecursively()
        }
    }
}
