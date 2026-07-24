package org.flowlang.generators.manifest

import org.flowlang.capabilities.SupportLevel

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
    private val blockedStatuses = setOf(
        TargetMaterializationStatus.BLOCKED,
        TargetMaterializationStatus.UNSUPPORTED
    )
    private val payloadKindPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._+:/-]*$")

    fun evaluate(manifest: TargetManifest): TargetRenderReadiness {
        val steps = manifest.jobs.flatMap { job -> job.steps.flatMap { it.flattenForReadiness() } }
        val blocking = steps
            .filter { it.materialization.status in blockedStatuses }
            .map { step ->
                TargetRenderFinding(step.id, step.materialization.status.name, step.materialization.reason)
            }
            .distinct()
        if (blocking.isNotEmpty()) {
            return TargetRenderReadiness(manifest.target, TargetRenderMode.FAIL_FAST, blocking)
        }

        val concreteFindings = steps
            .filter { it.isMaterializationLeaf() }
            .mapNotNull { step -> readinessFinding(step, manifest.target) }
            .distinct()
        val compatibilityFindings = buildList {
            if (manifest.compatibility.hasErrors || manifest.compatibility.status == SupportLevel.UNSUPPORTED) {
                add(TargetRenderFinding(
                    "manifest",
                    "COMPATIBILITY_UNSUPPORTED",
                    "Target compatibility contains blocking evidence and cannot produce executable syntax."
                ))
            } else if (manifest.compatibility.capabilityStatus != SupportLevel.SUPPORTED) {
                add(TargetRenderFinding(
                    "manifest",
                    "CAPABILITY_${manifest.compatibility.capabilityStatus.name}",
                    "Target capability compatibility is '${manifest.compatibility.capabilityStatus}' and cannot produce executable syntax."
                ))
            } else if (
                manifest.compatibility.status != SupportLevel.SUPPORTED &&
                concreteFindings.isEmpty()
            ) {
                add(TargetRenderFinding(
                    "manifest",
                    "COMPATIBILITY_${manifest.compatibility.status.name}",
                    "Effective target compatibility is '${manifest.compatibility.status}' and requires review."
                ))
            }
        }
        val unresolved = (compatibilityFindings + concreteFindings).distinct()

        return if (unresolved.isEmpty()) {
            TargetRenderReadiness(manifest.target, TargetRenderMode.EXECUTABLE, emptyList())
        } else {
            TargetRenderReadiness(manifest.target, TargetRenderMode.REVIEW_ONLY, unresolved)
        }
    }

    private fun readinessFinding(step: TargetStep, manifestTarget: String): TargetRenderFinding? {
        if (step.materialization.status !in completedStatuses) {
            return TargetRenderFinding(
                step.id,
                step.materialization.status.name,
                step.materialization.reason
            )
        }
        val payload = step.rendererPayload ?: return TargetRenderFinding(
            step.id,
            "TARGET_PAYLOAD_MISSING",
            "Native materialization evidence exists, but no structured renderer payload is declared for '$manifestTarget'."
        )
        if (payload.target != manifestTarget) {
            return TargetRenderFinding(
                step.id,
                "TARGET_PAYLOAD_MISMATCH",
                "Renderer payload is bound to '${payload.target}', not '$manifestTarget'."
            )
        }
        if (payload.kind.isBlank()) {
            return TargetRenderFinding(
                step.id,
                "TARGET_PAYLOAD_KIND_MISSING",
                "Renderer readiness requires an opaque projection payload kind."
            )
        }
        if (!payloadKindPattern.matches(payload.kind)) {
            return TargetRenderFinding(
                step.id,
                "TARGET_PAYLOAD_KIND_INVALID",
                "Renderer payload kind '${payload.kind}' is not a valid opaque projection identifier."
            )
        }
        if (payload.reference.isBlank()) {
            return TargetRenderFinding(
                step.id,
                "TARGET_PAYLOAD_REFERENCE_MISSING",
                "Renderer readiness requires a concrete target payload reference."
            )
        }
        if (payload.evidenceReference.isBlank()) {
            return TargetRenderFinding(
                step.id,
                "TARGET_PAYLOAD_EVIDENCE_MISSING",
                "Renderer payload must cite target registry or notes evidence."
            )
        }
        val bindingIssue = TargetManifestBindingValidation.issues(payload).firstOrNull()
        if (bindingIssue != null) {
            return TargetRenderFinding(
                step.id,
                "TARGET_BINDING_INVALID",
                "Binding '${bindingIssue.name}' is invalid: ${bindingIssue.reason}"
            )
        }
        val unresolvedBinding = TargetManifestBindingValidation.unresolved(payload).firstOrNull()
        if (unresolvedBinding != null) {
            return TargetRenderFinding(
                step.id,
                "TARGET_BINDING_UNRESOLVED",
                "Binding '${unresolvedBinding.name}' is unresolved: ${unresolvedBinding.reason}"
            )
        }
        return null
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
        "try-body",
        "error-handler",
        "parallel",
        "parallel-branch",
        "loop",
        "match",
        "retry",
        "condition"
    )

    private fun TargetStep.flattenForReadiness(): List<TargetStep> =
        listOf(this) + children.flatMap { it.flattenForReadiness() }
}
