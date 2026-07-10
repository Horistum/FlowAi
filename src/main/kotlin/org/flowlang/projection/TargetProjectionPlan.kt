package org.flowlang.projection

import org.flowlang.materialization.MaterializationDecision
import org.flowlang.materialization.MaterializationNegotiation
import org.flowlang.materialization.MaterializationNegotiationValidator
import org.flowlang.materialization.MaterializationStatus
import org.flowlang.notes.NotesPackageContract
import org.flowlang.semantic.SemanticActionKind

/**
 * Target projection plan contract.
 *
 * This model describes the artifacts that may be produced after semantic action
 * graph validation and materialization negotiation. It records projection intent
 * without treating a command string as the universal representation of work.
 */
enum class TargetProjectionArtifactKind {
    TARGET_NATIVE,
    NOTES_BACKED,
    ADAPTER_BOUNDARY,
    REVIEW_RECORD,
    CONFORMANCE_RECORD
}

data class TargetProjectionArtifact(
    val artifactId: String,
    val nodeId: String,
    val kind: TargetProjectionArtifactKind,
    val materializationStatus: MaterializationStatus,
    val target: String = "",
    val notesReference: String = "",
    val description: String = "",
    val fields: Map<String, String> = emptyMap()
)

data class TargetProjectionPlan(
    val planId: String,
    val negotiation: MaterializationNegotiation,
    val artifacts: List<TargetProjectionArtifact>
) {
    fun artifactsByNode(): Map<String, List<TargetProjectionArtifact>> = artifacts.groupBy { it.nodeId }
}

enum class TargetProjectionPlanStatus {
    PASS,
    FAIL
}

data class TargetProjectionPlanIssue(
    val code: String,
    val planId: String,
    val message: String
)

data class TargetProjectionPlanReport(
    val status: TargetProjectionPlanStatus,
    val planId: String,
    val artifacts: Int,
    val issues: List<TargetProjectionPlanIssue>
) {
    val valid: Boolean = status == TargetProjectionPlanStatus.PASS
}

