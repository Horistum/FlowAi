package org.flowlang.topology

/**
 * Target-neutral execution-topology contracts.
 *
 * A plan requirement describes the execution environment that semantic work
 * needs. A target profile is implementation evidence for those requirements.
 * Matching the two is separate from action-capability compatibility: knowing
 * how to render a task does not prove that its isolation, lifetime, persistence
 * or propagation requirements can be represented safely.
 */
enum class ExecutionTopologyDimension {
    ISOLATION,
    LIFETIME,
    PERSISTENCE,
    PROPAGATION
}

enum class ExecutionTopologyKind(
    val dimension: ExecutionTopologyDimension,
    val registryKey: String
) {
    WORKFLOW_SCOPE(ExecutionTopologyDimension.ISOLATION, "workflowScope"),
    BRANCH_ISOLATION(ExecutionTopologyDimension.ISOLATION, "branchIsolation"),
    ATTEMPT_ISOLATION(ExecutionTopologyDimension.ISOLATION, "attemptIsolation"),
    WORKFLOW_LIFETIME(ExecutionTopologyDimension.LIFETIME, "workflowLifetime"),
    SUSPEND_RESUME(ExecutionTopologyDimension.LIFETIME, "suspendResume"),
    EPHEMERAL_WORKSPACE(ExecutionTopologyDimension.PERSISTENCE, "ephemeralWorkspace"),
    DURABLE_STATE(ExecutionTopologyDimension.PERSISTENCE, "durableState"),
    VALUE_PROPAGATION(ExecutionTopologyDimension.PROPAGATION, "valuePropagation"),
    WORKSPACE_PROPAGATION(ExecutionTopologyDimension.PROPAGATION, "workspacePropagation"),
    STATE_PROPAGATION(ExecutionTopologyDimension.PROPAGATION, "statePropagation"),
    FAILURE_PROPAGATION(ExecutionTopologyDimension.PROPAGATION, "failurePropagation");

    companion object {
        private val byRegistryKey = entries.associateBy(ExecutionTopologyKind::registryKey)

        fun fromRegistryKey(key: String): ExecutionTopologyKind? = byRegistryKey[key]
    }
}

enum class ExecutionTopologyRequirementSource {
    CANONICAL_WORKFLOW,
    CANONICAL_CAPABILITY,
    PLAN_STRUCTURE,
    DEPENDENCY_RELATION,
    MODULE_CONTRACT
}

data class ExecutionTopologyRequirement(
    val id: String,
    val kind: ExecutionTopologyKind,
    val subject: String,
    val source: ExecutionTopologyRequirementSource,
    val evidenceReference: String? = null
) {
    init {
        require(id.isNotBlank()) { "Execution topology requirement id must not be blank." }
        require(subject.isNotBlank()) { "Execution topology requirement subject must not be blank." }
    }
}

enum class ExecutionTopologySupportStatus {
    SUPPORTED,
    PARTIAL,
    UNSUPPORTED,
    UNKNOWN
}

data class ExecutionTopologySupportDeclaration(
    val kind: ExecutionTopologyKind,
    val status: ExecutionTopologySupportStatus,
    val evidenceReference: String,
    val detail: String? = null
) {
    init {
        require(evidenceReference.isNotBlank()) { "Execution topology evidence reference must not be blank." }
    }
}

data class ExecutionTopologyProfile(
    val target: String,
    val declarations: List<ExecutionTopologySupportDeclaration> = emptyList()
) {
    init {
        require(target.isNotBlank()) { "Execution topology profile target must not be blank." }
    }

    companion object {
        fun fullySupported(target: String, evidenceReference: String): ExecutionTopologyProfile =
            ExecutionTopologyProfile(
                target = target,
                declarations = ExecutionTopologyKind.entries.map { kind ->
                    ExecutionTopologySupportDeclaration(
                        kind = kind,
                        status = ExecutionTopologySupportStatus.SUPPORTED,
                        evidenceReference = evidenceReference
                    )
                }
            )
    }
}

data class ExecutionTopologyProfileIssue(
    val code: String,
    val kind: ExecutionTopologyKind? = null,
    val message: String
)

