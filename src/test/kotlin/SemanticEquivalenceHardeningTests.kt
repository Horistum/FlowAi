import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.cli.Json
import org.flowlang.conformance.SemanticEquivalenceDecisionStatus
import org.flowlang.conformance.SemanticEquivalenceFixture
import org.flowlang.conformance.SemanticEquivalenceMutation
import org.flowlang.conformance.SemanticEquivalencePlanFactory
import org.flowlang.conformance.SemanticObservationAuthority
import org.flowlang.conformance.SemanticObservationEvidenceStatus
import org.flowlang.conformance.applySemanticEquivalenceMutation
import org.flowlang.conformance.semanticIndependencePolarityErrors
import org.flowlang.conformance.semanticSnapshotPlanErrors

class SemanticEquivalenceHardeningTests {
    @Test
    fun contradictoryMutationExercisesSingleEvidenceStatusPath() {
        val requirements = SemanticObservationAuthority.requirementsFor(
            SemanticEquivalencePlanFactory.plan(SemanticEquivalenceFixture.EFFECT)
        )
        val requirement = requirements.single()
        val baseline = SemanticObservationAuthority.fullyPreserved(requirements, "test:baseline")

        val contradictory = applySemanticEquivalenceMutation(
            baseline,
            requirement,
            SemanticEquivalenceMutation.CONTRADICTORY
        )

        assertEquals(baseline.size, contradictory.size)
        assertEquals(1, contradictory.count { it.requirementId == requirement.id })
        assertEquals(
            SemanticObservationEvidenceStatus.CONTRADICTORY,
            contradictory.single { it.requirementId == requirement.id }.status
        )

        val assessment = SemanticObservationAuthority.assess(requirements, contradictory)
        val affected = assessment.observations.single { it.requirement?.id == requirement.id }
        assertEquals(SemanticEquivalenceDecisionStatus.NOT_EQUIVALENT, assessment.decision.status)
        assertEquals(SemanticObservationEvidenceStatus.CONTRADICTORY, affected.status)
        assertTrue(affected.message.orEmpty().contains("is CONTRADICTORY"))
        assertTrue(!affected.message.orEmpty().contains("multiple evidence records"))
    }

    @Test
    fun duplicateEvidenceRemainsASeparateNegativePath() {
        val requirements = SemanticObservationAuthority.requirementsFor(
            SemanticEquivalencePlanFactory.plan(SemanticEquivalenceFixture.EFFECT)
        )
        val requirement = requirements.single()
        val baseline = SemanticObservationAuthority.fullyPreserved(requirements, "test:baseline")
        val duplicate = baseline + baseline.single().copy(evidenceReference = "test:duplicate")

        val assessment = SemanticObservationAuthority.assess(requirements, duplicate)
        val affected = assessment.observations.single { it.requirement?.id == requirement.id }

        assertEquals(SemanticEquivalenceDecisionStatus.NOT_EQUIVALENT, assessment.decision.status)
        assertEquals(SemanticObservationEvidenceStatus.CONTRADICTORY, affected.status)
        assertTrue(affected.message.orEmpty().contains("multiple evidence records"))
    }

    @Test
    fun implementationIndependenceHasPositiveAndSemanticMutationPolarities() {
        SemanticEquivalenceFixture.entries.forEach { fixture ->
            val reference = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(fixture)
            )
            val alternateImplementation = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(fixture, alternateImplementationLabels = true)
            )
            val alternateSemanticMeaning = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(
                    fixture,
                    alternateImplementationLabels = true,
                    alternateSemanticMeaning = true
                )
            )

            assertTrue(
                semanticIndependencePolarityErrors(
                    fixture.name,
                    reference,
                    alternateImplementation,
                    alternateSemanticMeaning
                ).isEmpty(),
                fixture.name
            )
            val brokenNegativePolarity = semanticIndependencePolarityErrors(
                fixture.name,
                reference,
                alternateImplementation,
                reference
            )
            assertTrue(
                brokenNegativePolarity.any { it.contains("semantic meaning changed") },
                fixture.name
            )
        }
    }

    @Test
    fun availableSnapshotIsStillCheckedWhenPeerPlanIsMissing() {
        val root = Files.createTempDirectory("semantic-snapshot-independent").toFile()
        try {
            val staleRight = root.resolve("right-plan.json")
            staleRight.writeText("{\"plan\":\"stale\"}")
            val expected = Json.mapper.readTree("{\"plan\":\"current\"}")

            val errors = semanticSnapshotPlanErrors(
                pairId = "independent-snapshot-check",
                leftTarget = "left-target",
                rightTarget = "right-target",
                expectedTree = expected,
                leftPlanFile = null,
                rightPlanFile = staleRight
            )

            assertTrue(
                errors.any {
                    it.contains("right-target snapshot plan is stale relative to the production planning path")
                },
                errors.joinToString(" | ")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedSnapshotDoesNotSuppressIndependentPeerStaleness() {
        val root = Files.createTempDirectory("semantic-snapshot-diagnostics").toFile()
        try {
            val malformedLeft = root.resolve("left-plan.json").apply { writeText("{") }
            val staleRight = root.resolve("right-plan.json").apply { writeText("{\"plan\":\"stale\"}") }
            val expected = Json.mapper.readTree("{\"plan\":\"current\"}")

            val errors = semanticSnapshotPlanErrors(
                pairId = "independent-diagnostics",
                leftTarget = "left-target",
                rightTarget = "right-target",
                expectedTree = expected,
                leftPlanFile = malformedLeft,
                rightPlanFile = staleRight
            )

            assertTrue(errors.any { it.contains("left execution plan cannot be parsed") })
            assertTrue(errors.any { it.contains("right-target snapshot plan is stale") })
        } finally {
            root.deleteRecursively()
        }
    }
}