class TargetProjectionPlanValidator(
    private val notesPackages: List<NotesPackageContract>
) {
    fun validate(plan: TargetProjectionPlan): TargetProjectionPlanReport {
        val issues = mutableListOf<TargetProjectionPlanIssue>()

        if (!PLAN_ID.matches(plan.planId)) {
            issues += issue(plan, "projection.plan.id.invalid", "Target projection plan id must be lowercase dot-separated identifier text.")
        }

        val negotiationReport = MaterializationNegotiationValidator(notesPackages).validate(plan.negotiation)
        if (!negotiationReport.valid) {
            negotiationReport.issues.forEach { negotiationIssue ->
                issues += issue(plan, "projection.negotiation.invalid", negotiationIssue.message)
            }
        }

        issues += validateArtifactCoverage(plan)
        issues += validateArtifacts(plan)

        return TargetProjectionPlanReport(
            status = if (issues.isEmpty()) TargetProjectionPlanStatus.PASS else TargetProjectionPlanStatus.FAIL,
            planId = plan.planId,
            artifacts = plan.artifacts.size,
            issues = issues
        )
    }

    private fun validateArtifactCoverage(plan: TargetProjectionPlan): List<TargetProjectionPlanIssue> {
        val issues = mutableListOf<TargetProjectionPlanIssue>()
        val artifactsByNode = plan.artifactsByNode()
        val decisionNodes = plan.negotiation.decisions.map { it.nodeId }.toSet()
        val graphNodes = plan.negotiation.graph.nodeIds()

        decisionNodes.filter { it !in artifactsByNode }.forEach { missingNode ->
            issues += issue(plan, "projection.artifact.missing", "Materialization decision '$missingNode' must have a projection artifact record.")
        }

        plan.artifacts.forEach { artifact ->
            if (artifact.nodeId !in graphNodes) {
                issues += issue(plan, "projection.artifact.node.unknown", "Projection artifact '${artifact.artifactId}' references unknown semantic node '${artifact.nodeId}'.")
            }
            if (artifact.nodeId !in decisionNodes) {
                issues += issue(plan, "projection.artifact.decision.missing", "Projection artifact '${artifact.artifactId}' has no materialization decision.")
            }
        }
        return issues
    }

    private fun validateArtifacts(plan: TargetProjectionPlan): List<TargetProjectionPlanIssue> {
        val issues = mutableListOf<TargetProjectionPlanIssue>()
        val seenArtifactIds = mutableSetOf<String>()
        val decisionsByNode = plan.negotiation.decisionsByNode()

        plan.artifacts.forEach { artifact ->
            if (!ARTIFACT_ID.matches(artifact.artifactId)) {
                issues += issue(plan, "projection.artifact.id.invalid", "Projection artifact id '${artifact.artifactId}' is not valid.")
            }
            if (!seenArtifactIds.add(artifact.artifactId)) {
                issues += issue(plan, "projection.artifact.id.duplicate", "Projection artifact id '${artifact.artifactId}' is duplicated.")
            }

            val decision = decisionsByNode[artifact.nodeId]
            if (decision != null) {
                issues += validateStatusAlignment(plan, artifact, decision)
            }

            issues += validateArtifactShape(plan, artifact)
            issues += validateNoCommandRepresentation(plan, artifact)
        }
        return issues
    }

    private fun validateStatusAlignment(
        plan: TargetProjectionPlan,
        artifact: TargetProjectionArtifact,
        decision: MaterializationDecision
    ): List<TargetProjectionPlanIssue> {
        val issues = mutableListOf<TargetProjectionPlanIssue>()
        if (artifact.materializationStatus != decision.status) {
            issues += issue(plan, "projection.artifact.status.mismatch", "Projection artifact '${artifact.artifactId}' status must match materialization decision for '${artifact.nodeId}'.")
        }

        when (decision.status) {
            MaterializationStatus.MATERIALIZABLE -> {
                if (artifact.kind !in MATERIALIZABLE_ARTIFACTS) {
                    issues += issue(plan, "projection.artifact.kind.not-materializable", "Materializable node '${artifact.nodeId}' must use a target-native, notes-backed or conformance artifact.")
                }
            }
            MaterializationStatus.ADAPTER_REQUIRED -> {
                if (artifact.kind != TargetProjectionArtifactKind.ADAPTER_BOUNDARY && artifact.kind != TargetProjectionArtifactKind.REVIEW_RECORD) {
                    issues += issue(plan, "projection.artifact.adapter-required.kind", "Adapter-required node '${artifact.nodeId}' must remain an adapter boundary or review record.")
                }
            }
            MaterializationStatus.UNSUPPORTED,
            MaterializationStatus.BLOCKED,
            MaterializationStatus.DEFERRED -> {
                if (artifact.kind == TargetProjectionArtifactKind.TARGET_NATIVE || artifact.kind == TargetProjectionArtifactKind.NOTES_BACKED) {
                    issues += issue(plan, "projection.artifact.unavailable.kind", "Unavailable node '${artifact.nodeId}' must not produce a target-native or notes-backed artifact.")
                }
            }
        }
        return issues
    }

    private fun validateArtifactShape(plan: TargetProjectionPlan, artifact: TargetProjectionArtifact): List<TargetProjectionPlanIssue> {
        val issues = mutableListOf<TargetProjectionPlanIssue>()
        when (artifact.kind) {
            TargetProjectionArtifactKind.TARGET_NATIVE -> {
                if (artifact.target.isBlank()) {
                    issues += issue(plan, "projection.artifact.target.missing", "Target-native artifact '${artifact.artifactId}' must name a target boundary.")
                }
            }
            TargetProjectionArtifactKind.NOTES_BACKED -> {
                if (artifact.notesReference.isBlank()) {
                    issues += issue(plan, "projection.artifact.notes-reference.missing", "Notes-backed artifact '${artifact.artifactId}' must cite a notes reference.")
                }
            }
            TargetProjectionArtifactKind.ADAPTER_BOUNDARY,
            TargetProjectionArtifactKind.REVIEW_RECORD,
            TargetProjectionArtifactKind.CONFORMANCE_RECORD -> Unit
        }
        return issues
    }

    private fun validateNoCommandRepresentation(plan: TargetProjectionPlan, artifact: TargetProjectionArtifact): List<TargetProjectionPlanIssue> {
        val issues = mutableListOf<TargetProjectionPlanIssue>()
        artifact.allText().forEach { text ->
            val lowered = text.lowercase()
            FORBIDDEN_PROJECTION_TERMS.filter { lowered.contains(it) }.forEach { term ->
                issues += issue(plan, "projection.artifact.forbidden-term", "Projection artifact '${artifact.artifactId}' must not use '$term' as target projection representation.")
            }
        }
        return issues
    }

    private fun TargetProjectionArtifact.allText(): List<String> =
        listOf(artifactId, nodeId, target, notesReference, description) + fields.keys + fields.values

    private fun issue(plan: TargetProjectionPlan, code: String, message: String) =
        TargetProjectionPlanIssue(code = code, planId = plan.planId, message = message)

    companion object {
        private val PLAN_ID = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)*")
        private val ARTIFACT_ID = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)*")
        private val MATERIALIZABLE_ARTIFACTS = setOf(
            TargetProjectionArtifactKind.TARGET_NATIVE,
            TargetProjectionArtifactKind.NOTES_BACKED,
            TargetProjectionArtifactKind.CONFORMANCE_RECORD
        )
        private val FORBIDDEN_PROJECTION_TERMS = listOf(
            "shell",
            "command",
            "script",
            "bash",
            "cmd.exe",
            "powershell"
        )
    }
}

object StandardTargetProjectionPlans {
    fun baseline(negotiation: MaterializationNegotiation): TargetProjectionPlan = TargetProjectionPlan(
        planId = "flow.projection.baseline",
        negotiation = negotiation,
        artifacts = negotiation.decisions.map { decision -> baselineArtifact(negotiation, decision) }
    )

    private fun baselineArtifact(
        negotiation: MaterializationNegotiation,
        decision: MaterializationDecision
    ): TargetProjectionArtifact {
        val node = negotiation.graph.nodes.single { it.id == decision.nodeId }
        val kind = when (decision.status) {
            MaterializationStatus.MATERIALIZABLE -> when (node.kind) {
                SemanticActionKind.CONFORMANCE_REQUIREMENT -> TargetProjectionArtifactKind.CONFORMANCE_RECORD
                else -> TargetProjectionArtifactKind.NOTES_BACKED
            }
            MaterializationStatus.ADAPTER_REQUIRED -> TargetProjectionArtifactKind.ADAPTER_BOUNDARY
            MaterializationStatus.UNSUPPORTED,
            MaterializationStatus.BLOCKED,
            MaterializationStatus.DEFERRED -> TargetProjectionArtifactKind.REVIEW_RECORD
        }
        return TargetProjectionArtifact(
            artifactId = "artifact.${node.id}",
            nodeId = node.id,
            kind = kind,
            materializationStatus = decision.status,
            target = if (kind == TargetProjectionArtifactKind.TARGET_NATIVE) "target.native" else "",
            notesReference = if (kind == TargetProjectionArtifactKind.NOTES_BACKED) node.declaration else "",
            description = "Projection record for ${node.id}.",
            fields = mapOf("declaration" to node.declaration, "status" to decision.status.name)
        )
    }
}
