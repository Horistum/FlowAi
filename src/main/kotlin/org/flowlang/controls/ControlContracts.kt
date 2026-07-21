package org.flowlang.controls

/**
 * Target-neutral control requirements, evidence and decisions.
 *
 * A requirement states what must be controlled. Evidence states what is known
 * about that requirement. A decision states whether planning may continue.
 * Keeping these concepts separate prevents a policy declaration from being
 * mistaken for proof that its control was actually provided.
 */
enum class ControlRequirementKind {
    APPROVAL,
    DRY_RUN,
    BACKUP,
    ROLLBACK_PLAN,
    CHANGE_TICKET,
    RETENTION_GUARD,
    CLARIFICATION,
    RISK_MITIGATION,
    SAFETY_GUARD,
    EXTERNAL_EFFECT_REVIEW,
    POLICY_EVALUATION
}

enum class ControlRequirementSource {
    CANONICAL_CAPABILITY,
    INTENT_POLICY,
    MODULE_CONTRACT,
    ENVIRONMENT_POLICY,
    FLOW_SOURCE
}

data class ControlRequirement(
    val id: String,
    val kind: ControlRequirementKind,
    val subject: String,
    val source: ControlRequirementSource,
    val condition: String? = null,
    val message: String? = null
) {
    init {
        require(id.isNotBlank()) { "Control requirement id must not be blank." }
        require(subject.isNotBlank()) { "Control requirement subject must not be blank." }
    }
}

enum class ControlEvidenceStatus {
    SATISFIED,
    UNSATISFIED,
    UNKNOWN,
    DYNAMIC
}

enum class ControlEvidenceSource {
    AUTHORED_STEP,
    AUTHORED_PARAMETER,
    AUTHORED_POLICY,
    AUTHORED_FAILURE_POLICY,
    AST_SAFETY_DECLARATION,
    MODULE_CONTRACT,
    DYNAMIC_CONDITION,
    MISSING
}

data class ControlEvidence(
    val requirementId: String,
    val status: ControlEvidenceStatus,
    val source: ControlEvidenceSource,
    val detail: String? = null,
    val enforcementCapabilities: List<String> = emptyList()
) {
    init {
        require(requirementId.isNotBlank()) { "Control evidence requirement id must not be blank." }
        require(enforcementCapabilities.none(String::isBlank)) {
            "Control evidence enforcement capability must not be blank."
        }
        require(status != ControlEvidenceStatus.DYNAMIC || enforcementCapabilities.isNotEmpty()) {
            "Dynamic control evidence must declare its enforcement capabilities."
        }
    }
}

enum class ControlDecisionStatus {
    ALLOWED,
    BLOCKED,
    PENDING
}

data class ControlDecision(
    val status: ControlDecisionStatus,
    val blockingRequirementIds: List<String> = emptyList(),
    val pendingRequirementIds: List<String> = emptyList()
)

data class ControlAssessment(
    val requirements: List<ControlRequirement> = emptyList(),
    val evidence: List<ControlEvidence> = emptyList(),
    val decision: ControlDecision = ControlDecision(ControlDecisionStatus.ALLOWED)
)

object ControlDecisionAuthority {
    fun evaluate(
        requirements: List<ControlRequirement>,
        evidence: List<ControlEvidence>
    ): ControlDecision {
        require(requirements.distinctBy(ControlRequirement::id).size == requirements.size) {
            "Control requirements must use unique ids."
        }
        require(evidence.groupBy(ControlEvidence::requirementId).values.none { it.size > 1 }) {
            "Control requirements must have at most one evidence record."
        }
        val evidenceById = evidence.associateBy(ControlEvidence::requirementId)
        require(evidenceById.keys.all { id -> requirements.any { it.id == id } }) {
            "Control evidence must reference a declared requirement."
        }
        val blocking = mutableListOf<String>()
        val pending = mutableListOf<String>()
        requirements.forEach { requirement ->
            when (evidenceById[requirement.id]?.status ?: ControlEvidenceStatus.UNKNOWN) {
                ControlEvidenceStatus.SATISFIED -> Unit
                ControlEvidenceStatus.UNSATISFIED,
                ControlEvidenceStatus.UNKNOWN -> blocking += requirement.id
                ControlEvidenceStatus.DYNAMIC -> pending += requirement.id
            }
        }
        return when {
            blocking.isNotEmpty() -> ControlDecision(
                status = ControlDecisionStatus.BLOCKED,
                blockingRequirementIds = blocking.sorted(),
                pendingRequirementIds = pending.sorted()
            )
            pending.isNotEmpty() -> ControlDecision(
                status = ControlDecisionStatus.PENDING,
                pendingRequirementIds = pending.sorted()
            )
            else -> ControlDecision(ControlDecisionStatus.ALLOWED)
        }
    }

    fun assessment(
        requirements: List<ControlRequirement>,
        evidence: List<ControlEvidence>
    ): ControlAssessment = ControlAssessment(
        requirements = requirements,
        evidence = evidence,
        decision = evaluate(requirements, evidence)
    )
}
