package org.flowlang.generators.manifest

import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingKind
import org.flowlang.projection.ProjectionBindingResolutionStatus

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
        appendTriggers(manifest, sb)
        sb.appendLine("  findings:")
        readiness.findings.forEach { finding ->
            sb.appendLine("    - nodeId: ${quoted(finding.nodeId)}")
            sb.appendLine("      status: ${quoted(finding.status)}")
            sb.appendLine("      reason: ${quoted(finding.reason)}")
        }
        sb.appendLine("  semanticInventory:")
        manifest.reviewInventoryLeaves().forEach { step -> appendInventoryStep(step, sb) }
        val requirements = manifest.bindingRequirements()
        if (requirements.isNotEmpty()) {
            sb.appendLine("  bindingRequirements:")
            requirements.forEach { requirement -> requirement.appendTo(sb) }
        }
        return sb.toString()
    }

    private fun appendTriggers(manifest: TargetManifest, sb: StringBuilder) {
        if (manifest.triggers.isEmpty()) return
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

    private fun appendInventoryStep(step: TargetStep, sb: StringBuilder) {
        sb.appendLine("    - nodeId: ${quoted(step.id)}")
        sb.appendLine("      module: ${quoted(step.module.orEmpty())}")
        sb.appendLine("      action: ${quoted(step.action.orEmpty())}")
        sb.appendLine("      materializationStatus: ${quoted(step.materialization.status.name)}")
        sb.appendLine("      materializationSource: ${quoted(step.materialization.metadata["materializationSource"].orEmpty())}")
        sb.appendLine("      projectionArtifact: ${quoted(step.materialization.requirements["projectionArtifact"].orEmpty())}")
        sb.appendLine("      rendererPayloadPresent: ${step.rendererPayload != null}")
        step.rendererPayload?.let { payload ->
            sb.appendLine("      rendererPayloadKind: ${quoted(payload.kind)}")
            sb.appendLine("      rendererPayloadReference: ${quoted(payload.reference)}")
            sb.appendLine("      rendererPayloadEvidence: ${quoted(payload.evidenceReference)}")
            if (payload.bindings.isNotEmpty()) {
                sb.appendLine("      rendererBindings:")
                payload.bindings.forEach { (name, binding) ->
                    sb.appendLine("        - name: ${quoted(name)}")
                    sb.appendLine("          kind: ${quoted(binding.kind.name)}")
                    sb.appendLine("          resolutionStatus: ${quoted(binding.resolutionStatus?.name ?: "UNKNOWN")}")
                    binding.sourceReference()?.let { source ->
                        sb.appendLine("          source: ${quoted(source)}")
                    }
                    binding.reason?.let { reason ->
                        sb.appendLine("          reason: ${quoted(reason)}")
                    }
                }
            }
        }
    }

    private fun TargetManifest.reviewInventoryLeaves(): List<TargetStep> = jobs
        .flatMap { job -> job.steps.flatMap { it.flattenForReviewInventory() } }
        .filter { it.isReviewInventoryLeaf() }

    private fun TargetStep.isReviewInventoryLeaf(): Boolean = children.isEmpty() && type !in setOf(
        "try-body",
        "error-handler",
        "parallel",
        "parallel-branch",
        "loop",
        "match",
        "retry",
        "condition"
    )

    private fun TargetStep.flattenForReviewInventory(): List<TargetStep> =
        listOf(this) + children.flatMap { it.flattenForReviewInventory() }

    private data class BindingRequirement(
        val nodeId: String,
        val parameter: String,
        val kind: String,
        val resolutionStatus: String,
        val source: String,
        val evidence: String,
        val reason: String? = null
    ) {
        fun appendTo(sb: StringBuilder) {
            sb.appendLine("    - nodeId: ${quoted(nodeId)}")
            sb.appendLine("      parameter: ${quoted(parameter)}")
            sb.appendLine("      kind: ${quoted(kind)}")
            sb.appendLine("      resolutionStatus: ${quoted(resolutionStatus)}")
            sb.appendLine("      source: ${quoted(source)}")
            sb.appendLine("      evidence: ${quoted(evidence)}")
            reason?.let { sb.appendLine("      reason: ${quoted(it)}") }
        }
    }

    private fun TargetManifest.bindingRequirements(): List<BindingRequirement> = jobs
        .flatMap { job -> job.steps.flatMap { it.flattenForReview() } }
        .flatMap { step -> step.typedBindingRequirements() + step.legacySecretRequirements() }
        .distinct()

    private fun TargetStep.typedBindingRequirements(): List<BindingRequirement> =
        rendererPayload?.bindings.orEmpty().mapNotNull { (parameter, binding) ->
            if (binding.resolutionStatus != ProjectionBindingResolutionStatus.RESOLVED) {
                BindingRequirement(
                    nodeId = id,
                    parameter = parameter,
                    kind = binding.kind.name,
                    resolutionStatus = binding.resolutionStatus?.name ?: "UNKNOWN",
                    source = binding.sourceReference().orEmpty(),
                    evidence = rendererPayload?.evidenceReference.orEmpty(),
                    reason = binding.reason
                )
            } else {
                null
            }
        }

    private fun TargetStep.legacySecretRequirements(): List<BindingRequirement> = params.flatMap { (parameter, value) ->
        opaqueReferenceRegex.findAll(value).map { match ->
            BindingRequirement(
                nodeId = id,
                parameter = parameter,
                kind = "LEGACY_SECRET_REFERENCE",
                resolutionStatus = "SYMBOLIC",
                source = match.groupValues[1],
                evidence = "step.params"
            )
        }.toList()
    }

    private fun ProjectionBinding.sourceReference(): String? = when (kind) {
        ProjectionBindingKind.LITERAL -> "literal"
        ProjectionBindingKind.TASK_PARAMETER -> name?.let { "task.parameter:$it" }
        ProjectionBindingKind.TASK_INPUT -> name?.let { "task.input:$it" }
        ProjectionBindingKind.TASK_METADATA -> field?.let { "task.metadata:${it.name}" }
        ProjectionBindingKind.FLOW_INPUT -> name?.let { "flow.input:$it" }
        ProjectionBindingKind.SECRET -> name?.let { "secret:$it" }
        ProjectionBindingKind.ARTIFACT -> name?.let { "artifact:$it" }
        ProjectionBindingKind.TASK_OUTPUT -> if (taskId != null && output != null) {
            "task.output:$taskId.$output"
        } else {
            null
        }
        ProjectionBindingKind.TARGET_EXPRESSION -> if (target != null && expression != null) {
            "target.expression:$target"
        } else {
            null
        }
    }

    private fun TargetStep.flattenForReview(): List<TargetStep> =
        listOf(this) + children.flatMap { it.flattenForReview() }

    private fun quoted(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n") + "\""

    private val opaqueReferenceRegex = Regex("secret:([A-Za-z0-9_.-]+)")
}
