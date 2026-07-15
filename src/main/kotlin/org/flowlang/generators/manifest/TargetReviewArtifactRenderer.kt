package org.flowlang.generators.manifest

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
                sb.appendLine("      rendererPayloadKind: ${quoted(payload.kind)}")
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
