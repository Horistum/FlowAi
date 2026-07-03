package org.flowlang.generators.manifest

/**
 * v0.9.3 capability degradation semantics.
 *
 * This analyzer explains what a target projection preserves, approximates, or blocks after capability
 * negotiation and manifest generation. It operates at the TargetManifest boundary, so it does not
 * re-plan Flow and does not introduce target-specific public syntax.
 */
object TargetCapabilityDegradationAnalyzer {
    fun analyze(manifest: TargetManifest, strictMode: Boolean = false): TargetCapabilityDegradationReport {
        val entries = mutableListOf<TargetCapabilityDegradationEntry>()

        manifest.mappingNotes.forEach { note -> entries += note.toDegradationEntry(manifest.target, "manifest") }
        manifest.metadata["supportLevel"]?.takeIf { it == "partial" }?.let {
            entries += TargetCapabilityDegradationEntry(
                target = manifest.target,
                nodeId = "manifest",
                feature = "target.partial",
                status = TargetCapabilityDegradationStatus.DEGRADED,
                preserved = "The manifest is still renderable as a target artifact.",
                approximated = "Some Flow semantics require review or external target adaptation before production use.",
                blocked = "None in non-strict mode.",
                message = "Target '${manifest.target}' is marked as a partial projection."
            )
        }

        manifest.jobs.forEach { job ->
            job.mappingNotes.forEach { note -> entries += note.toDegradationEntry(manifest.target, "job:${job.id}") }
            job.metadata["supportLevel"]?.takeIf { it == "partial" }?.let {
                entries += TargetCapabilityDegradationEntry(
                    target = manifest.target,
                    nodeId = job.id,
                    feature = "job.partial",
                    status = TargetCapabilityDegradationStatus.DEGRADED,
                    preserved = "The job remains present in the projected manifest.",
                    approximated = "The job carries semantics that are only partially represented by the target.",
                    blocked = "None in non-strict mode.",
                    message = "Job '${job.id}' is marked as a partial projection."
                )
            }
            job.steps.forEach { collectStepEntries(it, manifest.target, entries) }
        }

        val status = when {
            entries.any { it.status == TargetCapabilityDegradationStatus.BLOCKED } -> TargetCapabilityDegradationStatus.BLOCKED
            entries.any { it.status == TargetCapabilityDegradationStatus.DEGRADED } -> TargetCapabilityDegradationStatus.DEGRADED
            else -> TargetCapabilityDegradationStatus.SUPPORTED
        }
        val valid = if (strictMode) status == TargetCapabilityDegradationStatus.SUPPORTED else status != TargetCapabilityDegradationStatus.BLOCKED

        return TargetCapabilityDegradationReport(
            target = manifest.target,
            status = status,
            strictMode = strictMode,
            valid = valid,
            entries = entries,
            metadata = mapOf(
                "target" to manifest.target,
                "strictMode" to strictMode.toString(),
                "entryCount" to entries.size.toString(),
                "blockedCount" to entries.count { it.status == TargetCapabilityDegradationStatus.BLOCKED }.toString(),
                "degradedCount" to entries.count { it.status == TargetCapabilityDegradationStatus.DEGRADED }.toString()
            )
        )
    }

    fun requireAcceptable(manifest: TargetManifest, strictMode: Boolean = false) {
        val report = analyze(manifest, strictMode)
        if (!report.valid) {
            val message = report.entries.joinToString("; ") { entry ->
                "${entry.status} ${entry.feature} at ${entry.nodeId}: ${entry.message}"
            }
            val mode = if (strictMode) "strict" else "standard"
            throw IllegalStateException("Target capability degradation report is not acceptable in $mode mode for '${manifest.target}': $message")
        }
    }

    private fun collectStepEntries(
        step: TargetStep,
        target: String,
        entries: MutableList<TargetCapabilityDegradationEntry>
    ) {
        step.mappingNotes.forEach { note -> entries += note.toDegradationEntry(target, "step:${step.id}") }
        step.metadata["supportLevel"]?.takeIf { it == "partial" }?.let {
            val feature = when (step.type) {
                "loop" -> "loop.partial"
                "match" -> "match.partial"
                else -> "step.partial"
            }
            entries += TargetCapabilityDegradationEntry(
                target = target,
                nodeId = step.id,
                feature = feature,
                status = TargetCapabilityDegradationStatus.DEGRADED,
                preserved = "The step body remains present in the projected manifest.",
                approximated = "The target projection does not fully preserve native Flow ${step.type} semantics.",
                blocked = "None in non-strict mode.",
                message = "Step '${step.id}' is marked as a partial ${step.type} projection."
            )
        }
        step.children.forEach { collectStepEntries(it, target, entries) }
    }

    private fun TargetMappingNote.toDegradationEntry(targetName: String, fallbackNodeId: String): TargetCapabilityDegradationEntry {
        val status = when (level) {
            "error" -> TargetCapabilityDegradationStatus.BLOCKED
            "warning" -> TargetCapabilityDegradationStatus.DEGRADED
            else -> TargetCapabilityDegradationStatus.SUPPORTED
        }
        return TargetCapabilityDegradationEntry(
            target = target.ifBlank { targetName },
            nodeId = nodeId.ifBlank { fallbackNodeId },
            feature = feature,
            status = status,
            preserved = when (status) {
                TargetCapabilityDegradationStatus.SUPPORTED -> "The manifest records this mapping as preserved or informational."
                TargetCapabilityDegradationStatus.DEGRADED -> "The projected artifact remains reviewable and may still be renderable."
                TargetCapabilityDegradationStatus.BLOCKED -> "No safe target-side preservation is guaranteed for this feature."
            },
            approximated = when (status) {
                TargetCapabilityDegradationStatus.SUPPORTED -> "No approximation is required by this note."
                TargetCapabilityDegradationStatus.DEGRADED -> "The mapping note describes target behavior that is approximate, partial, or review-required."
                TargetCapabilityDegradationStatus.BLOCKED -> "The feature must be changed, explicitly adapted, or blocked before production use."
            },
            blocked = when (status) {
                TargetCapabilityDegradationStatus.SUPPORTED -> "Nothing is blocked by this note."
                TargetCapabilityDegradationStatus.DEGRADED -> "Strict mode blocks this degraded feature."
                TargetCapabilityDegradationStatus.BLOCKED -> "Standard and strict modes block this feature."
            },
            message = message
        )
    }
}

enum class TargetCapabilityDegradationStatus {
    SUPPORTED,
    DEGRADED,
    BLOCKED
}

data class TargetCapabilityDegradationReport(
    val target: String,
    val status: TargetCapabilityDegradationStatus,
    val strictMode: Boolean,
    val valid: Boolean,
    val entries: List<TargetCapabilityDegradationEntry>,
    val metadata: Map<String, String> = emptyMap()
) {
    val degradedEntries: List<TargetCapabilityDegradationEntry> = entries.filter { it.status == TargetCapabilityDegradationStatus.DEGRADED }
    val blockedEntries: List<TargetCapabilityDegradationEntry> = entries.filter { it.status == TargetCapabilityDegradationStatus.BLOCKED }
}

data class TargetCapabilityDegradationEntry(
    val target: String,
    val nodeId: String,
    val feature: String,
    val status: TargetCapabilityDegradationStatus,
    val preserved: String,
    val approximated: String,
    val blocked: String,
    val message: String
)
