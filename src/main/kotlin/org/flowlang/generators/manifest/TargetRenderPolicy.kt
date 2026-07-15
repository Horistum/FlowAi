package org.flowlang.generators.manifest

enum class TargetRenderMode {
    EXECUTABLE,
    REVIEW_ONLY,
    FAIL_FAST
}

data class TargetRenderFinding(
    val nodeId: String,
    val status: String,
    val reason: String
)

data class TargetRenderReadiness(
    val target: String,
    val mode: TargetRenderMode,
    val findings: List<TargetRenderFinding>
) {
    val executable: Boolean = mode == TargetRenderMode.EXECUTABLE
}

class TargetRenderBlockedException(val readiness: TargetRenderReadiness) : IllegalStateException(
    "Target '${readiness.target}' rendering is blocked: " +
        readiness.findings.joinToString("; ") { "${it.nodeId} ${it.status}: ${it.reason}" }
)

object TargetRenderPolicy {
    private val completedStatuses = setOf(TargetMaterializationStatus.NATIVE)
    private val blockedStatuses = setOf(TargetMaterializationStatus.BLOCKED, TargetMaterializationStatus.UNSUPPORTED)
    private val payloadKindPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._+:/-]*$")

    fun evaluate(manifest: TargetManifest): TargetRenderReadiness {
        val steps = manifest.jobs.flatMap { job -> job.steps.flatMap { it.flattenForReadiness() } }
        val capabilityFinding = if (manifest.compatibility.capabilityStatus != org.flowlang.capabilities.SupportLevel.SUPPORTED) {
            listOf(TargetRenderFinding(
                "manifest",
                "CAPABILITY_${manifest.compatibility.capabilityStatus.name}",
                "Target capability compatibility is '${manifest.compatibility.capabilityStatus}' and cannot produce executable syntax."
            ))
        } else emptyList()
        val blocking = steps.filter { it.materialization.status in blockedStatuses }.map { step ->
            TargetRenderFinding(step.id, step.materialization.status.name, step.materialization.reason)
        }.distinct()
        if (blocking.isNotEmpty()) return TargetRenderReadiness(manifest.target, TargetRenderMode.FAIL_FAST, blocking)

        val unresolved = capabilityFinding + steps.filter { it.isMaterializationLeaf() }.mapNotNull { step ->
            when {
                step.materialization.status !in completedStatuses -> TargetRenderFinding(
                    step.id,
                    step.materialization.status.name,
                    step.materialization.reason
                )
                step.rendererPayload == null -> TargetRenderFinding(
                    step.id,
                    "TARGET_PAYLOAD_MISSING",
                    "Native materialization evidence exists, but no structured renderer payload is declared for '${manifest.target}'."
                )
                step.rendererPayload.target != manifest.target -> TargetRenderFinding(
                    step.id,
                    "TARGET_PAYLOAD_MISMATCH",
                    "Renderer payload is bound to '${step.rendererPayload.target}', not '${manifest.target}'."
                )
                step.rendererPayload.kind.isBlank() -> TargetRenderFinding(
                    step.id,
                    "TARGET_PAYLOAD_KIND_MISSING",
                    "Renderer readiness requires an opaque projection payload kind."
                )
                !payloadKindPattern.matches(step.rendererPayload.kind) -> TargetRenderFinding(
                    step.id,
                    "TARGET_PAYLOAD_KIND_INVALID",
                    "Renderer payload kind '${step.rendererPayload.kind}' is not a valid opaque projection identifier."
                )
                step.rendererPayload.reference.isBlank() -> TargetRenderFinding(
                    step.id,
                    "TARGET_PAYLOAD_REFERENCE_MISSING",
                    "Renderer readiness requires a concrete target payload reference."
                )
                step.rendererPayload.evidenceReference.isBlank() -> TargetRenderFinding(
                    step.id,
                    "TARGET_PAYLOAD_EVIDENCE_MISSING",
                    "Renderer payload must cite target registry or notes evidence."
                )
                else -> null
            }
        }.distinct()

        return if (unresolved.isEmpty()) {
            TargetRenderReadiness(manifest.target, TargetRenderMode.EXECUTABLE, emptyList())
        } else {
            TargetRenderReadiness(manifest.target, TargetRenderMode.REVIEW_ONLY, unresolved)
        }
    }

    fun requireSafe(manifest: TargetManifest): TargetRenderReadiness {
        val readiness = evaluate(manifest)
        if (readiness.mode == TargetRenderMode.FAIL_FAST) throw TargetRenderBlockedException(readiness)
        return readiness
    }

    fun requireExecutable(manifest: TargetManifest): TargetRenderReadiness {
        val readiness = evaluate(manifest)
        if (!readiness.executable) throw TargetRenderBlockedException(readiness)
        return readiness
    }

    private fun TargetStep.isMaterializationLeaf(): Boolean = children.isEmpty() && type !in setOf(
        "try-body", "error-handler", "parallel", "parallel-branch", "loop", "match", "retry", "condition"
    )

    private fun TargetStep.flattenForReadiness(): List<TargetStep> = listOf(this) + children.flatMap { it.flattenForReadiness() }
}
