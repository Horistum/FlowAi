import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.conformance.ReferenceSnapshotBundleGenerator
import org.flowlang.conformance.SemanticEquivalenceAuthority
import org.flowlang.conformance.SemanticEquivalenceDecisionStatus
import org.flowlang.conformance.SemanticEquivalenceFixture
import org.flowlang.conformance.SemanticEquivalenceLoader
import org.flowlang.conformance.SemanticEquivalencePlanFactory
import org.flowlang.conformance.SemanticImplementationObservationAuthority
import org.flowlang.conformance.semanticEffectEvidenceStatus
import org.flowlang.conformance.SemanticObservationAuthority
import org.flowlang.conformance.SemanticObservationEvidenceStatus
import org.flowlang.conformance.SemanticObservationKind
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.effects.SemanticEffect
import org.flowlang.effects.canonicalObservationValue
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.PlanDependencyResolution
import org.flowlang.planner.TaskNode

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
    fun concreteProfilesUseTargetBackedEvidenceForEveryObservation() {
        val plan = ReferenceSnapshotBundleGenerator(File("."), modules).planFor(
            File("examples/intent/checkout-build-image.intent.yaml")
        )
        val requirements = SemanticObservationAuthority.requirementsFor(plan)
        val authority = SemanticImplementationObservationAuthority(File("."))
        val committedArtifacts = mapOf(
            "jenkins" to File("conformance/snapshots/checkout-build-image/jenkins.executable.yaml"),
            "github-actions" to File(
                "conformance/snapshots/github-actions-checkout-build-image/github-actions.executable.yaml"
            )
        )

        listOf("jenkins", "github-actions").forEach { target ->
            val profile = authority.profile(
                plan = plan,
                target = target,
                scenarioId = "checkout-build-image",
                requirements = requirements
            )
            val assessment = SemanticObservationAuthority.assess(requirements, profile.evidence)

            assertEquals(requirements.size, profile.evidence.size, target)
            assertEquals(SemanticEquivalenceDecisionStatus.EQUIVALENT, assessment.decision.status, target)
            assertTrue(profile.evidence.all { it.status == SemanticObservationEvidenceStatus.PRESERVED }, target)
            assertTrue(profile.evidence.all { it.evidenceReference.startsWith("$target:") }, target)
            assertEquals(TargetRenderMode.EXECUTABLE, profile.rendering.receipt.renderMode, target)
            assertEquals(AdapterRenderedArtifactKind.EXECUTABLE_TARGET, profile.rendering.artifact.kind, target)
            assertEquals(profile.rendering.artifact.sha256, profile.rendering.receipt.artifactSha256, target)
            assertEquals(committedArtifacts.getValue(target).readText(), profile.rendering.artifact.content, target)
        }
    }

    @Test
    fun implementationProfileRejectsCallerSuppliedObservationSubset() {
        val plan = ReferenceSnapshotBundleGenerator(File("."), modules).planFor(
            File("examples/intent/checkout-build-image.intent.yaml")
        )
        val requirements = SemanticObservationAuthority.requirementsFor(plan)

        val failure = assertFailsWith<IllegalArgumentException> {
            SemanticImplementationObservationAuthority(File(".")).profile(
                plan = plan,
                target = "jenkins",
                scenarioId = "checkout-build-image",
                requirements = requirements.dropLast(1)
            )
        }

        assertTrue(failure.message.orEmpty().contains("exact observation set"))
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
    fun everyIndependenceFixtureAlsoDetectsARealSemanticMutation() {
        SemanticEquivalenceFixture.entries.forEach { fixture ->
            val reference = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(fixture)
            )
            val semanticAlternate = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(fixture, alternateSemanticMeaning = true)
            )

            assertTrue(reference != semanticAlternate, fixture.name)
        }
    }

    @Test
    fun effectEvidenceRequiresTheExactManifestEffectTuple() {
        val effect = SemanticEffect(
            domain = EffectDomain.INFRASTRUCTURE_STATE,
            operation = EffectOperation.UPSERT,
            resource = "deployment.state",
            sourceCapability = "DEPLOY"
        )
        val task = TaskNode(
            id = "deploy",
            module = "reference",
            action = "deploy",
            target = "system",
            effectModel = listOf(effect)
        )
        val matching = TargetStep(
            id = "deploy",
            type = "action",
            materialization = TargetMaterialization.native("deploy", "test"),
            metadata = mapOf("semanticEffect.0" to effect.canonicalObservationValue())
        )
        val contradictory = matching.copy(
            metadata = mapOf("semanticEffect.0" to effect.copy(resource = "other.state").canonicalObservationValue())
        )

        assertEquals(
            SemanticObservationEvidenceStatus.PRESERVED,
            semanticEffectEvidenceStatus(task, matching, effect.canonicalObservationValue(), native = true)
        )
        assertEquals(
            SemanticObservationEvidenceStatus.CONTRADICTORY,
            semanticEffectEvidenceStatus(task, contradictory, effect.canonicalObservationValue(), native = true)
        )
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
    fun unresolvedContinuityCannotBecomeAValidObservationBaseline() {
        val baseline = SemanticEquivalencePlanFactory.plan(SemanticEquivalenceFixture.VALUE_CONTINUITY)
        val unresolved = baseline.copy(
            dependencyRelations = baseline.dependencyRelations.map {
                it.copy(resolution = PlanDependencyResolution.UNRESOLVED)
            }
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            SemanticObservationAuthority.requirementsFor(unresolved)
        }

        assertTrue(failure.message.orEmpty().contains("cannot certify"))
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