object ExecutionTopologyProfileAuthority {
    fun validate(profile: ExecutionTopologyProfile, requireComplete: Boolean = true): List<ExecutionTopologyProfileIssue> {
        val issues = mutableListOf<ExecutionTopologyProfileIssue>()
        profile.declarations.groupBy(ExecutionTopologySupportDeclaration::kind).forEach { (kind, declarations) ->
            if (declarations.size > 1) {
                val statuses = declarations.map(ExecutionTopologySupportDeclaration::status).distinct()
                issues += ExecutionTopologyProfileIssue(
                    code = if (statuses.size > 1) "TOPOLOGY_PROFILE_CONTRADICTORY" else "TOPOLOGY_PROFILE_DUPLICATE",
                    kind = kind,
                    message = if (statuses.size > 1) {
                        "Topology profile '${profile.target}' declares contradictory support for '${kind.registryKey}': ${statuses.joinToString()}."
                    } else {
                        "Topology profile '${profile.target}' declares '${kind.registryKey}' more than once."
                    }
                )
            }
        }
        if (requireComplete) {
            val declared = profile.declarations.map(ExecutionTopologySupportDeclaration::kind).toSet()
            (ExecutionTopologyKind.entries.toSet() - declared).forEach { kind ->
                issues += ExecutionTopologyProfileIssue(
                    code = "TOPOLOGY_PROFILE_INCOMPLETE",
                    kind = kind,
                    message = "Topology profile '${profile.target}' does not declare '${kind.registryKey}'."
                )
            }
        }
        return issues.sortedWith(compareBy({ it.kind?.ordinal ?: -1 }, ExecutionTopologyProfileIssue::code))
    }

    fun requireValid(profile: ExecutionTopologyProfile, requireComplete: Boolean = true) {
        val issues = validate(profile, requireComplete)
        require(issues.isEmpty()) { issues.joinToString("; ") { it.message } }
    }
}

enum class ExecutionTopologyEvidenceStatus {
    SATISFIED,
    DEGRADED,
    UNSATISFIED,
    UNKNOWN,
    CONTRADICTORY
}

enum class ExecutionTopologyEvidenceSource {
    TARGET_REGISTRY,
    MISSING,
    PROFILE_CONTRADICTION
}

data class ExecutionTopologyEvidence(
    val requirementId: String,
    val kind: ExecutionTopologyKind,
    val status: ExecutionTopologyEvidenceStatus,
    val source: ExecutionTopologyEvidenceSource,
    val target: String,
    val evidenceReference: String? = null,
    val detail: String? = null
)

enum class ExecutionTopologyDecisionStatus {
    MATCHED,
    DEGRADED,
    BLOCKED
}

data class ExecutionTopologyDecision(
    val status: ExecutionTopologyDecisionStatus,
    val blockingRequirementIds: List<String> = emptyList(),
    val degradedRequirementIds: List<String> = emptyList()
)

data class ExecutionTopologyAssessment(
    val target: String,
    val requirements: List<ExecutionTopologyRequirement> = emptyList(),
    val evidence: List<ExecutionTopologyEvidence> = emptyList(),
    val decision: ExecutionTopologyDecision = ExecutionTopologyDecision(ExecutionTopologyDecisionStatus.BLOCKED)
)

