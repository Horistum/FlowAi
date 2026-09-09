package org.flowlang.targets

/**
 * Honest target registry contract.
 *
 * The registry records what level of support a target actually has. A target
 * name is not support, a renderer class is not support, and a happy-path sample
 * is not production readiness. The model keeps those states separate so later
 * projection work cannot silently upgrade a target by optimism.
 */
enum class TargetRegistryStatus {
    DECLARED_ONLY,
    EXPERIMENTAL,
    IMPLEMENTED,
    TESTED,
    PRODUCTION_SUPPORTED,
    DEPRECATED,
    BLOCKED
}

enum class TargetRegistryEvidenceKind {
    NOTES_DECLARATION,
    IMPLEMENTATION,
    PROJECTION_PLAN,
    TEST,
    CONFORMANCE,
    REVIEW_DECISION,
    POLICY_DECISION
}

data class TargetRegistryEvidence(
    val reference: String,
    val kind: TargetRegistryEvidenceKind,
    val description: String = ""
)

data class TargetRegistryEntry(
    val targetId: String,
    val displayName: String,
    val status: TargetRegistryStatus,
    val declaredCapabilities: Set<String> = emptySet(),
    val evidence: List<TargetRegistryEvidence> = emptyList(),
    val reason: String = ""
) {
    fun evidenceKinds(): Set<TargetRegistryEvidenceKind> = evidence.map { it.kind }.toSet()
}

data class TargetRegistrySnapshot(
    val registryId: String,
    val entries: List<TargetRegistryEntry>
) {
    fun entriesById(): Map<String, TargetRegistryEntry> = entries.associateBy { it.targetId }
}

enum class TargetRegistryHonestyStatus {
    PASS,
    FAIL
}

data class TargetRegistryHonestyIssue(
    val code: String,
    val registryId: String,
    val message: String
)

data class TargetRegistryHonestyReport(
    val status: TargetRegistryHonestyStatus,
    val registryId: String,
    val entries: Int,
    val issues: List<TargetRegistryHonestyIssue>
) {
    val valid: Boolean = status == TargetRegistryHonestyStatus.PASS
}

class TargetRegistryHonestyValidator {
    fun validate(snapshot: TargetRegistrySnapshot): TargetRegistryHonestyReport {
        val issues = mutableListOf<TargetRegistryHonestyIssue>()
        val seenIds = mutableSetOf<String>()

        if (!REGISTRY_ID.matches(snapshot.registryId)) {
            issues += issue(snapshot, "target.registry.id.invalid", "Target registry id must be lowercase dot-separated identifier text.")
        }
        if (snapshot.entries.isEmpty()) {
            issues += issue(snapshot, "target.registry.empty", "Target registry must contain at least one entry.")
        }

        snapshot.entries.forEach { entry ->
            issues += validateEntry(snapshot, entry, seenIds)
        }

        return TargetRegistryHonestyReport(
            status = if (issues.isEmpty()) TargetRegistryHonestyStatus.PASS else TargetRegistryHonestyStatus.FAIL,
            registryId = snapshot.registryId,
            entries = snapshot.entries.size,
            issues = issues
        )
    }

    private fun validateEntry(
        snapshot: TargetRegistrySnapshot,
        entry: TargetRegistryEntry,
        seenIds: MutableSet<String>
    ): List<TargetRegistryHonestyIssue> {
        val issues = mutableListOf<TargetRegistryHonestyIssue>()

        if (!TARGET_ID.matches(entry.targetId)) {
            issues += issue(snapshot, "target.entry.id.invalid", "Target id '${entry.targetId}' is not valid.")
        }
        if (!seenIds.add(entry.targetId)) {
            issues += issue(snapshot, "target.entry.id.duplicate", "Target id '${entry.targetId}' is duplicated.")
        }
        if (entry.displayName.isBlank()) {
            issues += issue(snapshot, "target.entry.display-name.missing", "Target '${entry.targetId}' must have a display name.")
        }
        if (entry.reason.isBlank()) {
            issues += issue(snapshot, "target.entry.reason.missing", "Target '${entry.targetId}' must explain its support status.")
        }
        if (entry.evidence.isEmpty()) {
            issues += issue(snapshot, "target.entry.evidence.missing", "Target '${entry.targetId}' must include evidence for its support status.")
        }
        entry.evidence.forEach { evidence ->
            if (evidence.reference.isBlank()) {
                issues += issue(snapshot, "target.evidence.reference.missing", "Target '${entry.targetId}' has evidence without a reference.")
            }
        }

        issues += validateStatusHonesty(snapshot, entry)
        issues += validateNoImplicitShellTarget(snapshot, entry)
        issues += validateNoForbiddenEvidenceText(snapshot, entry)
        return issues
    }

