import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.conformance.AbstractTopologyMatrixAuthority
import org.flowlang.conformance.AbstractTopologyMatrixFixture
import org.flowlang.conformance.AbstractTopologyMatrixLoader
import org.flowlang.conformance.AbstractTopologyMatrixPlanFactory
import org.flowlang.modules.ModuleRegistry
import org.flowlang.topology.ExecutionTopologyDecisionStatus
import org.flowlang.topology.ExecutionTopologyDimension
import org.flowlang.topology.ExecutionTopologyEvidenceStatus
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologyMatchingAuthority
import org.flowlang.topology.ExecutionTopologyProfile
import org.flowlang.topology.ExecutionTopologySupportDeclaration
import org.flowlang.topology.ExecutionTopologySupportStatus

class AbstractTopologyMatrixAuthorityTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }

    @Test
    fun repositoryMatrixPassesEveryIndependentEvidenceCategory() {
        val report = AbstractTopologyMatrixAuthority(File("."), modules, targets).analyze()

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertTrue(report.schemaErrors.isEmpty())
        assertTrue(report.coverageErrors.isEmpty())
        assertTrue(report.polarityErrors.isEmpty())
        assertTrue(report.independenceErrors.isEmpty())
        assertTrue(report.concreteReferenceErrors.isEmpty())
        assertTrue(report.boundaryErrors.isEmpty())
    }

    @Test
    fun matrixCoversEveryClosedTopologyKindAndDimension() {
        val document = AbstractTopologyMatrixLoader.load()
        val kinds = document.cases.flatMap { it.requiredKinds }.toSet()

        assertEquals(ExecutionTopologyKind.entries.toSet(), kinds)
        assertEquals(ExecutionTopologyDimension.entries.toSet(), kinds.map { it.dimension }.toSet())
    }

    @Test
    fun workspaceRequirementChangesPolarityThroughProductionMatchingAuthority() {
        val plan = AbstractTopologyMatrixPlanFactory.plan(AbstractTopologyMatrixFixture.WORKSPACE_CONTINUITY)
        val profile = ExecutionTopologyProfile(
            target = "workspace-negative",
            declarations = ExecutionTopologyKind.entries.map { kind ->
                ExecutionTopologySupportDeclaration(
                    kind = kind,
                    status = if (kind == ExecutionTopologyKind.WORKSPACE_PROPAGATION) {
                        ExecutionTopologySupportStatus.UNSUPPORTED
                    } else {
                        ExecutionTopologySupportStatus.SUPPORTED
                    },
                    evidenceReference = "test:${kind.registryKey}"
                )
            }
        )

        val assessment = ExecutionTopologyMatchingAuthority.assess(plan.topologyRequirements, profile)

        assertEquals(ExecutionTopologyDecisionStatus.BLOCKED, assessment.decision.status)
        assertTrue(assessment.evidence.any {
            it.kind == ExecutionTopologyKind.WORKSPACE_PROPAGATION &&
                it.status == ExecutionTopologyEvidenceStatus.UNSATISFIED
        })
    }

    @Test
    fun unknownMatrixFieldFailsClosedAtTheSharedYamlBoundary() {
        val root = Files.createTempDirectory("flow-c0-2-matrix").toFile()
        try {
            val file = File(root, AbstractTopologyMatrixLoader.PATH)
            file.parentFile.mkdirs()
            file.writeText(
                """
                version: "1.0"
                mutations: []
                cases: []
                concreteReferences: []
                inventedSupport: true
                """.trimIndent()
            )

            val failure = assertFailsWith<IllegalArgumentException> {
                AbstractTopologyMatrixLoader.load(root)
            }
            assertTrue(failure.message.orEmpty().contains("unknown fields"))
        } finally {
            root.deleteRecursively()
        }
    }
}
