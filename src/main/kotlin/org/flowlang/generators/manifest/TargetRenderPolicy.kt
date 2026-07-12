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
    private val completedStatuses = setOf(TargetMaterializationStatus.NATIVE, TargetMaterializationStatus.NOTES_PROJECTED)
    private val blockedStatuses = setOf(TargetMaterializationStatus.BLOCKED, TargetMaterializationStatus.UNSUPPORTED)

    fun evaluate(manifest: TargetManifest): TargetRenderReadiness {
        val steps = manifest.jobs.flatMap { job -> job.steps.flatMap { it.flattenForReadiness() } }
        val blocking = steps.filter { it.materialization.status in blockedStatuses }.map { step ->
            TargetRenderFinding(step.id, step.materialization.status.name, step.materialization.reason)
        }.distinct()
        if (blocking.isNotEmpty()) return TargetRenderReadiness(manifest.target, TargetRenderMode.FAIL_FAST, blocking)

        val unresolved = steps.filter { it.isMaterializationLeaf() }.mapNotNull { step ->
            when {
                step.materialization.status !in completedStatuses -> TargetRenderFinding(
                    step.id,
                    step.materialization.status.name,
                    step.materialization.reason
                )
                step.metadata["rendererReady"] != "true" -> TargetRenderFinding(
                    step.id,
                    "TARGET_PAYLOAD_MISSING",
                    "Materialization evidence exists, but no target renderer payload is declared for '${manifest.target}'."
                )
                step.metadata["rendererTarget"] != manifest.target -> TargetRenderFinding(
                    step.id,
                    "TARGET_PAYLOAD_MISMATCH",
                    "Renderer payload is not bound to target '${manifest.target}'."
                )
                step.metadata["rendererPayloadId"].isNullOrBlank() -> TargetRenderFinding(
                    step.id,
                    "TARGET_PAYLOAD_ID_MISSING",
                    "Renderer readiness requires a concrete target payload identifier."
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

object TargetReviewArtifactRenderer {
    fun render(manifest: TargetManifest, readiness: TargetRenderReadiness): String {
        require(readiness.mode == TargetRenderMode.REVIEW_ONLY) { "Review artifact requires REVIEW_ONLY readiness." }
        val sb = StringBuilder()
        sb.appendLine("apiVersion: flowlang.org/v1alpha1")
        sb.appendLine("kind: TargetProjectionReview")
        sb.appendLine("metadata:")
        sb.appendLine("  name: ${quoted(sanitizeId(manifest.flowName))}")
        sb.appendLine("spec:")
        sb.appendLine("  standardVersion: ${quoted(manifest.standardVersion)}")
        sb.appendLine("  manifestVersion: ${quoted(manifest.manifestVersion)}")
        sb.appendLine("  target: ${quoted(manifest.target)}")
        sb.appendLine("  renderMode: REVIEW_ONLY")
        sb.appendLine("  executable: false")
        sb.appendLine("  compatibility: ${quoted(manifest.compatibility.status.name)}")
        sb.appendLine("  reason: ${quoted("Target syntax was not emitted because required renderer payloads are unresolved.")}")
        sb.appendLine("  findings:")
        readiness.findings.forEach { finding ->
            sb.appendLine("    - nodeId: ${quoted(finding.nodeId)}")
            sb.appendLine("      status: ${quoted(finding.status)}")
            sb.appendLine("      reason: ${quoted(finding.reason)}")
        }
        val requirements = manifest.bindingRequirements()
        if (requirements.isNotEmpty()) {
            sb.appendLine("  requirements:")
            requirements.forEach { requirement -> requirement.appendTo(sb) }
        }
        return sb.toString()
    }

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
