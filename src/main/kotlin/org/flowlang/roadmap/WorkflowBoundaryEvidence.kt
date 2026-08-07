package org.flowlang.roadmap

/**
 * Canonical representation of one externally observed Flow CI validation boundary.
 *
 * Lifecycle authorities deliberately keep ownership of their item-specific state machines.
 * Only the cross-stream structural definition of trustworthy CI evidence lives here, so a
 * historical work item cannot silently invent a weaker meaning of "passed validation".
 */
data class WorkflowBoundaryEvidence(
    val status: String = "",
    val workflow: String = "",
    val runNumber: Int? = null,
    val runId: Long? = null,
    val exactHead: String = "",
    val mergeCandidate: String = "",
    val unknownFields: List<String> = emptyList(),
    val present: Boolean = false
) {
    val structurallyValid: Boolean
        get() = present &&
            unknownFields.isEmpty() &&
            status == "passed" &&
            workflow == FLOW_CI &&
            runNumber?.let { it > 0 } == true &&
            runId?.let { it > 0 } == true &&
            SHA_PATTERN.matches(exactHead) &&
            SHA_PATTERN.matches(mergeCandidate) &&
            exactHead != mergeCandidate

    fun sameBoundary(other: WorkflowBoundaryEvidence): Boolean =
        structurallyValid &&
            other.structurallyValid &&
            runNumber == other.runNumber &&
            runId == other.runId &&
            exactHead == other.exactHead &&
            mergeCandidate == other.mergeCandidate

    fun distinctFrom(previous: WorkflowBoundaryEvidence): Boolean =
        previous.structurallyValid &&
            structurallyValid &&
            runNumber != previous.runNumber &&
            runId != previous.runId &&
            exactHead != previous.exactHead &&
            mergeCandidate != previous.mergeCandidate

    fun follows(previous: WorkflowBoundaryEvidence): Boolean =
        distinctFrom(previous) &&
            runNumber?.let { current -> previous.runNumber?.let { prior -> current > prior } } == true

    fun summary(): String = if (!present) {
        "absent"
    } else {
        "status=$status,workflow=$workflow,runNumber=${runNumber ?: "invalid"}," +
            "runId=${runId ?: "invalid"},exactHead=$exactHead,mergeCandidate=$mergeCandidate," +
            "unknown=${unknownFields.joinToString()}"
    }

    companion object {
        const val FLOW_CI = "Flow CI"
        val FIELDS = setOf("status", "workflow", "runNumber", "runId", "exactHead", "mergeCandidate")
        val ABSENT = WorkflowBoundaryEvidence()
        private val SHA_PATTERN = Regex("[0-9a-f]{40}")

        fun fromMap(raw: Map<String, Any?>): WorkflowBoundaryEvidence {
            if (raw.isEmpty()) return ABSENT
            return WorkflowBoundaryEvidence(
                status = raw["status"]?.toString().orEmpty(),
                workflow = raw["workflow"]?.toString().orEmpty(),
                runNumber = raw["runNumber"]?.toString()?.toIntOrNull(),
                runId = raw["runId"]?.toString()?.toLongOrNull(),
                exactHead = raw["exactHead"]?.toString().orEmpty(),
                mergeCandidate = raw["mergeCandidate"]?.toString().orEmpty(),
                unknownFields = (raw.keys - FIELDS).sorted(),
                present = true
            )
        }
    }
}
