package org.flowlang.materialization

import org.flowlang.notes.NotesPackageContract
import org.flowlang.notes.NotesPackageKind
import org.flowlang.obligations.ArchitectureObligationGraph
import org.flowlang.obligations.ArchitectureObligationGraphValidator
import org.flowlang.obligations.ArchitectureObligationKind

/**
 * Target-neutral materialization negotiation for architecture-obligation evidence.
 *
 * This model records whether a semantic action can advance toward projection,
 * needs an adapter boundary, is unsupported, is blocked by policy, or must be
 * deferred. It does not perform work and does not describe a target artifact.
 */
enum class MaterializationStatus {
    MATERIALIZABLE,
    ADAPTER_REQUIRED,
    UNSUPPORTED,
    BLOCKED,
    DEFERRED
}

enum class MaterializationEvidenceKind {
    NOTES_DECLARATION,
    CAPABILITY_DECLARATION,
    SAFETY_POLICY,
    RUNTIME_REQUIREMENT,
    TARGET_REQUIREMENT,
    PROJECTION_RULE,
    CONFORMANCE_CHECK,
    REVIEW_DECISION
}

data class MaterializationEvidence(
    val reference: String,
    val kind: MaterializationEvidenceKind,
    val description: String = ""
)

data class MaterializationDecision(
    val nodeId: String,
    val status: MaterializationStatus,
    val reason: String,
    val evidence: List<MaterializationEvidence> = emptyList()
) {
    val canAdvanceToProjection: Boolean = status == MaterializationStatus.MATERIALIZABLE
}

data class MaterializationNegotiation(
    val negotiationId: String,
    val graph: ArchitectureObligationGraph,
    val decisions: List<MaterializationDecision>
) {
    fun decisionsByNode(): Map<String, MaterializationDecision> = decisions.associateBy { it.nodeId }
}

enum class MaterializationNegotiationStatus {
    PASS,
    FAIL
}

data class MaterializationNegotiationIssue(
    val code: String,
    val negotiationId: String,
    val message: String
)

data class MaterializationNegotiationReport(
    val status: MaterializationNegotiationStatus,
    val negotiationId: String,
    val graphId: String,
    val decisions: Int,
    val issues: List<MaterializationNegotiationIssue>
) {
    val valid: Boolean = status == MaterializationNegotiationStatus.PASS
}

