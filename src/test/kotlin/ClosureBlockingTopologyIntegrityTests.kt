import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.generators.manifest.InvalidPlanningEvidenceException
import org.flowlang.generators.manifest.MandatoryMaterializationAuthority
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.topology.ExecutionPlanCanonicalTopologyAuthority
import org.flowlang.topology.ExecutionTopologyRequirementSource

class ClosureBlockingTopologyIntegrityTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }

    @Test
    fun intentDerivedPlanReproducesCanonicalTopologyFromIndependentSourceMetadata() {
        val plan = approvalPlan()
        val derived = ExecutionPlanCanonicalTopologyAuthority.requirementsFor(plan)
        val actual = plan.topologyRequirements.filter {
            it.source == ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW ||
                it.source == ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY
        }

        assertEquals(derived, actual.sortedBy { it.id })
        assertTrue(derived.any { it.source == ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW })
        assertTrue(derived.any { it.source == ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY })
    }

    @Test
    fun omittedCanonicalWorkflowAndCapabilityTopologyIsRejected() {
        val plan = approvalPlan()
        val damaged = plan.copy(
            topologyRequirements = plan.topologyRequirements.filterNot {
                it.source == ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW ||
                    it.source == ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY
            }
        )

        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            MandatoryMaterializationAuthority(targets, modules)
                .authorizeDiagnosticEvidence(damaged, "jenkins")
        }

        assertTrue(failure.issues.any { it.code == "planning.topology.canonical.missing" })
    }

    @Test
    fun forgedCanonicalTopologyProvenanceIsRejected() {
        val plan = approvalPlan()
        val canonical = plan.topologyRequirements.first {
            it.source == ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY
        }
        val damaged = plan.copy(
            topologyRequirements = plan.topologyRequirements.map {
                if (it.id == canonical.id) it.copy(evidenceReference = "plan.nodes.forged") else it
            }
        )

        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            MandatoryMaterializationAuthority(targets, modules)
                .authorizeDiagnosticEvidence(damaged, "jenkins")
        }

        assertTrue(failure.issues.any { it.code == "planning.topology.canonical.invalid" })
    }

    @Test
    fun retainedCanonicalClaimsWithoutSourceProvenanceAreRejected() {
        val damaged = approvalPlan().copy(sourceIntent = null)

        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            MandatoryMaterializationAuthority(targets, modules)
                .authorizeDiagnosticEvidence(damaged, "jenkins")
        }

        assertTrue(failure.issues.any { it.code == "planning.topology.canonical.provenance.missing" })
    }

    @Test
    fun removingSourceAndCanonicalClaimsCannotHideIntentDerivedTopology() {
        val plan = approvalPlan()
        val damaged = plan.copy(
            sourceIntent = null,
            topologyRequirements = plan.topologyRequirements.filterNot {
                it.source == ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW ||
                    it.source == ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY
            }
        )

        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            MandatoryMaterializationAuthority(targets, modules)
                .authorizeDiagnosticEvidence(damaged, "jenkins")
        }

        assertTrue(failure.issues.any { it.code == "planning.topology.canonical.provenance.missing" })
    }

    private fun approvalPlan() = FlowPlanner(modules).plan(
        IntentToAstPlanner(modules).plan(
            IntentDocument(
                name = "approval-topology",
                workflows = listOf(
                    IntentWorkflow(
                        name = "delivery",
                        kind = IntentWorkflowKind.DEPLOY,
                        steps = listOf(
                            IntentStep(id = "approve-release", capability = StandardCapability.APPROVE)
                        )
                    )
                )
            )
        )
    )
}
