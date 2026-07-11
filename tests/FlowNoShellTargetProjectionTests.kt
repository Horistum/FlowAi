import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.materialization.MaterializationDecision
import org.flowlang.materialization.MaterializationEvidence
import org.flowlang.materialization.MaterializationEvidenceKind
import org.flowlang.materialization.MaterializationNegotiation
import org.flowlang.materialization.MaterializationStatus
import org.flowlang.materialization.StandardMaterializationNegotiations
import org.flowlang.notes.StandardNotesPackageContracts
import org.flowlang.projection.StandardTargetProjectionPlans
import org.flowlang.projection.TargetProjectionArtifactKind
import org.flowlang.projection.TargetProjectionPlan
import org.flowlang.projection.TargetProjectionPlanStatus
import org.flowlang.projection.TargetProjectionPlanValidator
import org.flowlang.semantic.StandardSemanticActionGraphs

class FlowNoShellTargetProjectionTests {
    private val notes = StandardNotesPackageContracts.baseline()
    private val graph = StandardSemanticActionGraphs.baseline()
    private val negotiation = StandardMaterializationNegotiations.baseline(graph)

    @Test
    fun baselineProjectionPlanIsValidAndKeepsAdapterBoundaryExplicit() {
        val plan = StandardTargetProjectionPlans.baseline(negotiation)
        val report = TargetProjectionPlanValidator(notes).validate(plan)

        assertEquals(TargetProjectionPlanStatus.PASS, report.status)
        assertTrue(report.valid)
        assertEquals(negotiation.decisions.size, report.artifacts)
        assertTrue(plan.artifacts.any { it.kind == TargetProjectionArtifactKind.ADAPTER_BOUNDARY && it.nodeId == "runtime.human-approval" })
        assertFalse(plan.artifacts.any { it.kind == TargetProjectionArtifactKind.TARGET_NATIVE && it.materializationStatus != MaterializationStatus.MATERIALIZABLE })
    }

    @Test
    fun everyMaterializationDecisionMustHaveProjectionArtifactRecord() {
        val plan = TargetProjectionPlan(
            planId = "flow.projection.incomplete",
            negotiation = negotiation,
            artifacts = StandardTargetProjectionPlans.baseline(negotiation).artifacts.dropLast(1)
        )

        val report = TargetProjectionPlanValidator(notes).validate(plan)

        assertEquals(TargetProjectionPlanStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "projection.artifact.missing" })
    }

    @Test
    fun materializableProjectionArtifactRejectsRawRuntimePayloadField() {
        val baseline = StandardTargetProjectionPlans.baseline(negotiation)
        val badArtifact = baseline.artifacts.first().copy(
            artifactId = "artifact.bad-runtime-payload",
            fields = baseline.artifacts.first().fields + ("command" to "execute arbitrary text")
        )
        val plan = baseline.copy(
            planId = "flow.projection.bad-runtime-payload",
            artifacts = listOf(badArtifact) + baseline.artifacts.drop(1)
        )

        val report = TargetProjectionPlanValidator(notes).validate(plan)

        assertEquals(TargetProjectionPlanStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "projection.artifact.forbidden-mechanism" })
    }

    @Test
    fun reviewDescriptionMayNameRejectedRuntimeMechanisms() {
        val baseline = StandardTargetProjectionPlans.baseline(negotiation)
        val artifacts = baseline.artifacts.map { artifact ->
            if (artifact.kind == TargetProjectionArtifactKind.ADAPTER_BOUNDARY) {
                artifact.copy(description = "A shell command is not materialized by this adapter boundary.")
            } else {
                artifact
            }
        }
        val plan = baseline.copy(planId = "flow.projection.diagnostic-language", artifacts = artifacts)

        val report = TargetProjectionPlanValidator(notes).validate(plan)

        assertEquals(TargetProjectionPlanStatus.PASS, report.status, report.issues.toString())
    }

    @Test
    fun adapterRequiredDecisionMustRemainAdapterBoundaryOrReviewRecord() {
        val artifacts = StandardTargetProjectionPlans.baseline(negotiation).artifacts.map { artifact ->
            if (artifact.nodeId == "runtime.human-approval") {
                artifact.copy(
                    kind = TargetProjectionArtifactKind.TARGET_NATIVE,
                    target = "target.native"
                )
            } else {
                artifact
            }
        }
        val plan = TargetProjectionPlan(
            planId = "flow.projection.adapter-invalid",
            negotiation = negotiation,
            artifacts = artifacts
        )

        val report = TargetProjectionPlanValidator(notes).validate(plan)

        assertEquals(TargetProjectionPlanStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "projection.artifact.adapter-required.kind" })
    }

    @Test
    fun targetNativeArtifactsMustDeclareTargetBoundary() {
        val materializableNegotiation = negotiation.copy(
            negotiationId = "flow.materialization.target-native-test",
            decisions = negotiation.decisions.map { decision ->
                if (decision.nodeId == "capability.approval-require") {
                    decision.copy(status = MaterializationStatus.MATERIALIZABLE)
                } else {
                    decision
                }
            }
        )
        val artifacts = StandardTargetProjectionPlans.baseline(materializableNegotiation).artifacts.map { artifact ->
            if (artifact.nodeId == "capability.approval-require") {
                artifact.copy(
                    kind = TargetProjectionArtifactKind.TARGET_NATIVE,
                    target = ""
                )
            } else {
                artifact
            }
        }
        val plan = TargetProjectionPlan(
            planId = "flow.projection.target-missing",
            negotiation = materializableNegotiation,
            artifacts = artifacts
        )

        val report = TargetProjectionPlanValidator(notes).validate(plan)

        assertEquals(TargetProjectionPlanStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "projection.artifact.target.missing" })
    }

    @Test
    fun unavailableDecisionMustNotProduceTargetNativeArtifact() {
        val blockedNegotiation = MaterializationNegotiation(
            negotiationId = "flow.materialization.blocked-test",
            graph = graph,
            decisions = negotiation.decisions.map { decision ->
                if (decision.nodeId == "safety.approval-required") {
                    MaterializationDecision(
                        nodeId = decision.nodeId,
                        status = MaterializationStatus.BLOCKED,
                        reason = "Safety review blocks projection.",
                        evidence = listOf(MaterializationEvidence("approval.required", MaterializationEvidenceKind.SAFETY_POLICY))
                    )
                } else {
                    decision
                }
            }
        )
        val artifacts = StandardTargetProjectionPlans.baseline(blockedNegotiation).artifacts.map { artifact ->
            if (artifact.nodeId == "safety.approval-required") {
                artifact.copy(
                    kind = TargetProjectionArtifactKind.TARGET_NATIVE,
                    target = "target.native"
                )
            } else {
                artifact
            }
        }
        val plan = TargetProjectionPlan(
            planId = "flow.projection.blocked-target-native",
            negotiation = blockedNegotiation,
            artifacts = artifacts
        )

        val report = TargetProjectionPlanValidator(notes).validate(plan)

        assertEquals(TargetProjectionPlanStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "projection.artifact.unavailable.kind" })
    }
}
