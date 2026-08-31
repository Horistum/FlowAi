package org.flowlang.projection

import org.flowlang.materialization.MaterializationDecision
import org.flowlang.materialization.MaterializationNegotiation
import org.flowlang.materialization.MaterializationNegotiationValidator
import org.flowlang.materialization.MaterializationStatus
import org.flowlang.notes.NotesPackageContract
import org.flowlang.obligations.ArchitectureObligationKind

/**
 * Target projection plan contract.
 *
 * This model describes artifacts that may be produced after semantic validation
 * and materialization negotiation. It validates structured projection ownership
 * instead of banning ordinary diagnostic words.
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

            decisionsByNode[artifact.nodeId]?.let { decision ->
                issues += validateStatusAlignment(plan, artifact, decision)
            }

            issues += validateArtifactShape(plan, artifact)
            issues += validateProjectionMechanism(plan, artifact)
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

    private fun validateProjectionMechanism(
        plan: TargetProjectionPlan,
        artifact: TargetProjectionArtifact
    ): List<TargetProjectionPlanIssue> {
        if (artifact.kind !in MATERIALIZABLE_PROJECTION_ARTIFACTS) return emptyList()

        val issues = mutableListOf<TargetProjectionPlanIssue>()
        artifact.fields.keys.filter { it.lowercase() in FORBIDDEN_RAW_PAYLOAD_KEYS }.forEach { key ->
            issues += issue(
                plan,
                "projection.artifact.forbidden-mechanism",
                "Materializable projection artifact '${artifact.artifactId}' must not carry raw runtime payload field '$key'."
            )
        }

        REPRESENTATION_KEYS.mapNotNull { key -> artifact.fields[key] }.forEach { representation ->
            val lowered = representation.lowercase()
            if (FORBIDDEN_REPRESENTATION_VALUES.any { term -> lowered == term || lowered.startsWith("$term.") }) {
                issues += issue(
                    plan,
                    "projection.artifact.forbidden-mechanism",
                    "Materializable projection artifact '${artifact.artifactId}' declares raw runtime representation '$representation'."
                )
            }
        }

        val notesReference = artifact.notesReference.lowercase()
        if (FORBIDDEN_NOTES_PREFIXES.any { notesReference == it.removeSuffix(".") || notesReference.startsWith(it) }) {
            issues += issue(
                plan,
                "projection.artifact.forbidden-mechanism",
                "Materializable projection artifact '${artifact.artifactId}' must not use raw runtime notes reference '${artifact.notesReference}'."
            )
        }
        return issues
    }

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
        private val MATERIALIZABLE_PROJECTION_ARTIFACTS = setOf(
            TargetProjectionArtifactKind.TARGET_NATIVE,
            TargetProjectionArtifactKind.NOTES_BACKED
        )
        private val FORBIDDEN_RAW_PAYLOAD_KEYS = setOf(
            "command",
            "script",
            "shell",
            "run"
        )
        private val REPRESENTATION_KEYS = setOf(
            "representation",
            "projectionMechanism",
            "executionMode"
        )
        private val FORBIDDEN_REPRESENTATION_VALUES = setOf(
            "shell",
            "command",
            "script",
            "bash",
            "cmd.exe",
            "powershell"
        )
        private val FORBIDDEN_NOTES_PREFIXES = setOf(
            "shell.",
            "command.",
            "script."
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
                ArchitectureObligationKind.CONFORMANCE_REQUIREMENT -> TargetProjectionArtifactKind.CONFORMANCE_RECORD
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
