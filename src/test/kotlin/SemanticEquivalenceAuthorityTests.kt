import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.conformance.SemanticEquivalenceAuthority
import org.flowlang.conformance.SemanticEquivalenceDecisionStatus
import org.flowlang.conformance.SemanticEquivalenceFixture
import org.flowlang.conformance.SemanticEquivalenceLoader
import org.flowlang.conformance.SemanticEquivalencePlanFactory
import org.flowlang.conformance.SemanticObservationAuthority
import org.flowlang.conformance.SemanticObservationEvidenceStatus
import org.flowlang.conformance.SemanticObservationKind
import org.flowlang.modules.ModuleRegistry

class SemanticEquivalenceAuthorityTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }

    @Test
    fun repositoryRulesPassEveryIndependentEvidenceCategory() {
        val report = SemanticEquivalenceAuthority(File("."), modules).analyze()

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertTrue(report.schemaErrors.isEmpty())
        assertTrue(report.coverageErrors.isEmpty())
        assertTrue(report.polarityErrors.isEmpty())
        assertTrue(report.independenceErrors.isEmpty())
        assertTrue(report.concreteReferenceErrors.isEmpty())
        assertTrue(report.boundaryErrors.isEmpty())
    }

    @Test
    fun implementationLabelsCannotChangeObservableRequirements() {
        SemanticEquivalenceFixture.entries.forEach { fixture ->
            val reference = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(fixture)
            )
            val alternate = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(fixture, alternateImplementationLabels = true)
            )

            assertEquals(reference, alternate, fixture.name)
        }
    }

    @Test
    fun everyClosedObservationKindHasPositiveAndNegativePolarity() {
        val observedKinds = mutableSetOf<SemanticObservationKind>()
        SemanticEquivalenceFixture.entries.forEach { fixture ->
            val requirements = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(fixture)
            )
            observedKinds += requirements.map { it.kind }
            val baseline = SemanticObservationAuthority.fullyPreserved(requirements, "test:$fixture:baseline")
            assertEquals(
                SemanticEquivalenceDecisionStatus.EQUIVALENT,
                SemanticObservationAuthority.assess(requirements, baseline).decision.status
            )

            requirements.forEach { requirement ->
                SemanticObservationEvidenceStatus.entries
                    .filterNot { it == SemanticObservationEvidenceStatus.PRESERVED }
                    .forEach { status ->
                        val evidence = if (status == SemanticObservationEvidenceStatus.MISSING) {
                            baseline.filterNot { it.requirementId == requirement.id }
                        } else {
                            baseline.map {
                                if (it.requirementId == requirement.id) it.copy(status = status) else it
                            }
                        }
                        val assessment = SemanticObservationAuthority.assess(requirements, evidence)
                        assertEquals(
                            SemanticEquivalenceDecisionStatus.NOT_EQUIVALENT,
                            assessment.decision.status,
                            "$fixture ${requirement.id} $status"
                        )
                    }
            }
        }

        assertEquals(SemanticObservationKind.entries.toSet(), observedKinds)
    }

    @Test
    fun emptyObservationSetNeverCertifiesVacuousEquivalence() {
        val assessment = SemanticObservationAuthority.assess(emptyList(), emptyList())

        assertEquals(SemanticEquivalenceDecisionStatus.NOT_EQUIVALENT, assessment.decision.status)
        assertEquals(SemanticObservationEvidenceStatus.UNKNOWN, assessment.observations.single().status)
    }

    @Test
    fun unknownRulesFieldFailsClosedAtSharedYamlBoundary() {
        val root = Files.createTempDirectory("flow-c0-3-equivalence").toFile()
        try {
            val file = File(root, SemanticEquivalenceLoader.PATH)
            file.parentFile.mkdirs()
            file.writeText(
                """
                version: "1.0"
                mutations: []
                cases: []
                concretePairs: []
                targetSyntaxEquality: true
                """.trimIndent()
            )

            val failure = assertFailsWith<IllegalArgumentException> {
                SemanticEquivalenceLoader.load(root)
            }
            assertTrue(failure.message.orEmpty().contains("unknown fields"))
        } finally {
            root.deleteRecursively()
        }
    }
}