    private fun validateStatusHonesty(snapshot: TargetRegistrySnapshot, entry: TargetRegistryEntry): List<TargetRegistryHonestyIssue> {
        val issues = mutableListOf<TargetRegistryHonestyIssue>()
        val kinds = entry.evidenceKinds()

        when (entry.status) {
            TargetRegistryStatus.DECLARED_ONLY -> {
                if (TargetRegistryEvidenceKind.NOTES_DECLARATION !in kinds) {
                    issues += issue(snapshot, "target.declared-only.notes.missing", "Declared-only target '${entry.targetId}' must cite notes declaration evidence.")
                }
                val prematureEvidence = kinds.intersect(EXECUTION_CLAIM_EVIDENCE)
                if (prematureEvidence.isNotEmpty()) {
                    issues += issue(snapshot, "target.declared-only.premature-evidence", "Declared-only target '${entry.targetId}' must not cite implementation, test or conformance evidence.")
                }
            }
            TargetRegistryStatus.EXPERIMENTAL -> {
                if (TargetRegistryEvidenceKind.NOTES_DECLARATION !in kinds && TargetRegistryEvidenceKind.REVIEW_DECISION !in kinds) {
                    issues += issue(snapshot, "target.experimental.evidence.missing", "Experimental target '${entry.targetId}' must cite notes or review evidence.")
                }
            }
            TargetRegistryStatus.IMPLEMENTED -> {
                if (TargetRegistryEvidenceKind.IMPLEMENTATION !in kinds) {
                    issues += issue(snapshot, "target.implemented.evidence.missing", "Implemented target '${entry.targetId}' must cite implementation evidence.")
                }
            }
            TargetRegistryStatus.TESTED -> {
                if (!kinds.containsAll(TESTED_REQUIRED_EVIDENCE)) {
                    issues += issue(snapshot, "target.tested.evidence.missing", "Tested target '${entry.targetId}' must cite implementation, projection and test evidence.")
                }
            }
            TargetRegistryStatus.PRODUCTION_SUPPORTED -> {
                if (!kinds.containsAll(PRODUCTION_REQUIRED_EVIDENCE)) {
                    issues += issue(snapshot, "target.production.evidence.missing", "Production-supported target '${entry.targetId}' must cite implementation, projection, test and conformance evidence.")
                }
                if (entry.declaredCapabilities.isEmpty()) {
                    issues += issue(snapshot, "target.production.capabilities.missing", "Production-supported target '${entry.targetId}' must declare supported capabilities.")
                }
            }
            TargetRegistryStatus.DEPRECATED -> {
                if (TargetRegistryEvidenceKind.REVIEW_DECISION !in kinds) {
                    issues += issue(snapshot, "target.deprecated.review.missing", "Deprecated target '${entry.targetId}' must cite review decision evidence.")
                }
            }
            TargetRegistryStatus.BLOCKED -> {
                if (TargetRegistryEvidenceKind.POLICY_DECISION !in kinds && TargetRegistryEvidenceKind.REVIEW_DECISION !in kinds) {
                    issues += issue(snapshot, "target.blocked.evidence.missing", "Blocked target '${entry.targetId}' must cite policy or review evidence.")
                }
            }
        }
        return issues
    }

    private fun validateNoImplicitShellTarget(snapshot: TargetRegistrySnapshot, entry: TargetRegistryEntry): List<TargetRegistryHonestyIssue> {
        val issues = mutableListOf<TargetRegistryHonestyIssue>()
        val lowered = entry.targetId.lowercase()
        if (lowered in BLOCKED_TARGET_IDS && entry.status != TargetRegistryStatus.BLOCKED) {
            issues += issue(snapshot, "target.shell.not-blocked", "Shell-like target '${entry.targetId}' must be registered only as blocked.")
        }
        return issues
    }

    private fun validateNoForbiddenEvidenceText(snapshot: TargetRegistrySnapshot, entry: TargetRegistryEntry): List<TargetRegistryHonestyIssue> {
        val issues = mutableListOf<TargetRegistryHonestyIssue>()
        val text = listOf(entry.targetId, entry.displayName, entry.reason) +
            entry.declaredCapabilities +
            entry.evidence.flatMap { listOf(it.reference, it.description) }
        text.forEach { value ->
            val lowered = value.lowercase()
            FORBIDDEN_READY_CLAIMS.filter { lowered.contains(it) }.forEach { term ->
                issues += issue(snapshot, "target.registry.forbidden-ready-claim", "Target '${entry.targetId}' must not use '$term' as support evidence.")
            }
        }
        return issues
    }

    private fun issue(snapshot: TargetRegistrySnapshot, code: String, message: String) =
        TargetRegistryHonestyIssue(code = code, registryId = snapshot.registryId, message = message)

    companion object {
        private val REGISTRY_ID = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)*")
        private val TARGET_ID = Regex("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*")
        private val EXECUTION_CLAIM_EVIDENCE = setOf(
            TargetRegistryEvidenceKind.IMPLEMENTATION,
            TargetRegistryEvidenceKind.PROJECTION_PLAN,
            TargetRegistryEvidenceKind.TEST,
            TargetRegistryEvidenceKind.CONFORMANCE
        )
        private val TESTED_REQUIRED_EVIDENCE = setOf(
            TargetRegistryEvidenceKind.IMPLEMENTATION,
            TargetRegistryEvidenceKind.PROJECTION_PLAN,
            TargetRegistryEvidenceKind.TEST
        )
        private val PRODUCTION_REQUIRED_EVIDENCE = setOf(
            TargetRegistryEvidenceKind.IMPLEMENTATION,
            TargetRegistryEvidenceKind.PROJECTION_PLAN,
            TargetRegistryEvidenceKind.TEST,
            TargetRegistryEvidenceKind.CONFORMANCE
        )
        private val BLOCKED_TARGET_IDS = setOf("shell", "bash", "powershell", "cmd")
        private val FORBIDDEN_READY_CLAIMS = listOf(
            "supported by default",
            "assume supported",
            "implicit support",
            "fallback shell",
            "generic shell",
            "command fallback"
        )
    }
}
