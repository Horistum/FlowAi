package org.flowlang.generators.manifest

import org.flowlang.capabilities.TargetRendererPayloadKind

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
                !payloadKindMatchesTarget(step.rendererPayload.kind, manifest.target) -> TargetRenderFinding(
                    step.id,
                    "TARGET_PAYLOAD_KIND_MISMATCH",
                    "Renderer payload kind '${step.rendererPayload.kind}' cannot be emitted by target '${manifest.target}'."
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

    private fun payloadKindMatchesTarget(kind: TargetRendererPayloadKind, target: String): Boolean = when (target) {
        "jenkins" -> kind == TargetRendererPayloadKind.JENKINS_STEP
        "github-actions" -> kind == TargetRendererPayloadKind.GITHUB_ACTION
        "tekton" -> kind == TargetRendererPayloadKind.TEKTON_TASK
        else -> false
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

object TargetReviewArtifactRenderer {
    fun render(manifest: TargetManifest, readiness: TargetRenderReadiness): String {
        require(readiness.mode == TargetRenderMode.REVIEW_ONLY) { "Review artifact requires REVIEW_ONLY readiness." }
        val compatibilityReadiness = TargetCompatibilityReadinessAnalyzer.analyze(manifest)
        val sb = StringBuilder()
        sb.appendLine("apiVersion: flowlang.org/v1alpha1")
        sb.appendLine("kind: TargetProjectionReview")
        sb.appendLine("metadata:")
        sb.appendLine("  name: ${quoted(sanitizeId(manifest.flowName))}")
        sb.appendLine("spec:")
        sb.appendLine("  standardVersion: ${quoted(manifest.standardVersion)}")
        sb.appendLine("  manifestVersion: ${quoted(manifest.manifestVersion)}")
        sb.appendLine("  target: ${quoted(manifest.target)}")
        sb.appendLine("  capabilityCompatibility: ${quoted(compatibilityReadiness.capabilityStatus.name)}")
        sb.appendLine("  effectiveCompatibility: ${quoted(compatibilityReadiness.effectiveStatus.name)}")
        sb.appendLine("  materializationReadiness: ${quoted(compatibilityReadiness.materializationReadiness.name)}")
        sb.appendLine("  projectionReadiness: ${quoted(compatibilityReadiness.projectionReadiness.name)}")
        sb.appendLine("  renderMode: REVIEW_ONLY")
        sb.appendLine("  executable: false")
        sb.appendLine("  reason: ${quoted("Target syntax was not emitted because required materialization, trigger, or renderer payload evidence is unresolved.")}")
        if (manifest.triggers.isNotEmpty()) {
            sb.appendLine("  triggers:")
            manifest.triggers.forEach { trigger ->
                sb.appendLine("    - id: ${quoted(trigger.id)}")
                sb.appendLine("      type: ${quoted(trigger.type)}")
                trigger.scheduleKind?.let { sb.appendLine("      scheduleKind: ${quoted(it)}") }
                trigger.scheduleExpression?.let { sb.appendLine("      scheduleExpression: ${quoted(it)}") }
                trigger.timezone?.let { sb.appendLine("      timezone: ${quoted(it)}") }
                trigger.event?.let { sb.appendLine("      event: ${quoted(it)}") }
            }
        }
        sb.appendLine("  findings:")
        readiness.findings.forEach { finding ->
            sb.appendLine("    - nodeId: ${quoted(finding.nodeId)}")
            sb.appendLine("      status: ${quoted(finding.status)}")
            sb.appendLine("      reason: ${quoted(finding.reason)}")
        }
        sb.appendLine("  semanticInventory:")
        manifest.reviewInventoryLeaves().forEach { step ->
            sb.appendLine("    - nodeId: ${quoted(step.id)}")
            sb.appendLine("      module: ${quoted(step.module.orEmpty())}")
            sb.appendLine("      action: ${quoted(step.action.orEmpty())}")
            sb.appendLine("      materializationStatus: ${quoted(step.materialization.status.name)}")
            sb.appendLine("      materializationSource: ${quoted(step.materialization.metadata["materializationSource"].orEmpty())}")
            sb.appendLine("      projectionArtifact: ${quoted(step.materialization.requirements["projectionArtifact"].orEmpty())}")
            sb.appendLine("      rendererPayloadPresent: ${step.rendererPayload != null}")
            step.rendererPayload?.let { payload ->
                sb.appendLine("      rendererPayloadKind: ${quoted(payload.kind.name)}")
                sb.appendLine("      rendererPayloadReference: ${quoted(payload.reference)}")
                sb.appendLine("      rendererPayloadEvidence: ${quoted(payload.evidenceReference)}")
            }
        }
        val requirements = manifest.bindingRequirements()
        if (requirements.isNotEmpty()) {
            sb.appendLine("  requirements:")
            requirements.forEach { requirement -> requirement.appendTo(sb) }
        }
        return sb.toString()
    }

    private fun TargetManifest.reviewInventoryLeaves(): List<TargetStep> = jobs
        .flatMap { job -> job.steps.flatMap { it.flattenForReviewInventory() } }
        .filter { it.isReviewInventoryLeaf() }

    private fun TargetStep.isReviewInventoryLeaf(): Boolean = children.isEmpty() && type !in setOf(
        "try-body", "error-handler", "parallel", "parallel-branch", "loop", "match", "retry", "condition"
    )

    private fun TargetStep.flattenForReviewInventory(): List<TargetStep> =
        listOf(this) + children.flatMap { it.flattenForReviewInventory() }

    private data class BindingRequirement(
        val nodeId: String,
        val parameter: String,
        val opaqueReference: String,
        val target: String
    ) {
        fun appendTo(sb: StringBuilder) {
            val envName = safeEnvName(opaqueReference)
            sb.appendLine("    - nodeId: ${quoted(nodeId)}")
            sb.appendLine("      parameter: ${quoted(parameter)}")
            sb.appendLine("      opaqueReference: ${quoted(opaqueReference)}")
            sb.appendLine("      runtimeReference: ${quoted("\$$envName")}")
            when (target) {
                "jenkins" -> sb.appendLine("      targetBinding: ${quoted("$envName = credentials('$opaqueReference')")}")
                "github-actions" -> {
                    val expression = "\$" + "{{ secrets.$opaqueReference }}"
                    sb.appendLine("      targetBinding: ${quoted("$envName: $expression")}")
                }
                "tekton" -> {
                    sb.appendLine("      targetBinding:")
                    sb.appendLine("        secretKeyRef:")
                    sb.appendLine("          name: flow-secrets")
                    sb.appendLine("          key: $opaqueReference")
                }
                else -> sb.appendLine("      targetBinding: ${quoted("Target binding notes are required for '$target'.")}")
            }
        }
    }

    private fun TargetManifest.bindingRequirements(): List<BindingRequirement> = jobs
        .flatMap { job -> job.steps.flatMap { it.flattenForReview() } }
        .flatMap { step ->
            step.params.flatMap { (parameter, value) ->
                opaqueReferenceRegex.findAll(value).map { match ->
                    BindingRequirement(step.id, parameter, match.groupValues[1], target)
                }.toList()
            }
        }
        .distinct()

    private fun TargetStep.flattenForReview(): List<TargetStep> = listOf(this) + children.flatMap { it.flattenForReview() }

    private fun safeEnvName(value: String): String = "FLOW_SECRET_" + value.uppercase()
        .replace(Regex("[^A-Z0-9_]+"), "_")
        .trim('_')
        .ifBlank { "OPAQUE" }

    private fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private val opaqueReferenceRegex = Regex("secret:([A-Za-z0-9_.-]+)")
}
