import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.materialization.MaterializationDecision
import org.flowlang.materialization.MaterializationEvidence
import org.flowlang.materialization.MaterializationEvidenceKind
import org.flowlang.materialization.MaterializationNegotiation
import org.flowlang.materialization.MaterializationNegotiationStatus
import org.flowlang.materialization.MaterializationNegotiationValidator
import org.flowlang.materialization.MaterializationStatus
import org.flowlang.materialization.StandardMaterializationNegotiations
import org.flowlang.notes.StandardNotesPackageContracts
import org.flowlang.obligations.StandardArchitectureObligationGraphs

class FlowMaterializationNegotiationTests {
    private val notes = StandardNotesPackageContracts.baseline()
    private val graph = StandardArchitectureObligationGraphs.baseline()

    @Test
    fun baselineMaterializationNegotiationIsExplicitAndValid() {
        val negotiation = StandardMaterializationNegotiations.baseline(graph)
        val report = MaterializationNegotiationValidator(notes).validate(negotiation)

        assertEquals(MaterializationNegotiationStatus.PASS, report.status)
        assertTrue(report.valid)
        assertEquals(graph.nodes.size, report.decisions)
        assertTrue(negotiation.decisions.all { it.reason.isNotBlank() })
        assertTrue(negotiation.decisions.all { it.evidence.isNotEmpty() })
        assertTrue(negotiation.decisions.any { it.status == MaterializationStatus.ADAPTER_REQUIRED })
        assertFalse(negotiation.decisions.all { it.canAdvanceToProjection })
    }

    @Test
    fun everySemanticNodeRequiresOneMaterializationDecision() {
        val baseline = StandardMaterializationNegotiations.baseline(graph)
        val negotiation = baseline.copy(decisions = baseline.decisions.filterNot { it.nodeId == "runtime.human-approval" })

        val report = MaterializationNegotiationValidator(notes).validate(negotiation)

        assertEquals(MaterializationNegotiationStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "materialization.decision.missing" })
    }

    @Test
    fun duplicateAndUnknownDecisionNodesAreRejected() {
        val baseline = StandardMaterializationNegotiations.baseline(graph)
        val duplicate = baseline.decisions.first()
        val unknown = duplicate.copy(nodeId = "unknown.node")
        val negotiation = baseline.copy(decisions = baseline.decisions + duplicate + unknown)

        val report = MaterializationNegotiationValidator(notes).validate(negotiation)

        assertEquals(MaterializationNegotiationStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "materialization.decision.duplicate" })
        assertTrue(report.issues.any { it.code == "materialization.decision.node.unknown" })
    }

    @Test
    fun runtimeRequirementCannotBeMarkedMaterializableWithoutAdapterBoundary() {
        val baseline = StandardMaterializationNegotiations.baseline(graph)
        val negotiation = baseline.replaceDecision(
            "runtime.human-approval",
            MaterializationDecision(
                nodeId = "runtime.human-approval",
                status = MaterializationStatus.MATERIALIZABLE,
                reason = "Incorrectly bypasses runtime boundary.",
                evidence = listOf(MaterializationEvidence("human.approval", MaterializationEvidenceKind.RUNTIME_REQUIREMENT))
            )
        )

        val report = MaterializationNegotiationValidator(notes).validate(negotiation)

        assertEquals(MaterializationNegotiationStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "materialization.status.adapter-boundary-required" })
    }

    @Test
    fun adapterRequiredDecisionNeedsAdapterEvidence() {
        val baseline = StandardMaterializationNegotiations.baseline(graph)
        val negotiation = baseline.replaceDecision(
            "runtime.human-approval",
            MaterializationDecision(
                nodeId = "runtime.human-approval",
                status = MaterializationStatus.ADAPTER_REQUIRED,
                reason = "Adapter is needed but evidence is wrong.",
                evidence = listOf(MaterializationEvidence("human.approval", MaterializationEvidenceKind.NOTES_DECLARATION))
            )
        )

        val report = MaterializationNegotiationValidator(notes).validate(negotiation)

        assertEquals(MaterializationNegotiationStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "materialization.adapter.evidence.missing" })
    }

    @Test
    fun blockedDecisionRequiresSafetyOrReviewEvidence() {
        val baseline = StandardMaterializationNegotiations.baseline(graph)
        val negotiation = baseline.replaceDecision(
            "safety.approval-required",
            MaterializationDecision(
                nodeId = "safety.approval-required",
                status = MaterializationStatus.BLOCKED,
                reason = "Blocked but lacks safety evidence.",
                evidence = listOf(MaterializationEvidence("approval.required", MaterializationEvidenceKind.NOTES_DECLARATION))
            )
        )

        val report = MaterializationNegotiationValidator(notes).validate(negotiation)

        assertEquals(MaterializationNegotiationStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "materialization.blocked.evidence.missing" })
    }

    @Test
    fun materializableDecisionMustCiteTheSemanticDeclaration() {
        val baseline = StandardMaterializationNegotiations.baseline(graph)
        val negotiation = baseline.replaceDecision(
            "capability.approval-require",
            MaterializationDecision(
                nodeId = "capability.approval-require",
                status = MaterializationStatus.MATERIALIZABLE,
                reason = "Capability is declared but the wrong evidence is cited.",
                evidence = listOf(MaterializationEvidence("notification.send", MaterializationEvidenceKind.CAPABILITY_DECLARATION))
            )
        )

        val report = MaterializationNegotiationValidator(notes).validate(negotiation)

        assertEquals(MaterializationNegotiationStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "materialization.evidence.declaration.missing" })
    }

    private fun MaterializationNegotiation.replaceDecision(
        nodeId: String,
        replacement: MaterializationDecision
    ): MaterializationNegotiation = copy(decisions = decisions.map { if (it.nodeId == nodeId) replacement else it })
}