class MaterializationNegotiationValidator(
    private val notesPackages: List<NotesPackageContract>
) {
    private val packagesById = notesPackages.associateBy { it.packageId }

    fun validate(negotiation: MaterializationNegotiation): MaterializationNegotiationReport {
        val issues = mutableListOf<MaterializationNegotiationIssue>()

        if (!NEGOTIATION_ID.matches(negotiation.negotiationId)) {
            issues += issue(negotiation, "materialization.negotiation.id.invalid", "Materialization negotiation id must be lowercase dot-separated identifier text.")
        }

        val graphReport = ArchitectureObligationGraphValidator(notesPackages).validate(negotiation.graph)
        if (!graphReport.valid) {
            graphReport.issues.forEach { graphIssue ->
                issues += issue(negotiation, "materialization.graph.invalid", graphIssue.message)
            }
        }

        issues += validateCoverage(negotiation)
        negotiation.decisions.forEach { decision ->
            issues += validateDecision(negotiation, decision)
        }

        return MaterializationNegotiationReport(
            status = if (issues.isEmpty()) MaterializationNegotiationStatus.PASS else MaterializationNegotiationStatus.FAIL,
            negotiationId = negotiation.negotiationId,
            graphId = negotiation.graph.graphId,
            decisions = negotiation.decisions.size,
            issues = issues
        )
    }

    private fun validateCoverage(negotiation: MaterializationNegotiation): List<MaterializationNegotiationIssue> {
        val issues = mutableListOf<MaterializationNegotiationIssue>()
        val nodeIds = negotiation.graph.nodeIds()
        val seen = mutableSetOf<String>()

        negotiation.decisions.forEach { decision ->
            if (!seen.add(decision.nodeId)) {
                issues += issue(negotiation, "materialization.decision.duplicate", "Materialization decision for node '${decision.nodeId}' is duplicated.")
            }
            if (decision.nodeId !in nodeIds) {
                issues += issue(negotiation, "materialization.decision.node.unknown", "Materialization decision references unknown semantic node '${decision.nodeId}'.")
            }
        }

        nodeIds.filter { it !in seen }.forEach { missingNode ->
            issues += issue(negotiation, "materialization.decision.missing", "Semantic node '$missingNode' must have an explicit materialization decision.")
        }
        return issues
    }

    private fun validateDecision(
        negotiation: MaterializationNegotiation,
        decision: MaterializationDecision
    ): List<MaterializationNegotiationIssue> {
        val issues = mutableListOf<MaterializationNegotiationIssue>()
        val node = negotiation.graph.nodes.firstOrNull { it.id == decision.nodeId } ?: return issues
        val notesPackage = packagesById[node.notesPackageId]

        if (decision.reason.isBlank()) {
            issues += issue(negotiation, "materialization.decision.reason.missing", "Materialization decision for '${decision.nodeId}' must explain the status.")
        }
        if (decision.evidence.isEmpty()) {
            issues += issue(negotiation, "materialization.decision.evidence.missing", "Materialization decision for '${decision.nodeId}' must include evidence.")
        }
        decision.evidence.forEach { evidence ->
            if (evidence.reference.isBlank()) {
                issues += issue(negotiation, "materialization.evidence.reference.missing", "Materialization evidence for '${decision.nodeId}' must include a reference.")
            }
        }

        if (decision.status == MaterializationStatus.MATERIALIZABLE && !node.kind.allowedToAdvanceWithoutAdapter()) {
            issues += issue(negotiation, "materialization.status.adapter-boundary-required", "Semantic node '${decision.nodeId}' cannot advance without an adapter or policy boundary.")
        }

        if (decision.status == MaterializationStatus.ADAPTER_REQUIRED && !decision.evidence.any { it.kind in ADAPTER_EVIDENCE_KINDS }) {
            issues += issue(negotiation, "materialization.adapter.evidence.missing", "Adapter-required decision for '${decision.nodeId}' must cite runtime, target or projection evidence.")
        }

        if (decision.status == MaterializationStatus.BLOCKED && !decision.evidence.any { it.kind == MaterializationEvidenceKind.SAFETY_POLICY || it.kind == MaterializationEvidenceKind.REVIEW_DECISION }) {
            issues += issue(negotiation, "materialization.blocked.evidence.missing", "Blocked decision for '${decision.nodeId}' must cite safety or review evidence.")
        }

        if (notesPackage != null && decision.status == MaterializationStatus.MATERIALIZABLE) {
            val declarationEvidenceMatches = decision.evidence.any { it.reference == node.declaration }
            if (!declarationEvidenceMatches) {
                issues += issue(negotiation, "materialization.evidence.declaration.missing", "Materializable decision for '${decision.nodeId}' must cite the semantic declaration '${node.declaration}'.")
            }
            if (notesPackage.kind == NotesPackageKind.TARGET || notesPackage.kind == NotesPackageKind.PROJECTION) {
                issues += issue(negotiation, "materialization.status.target-or-projection-premature", "Target and projection notes must not be marked materializable before target negotiation.")
            }
        }

        return issues
    }

    private fun ArchitectureObligationKind.allowedToAdvanceWithoutAdapter(): Boolean = when (this) {
        ArchitectureObligationKind.DOMAIN,
        ArchitectureObligationKind.CAPABILITY,
        ArchitectureObligationKind.SAFETY,
        ArchitectureObligationKind.CONFORMANCE_REQUIREMENT -> true
        ArchitectureObligationKind.RUNTIME_REQUIREMENT,
        ArchitectureObligationKind.TARGET_REQUIREMENT,
        ArchitectureObligationKind.PROJECTION_REQUIREMENT -> false
    }

    private fun issue(negotiation: MaterializationNegotiation, code: String, message: String) =
        MaterializationNegotiationIssue(code = code, negotiationId = negotiation.negotiationId, message = message)

    companion object {
        private val NEGOTIATION_ID = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)*")
        private val ADAPTER_EVIDENCE_KINDS = setOf(
            MaterializationEvidenceKind.RUNTIME_REQUIREMENT,
            MaterializationEvidenceKind.TARGET_REQUIREMENT,
            MaterializationEvidenceKind.PROJECTION_RULE
        )
    }
}

object StandardMaterializationNegotiations {
    fun baseline(graph: ArchitectureObligationGraph): MaterializationNegotiation = MaterializationNegotiation(
        negotiationId = "flow.materialization.baseline",
        graph = graph,
        decisions = listOf(
            MaterializationDecision(
                nodeId = "domain.automation-intent",
                status = MaterializationStatus.MATERIALIZABLE,
                reason = "Domain meaning is declared by notes and can advance to capability review.",
                evidence = listOf(MaterializationEvidence("automation.intent", MaterializationEvidenceKind.NOTES_DECLARATION))
            ),
            MaterializationDecision(
                nodeId = "capability.approval-require",
                status = MaterializationStatus.MATERIALIZABLE,
                reason = "Capability meaning is declared and remains target-neutral.",
                evidence = listOf(MaterializationEvidence("approval.require", MaterializationEvidenceKind.CAPABILITY_DECLARATION))
            ),
            MaterializationDecision(
                nodeId = "safety.approval-required",
                status = MaterializationStatus.MATERIALIZABLE,
                reason = "Safety policy is declared before projection.",
                evidence = listOf(MaterializationEvidence("approval.required", MaterializationEvidenceKind.SAFETY_POLICY))
            ),
            MaterializationDecision(
                nodeId = "runtime.human-approval",
                status = MaterializationStatus.ADAPTER_REQUIRED,
                reason = "Human approval requires a runtime boundary before projection can claim support.",
                evidence = listOf(MaterializationEvidence("human.approval", MaterializationEvidenceKind.RUNTIME_REQUIREMENT))
            ),
            MaterializationDecision(
                nodeId = "conformance.notes-contract-valid",
                status = MaterializationStatus.MATERIALIZABLE,
                reason = "Conformance evidence is declared as verification metadata.",
                evidence = listOf(MaterializationEvidence("notes.contract.valid", MaterializationEvidenceKind.CONFORMANCE_CHECK))
            )
        )
    )
}