object ExecutionTopologyMatchingAuthority {
    fun assess(
        requirements: List<ExecutionTopologyRequirement>,
        profile: ExecutionTopologyProfile?
    ): ExecutionTopologyAssessment {
        require(requirements.distinctBy(ExecutionTopologyRequirement::id).size == requirements.size) {
            "Execution topology requirements must use unique ids."
        }
        val target = profile?.target.orEmpty()
        if (requirements.isEmpty()) {
            return ExecutionTopologyAssessment(
                target = target,
                requirements = emptyList(),
                evidence = emptyList(),
                decision = ExecutionTopologyDecision(ExecutionTopologyDecisionStatus.MATCHED)
            )
        }
        if (profile == null) {
            val evidence = requirements.map { requirement ->
                ExecutionTopologyEvidence(
                    requirementId = requirement.id,
                    kind = requirement.kind,
                    status = ExecutionTopologyEvidenceStatus.UNKNOWN,
                    source = ExecutionTopologyEvidenceSource.MISSING,
                    target = target,
                    detail = "Target does not provide an execution topology profile."
                )
            }
            return assessment(target, requirements, evidence)
        }

        val profileIssues = ExecutionTopologyProfileAuthority.validate(profile)
        val contradictoryKinds = profileIssues
            .filter { it.code == "TOPOLOGY_PROFILE_CONTRADICTORY" }
            .mapNotNull(ExecutionTopologyProfileIssue::kind)
            .toSet()
        val declarations = profile.declarations.groupBy(ExecutionTopologySupportDeclaration::kind)
        val evidence = requirements.map { requirement ->
            val candidates = declarations[requirement.kind].orEmpty()
            when {
                requirement.kind in contradictoryKinds -> ExecutionTopologyEvidence(
                    requirementId = requirement.id,
                    kind = requirement.kind,
                    status = ExecutionTopologyEvidenceStatus.CONTRADICTORY,
                    source = ExecutionTopologyEvidenceSource.PROFILE_CONTRADICTION,
                    target = profile.target,
                    detail = "Target topology profile contains contradictory evidence for '${requirement.kind.registryKey}'."
                )
                candidates.size != 1 -> ExecutionTopologyEvidence(
                    requirementId = requirement.id,
                    kind = requirement.kind,
                    status = ExecutionTopologyEvidenceStatus.UNKNOWN,
                    source = ExecutionTopologyEvidenceSource.MISSING,
                    target = profile.target,
                    detail = "Target topology profile has no unique evidence for '${requirement.kind.registryKey}'."
                )
                else -> candidates.single().toEvidence(requirement, profile.target)
            }
        }
        return assessment(profile.target, requirements, evidence)
    }

    private fun ExecutionTopologySupportDeclaration.toEvidence(
        requirement: ExecutionTopologyRequirement,
        target: String
    ): ExecutionTopologyEvidence = ExecutionTopologyEvidence(
        requirementId = requirement.id,
        kind = requirement.kind,
        status = when (status) {
            ExecutionTopologySupportStatus.SUPPORTED -> ExecutionTopologyEvidenceStatus.SATISFIED
            ExecutionTopologySupportStatus.PARTIAL -> ExecutionTopologyEvidenceStatus.DEGRADED
            ExecutionTopologySupportStatus.UNSUPPORTED -> ExecutionTopologyEvidenceStatus.UNSATISFIED
            ExecutionTopologySupportStatus.UNKNOWN -> ExecutionTopologyEvidenceStatus.UNKNOWN
        },
        source = ExecutionTopologyEvidenceSource.TARGET_REGISTRY,
        target = target,
        evidenceReference = evidenceReference,
        detail = detail
    )

    private fun assessment(
        target: String,
        requirements: List<ExecutionTopologyRequirement>,
        evidence: List<ExecutionTopologyEvidence>
    ): ExecutionTopologyAssessment {
        val blocking = evidence.filter { it.status in setOf(
            ExecutionTopologyEvidenceStatus.UNSATISFIED,
            ExecutionTopologyEvidenceStatus.UNKNOWN,
            ExecutionTopologyEvidenceStatus.CONTRADICTORY
        ) }.map(ExecutionTopologyEvidence::requirementId).sorted()
        val degraded = evidence.filter { it.status == ExecutionTopologyEvidenceStatus.DEGRADED }
            .map(ExecutionTopologyEvidence::requirementId).sorted()
        val decision = when {
            blocking.isNotEmpty() -> ExecutionTopologyDecision(
                status = ExecutionTopologyDecisionStatus.BLOCKED,
                blockingRequirementIds = blocking,
                degradedRequirementIds = degraded
            )
            degraded.isNotEmpty() -> ExecutionTopologyDecision(
                status = ExecutionTopologyDecisionStatus.DEGRADED,
                degradedRequirementIds = degraded
            )
            else -> ExecutionTopologyDecision(ExecutionTopologyDecisionStatus.MATCHED)
        }
        return ExecutionTopologyAssessment(target, requirements, evidence, decision)
    }
}
