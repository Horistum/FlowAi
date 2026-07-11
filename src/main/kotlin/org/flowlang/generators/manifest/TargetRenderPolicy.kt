package org.flowlang.generators.manifest

/**
 * Decides whether a target manifest may be serialized as an executable target
 * artifact or must remain a review-only Flow artifact.
 *
 * Materialization status alone is not executable evidence. A target-native
 * projection must be explicit at both manifest and leaf-step boundaries.
 */
enum class TargetRenderMode {
    EXECUTABLE,
    REVIEW_ONLY
}

data class TargetRenderFinding(
    val jobId: String,
    val stepId: String,
    val stepType: String,
    val materializationStatus: TargetMaterializationStatus,
    val capability: String,
    val reason: String
)

data class TargetRenderDecision(
    val mode: TargetRenderMode,
    val executable: Boolean,
    val reason: String,
    val findings: List<TargetRenderFinding>
)

object TargetRenderPolicy {
    fun evaluate(manifest: TargetManifest): TargetRenderDecision {
        val findings = mutableListOf<TargetRenderFinding>()
        val explicitManifestProjection = manifest.metadata["executableProjection"] == "true"

        if (!explicitManifestProjection) {
            findings += TargetRenderFinding(
                jobId = "manifest",
                stepId = "manifest",
                stepType = "manifest",
                materializationStatus = TargetMaterializationStatus.DECLARATIVE_ONLY,
                capability = "flow.target.projection",
                reason = "Manifest does not carry explicit executable target projection evidence."
            )
        }

        manifest.jobs.forEach { job ->
            val leaves = job.steps.flatMap { it.leafSteps() }
            if (leaves.isEmpty()) {
                findings += TargetRenderFinding(
                    jobId = job.id,
                    stepId = job.id,
                    stepType = "job",
                    materializationStatus = TargetMaterializationStatus.DECLARATIVE_ONLY,
                    capability = "flow.job",
                    reason = "Job contains no target-native leaf steps."
                )
            }
            leaves.forEach { step ->
                val targetNative = step.materialization.status == TargetMaterializationStatus.NATIVE &&
                    step.metadata["targetNativeProjection"] == "true"
                if (!targetNative) {
                    findings += TargetRenderFinding(
                        jobId = job.id,
                        stepId = step.id,
                        stepType = step.type,
                        materializationStatus = step.materialization.status,
                        capability = step.materialization.capability,
                        reason = when (step.materialization.status) {
                            TargetMaterializationStatus.NOTES_PROJECTED ->
                                "Notes-backed semantic materialization exists, but no target-native executable projection is attached."
                            else -> step.materialization.reason
                        }
                    )
                }
            }
        }

        val executable = findings.isEmpty()
        return TargetRenderDecision(
            mode = if (executable) TargetRenderMode.EXECUTABLE else TargetRenderMode.REVIEW_ONLY,
            executable = executable,
            reason = if (executable) {
                "Every target leaf carries explicit target-native executable projection evidence."
            } else {
                "Target artifact is review-only because required work is not fully target-native materialized."
            },
            findings = findings
        )
    }

    private fun TargetStep.leafSteps(): List<TargetStep> =
        if (children.isEmpty()) listOf(this) else children.flatMap { it.leafSteps() }
}

object TargetReviewArtifactRenderer {
    fun render(manifest: TargetManifest, decision: TargetRenderDecision = TargetRenderPolicy.evaluate(manifest)): String {
        require(decision.mode == TargetRenderMode.REVIEW_ONLY) { "Review renderer requires REVIEW_ONLY decision." }
        val sb = StringBuilder()
        sb.appendLine("# Flow target review artifact")
        sb.appendLine("reviewVersion: \"1.0\"")
        sb.appendLine("standardVersion: ${quoted(manifest.standardVersion)}")
        sb.appendLine("target: ${quoted(manifest.target)}")
        sb.appendLine("flow: ${quoted(manifest.flowName)}")
        sb.appendLine("mode: REVIEW_ONLY")
        sb.appendLine("executable: false")
        sb.appendLine("reason: ${quoted(decision.reason)}")
        appendRequestedFormat(manifest.target, sb)
        sb.appendLine("inputs:")
        if (manifest.inputs.isEmpty()) {
            sb.appendLine("  []")
        } else {
            manifest.inputs.forEach { input ->
                sb.appendLine("  - name: ${quoted(input.name)}")
                sb.appendLine("    targetReference: ${quoted(targetReference(manifest.target, input.name))}")
            }
        }
        sb.appendLine("jobs:")
        if (manifest.jobs.isEmpty()) {
            sb.appendLine("  []")
        } else {
            manifest.jobs.forEach { job ->
                sb.appendLine("  - id: ${quoted(job.id)}")
                sb.appendLine("    unresolved:")
                val jobFindings = decision.findings.filter { it.jobId == job.id }
                if (jobFindings.isEmpty()) {
                    sb.appendLine("      []")
                } else {
                    jobFindings.forEach { finding ->
                        sb.appendLine("      - step: ${quoted(finding.stepId)}")
                        sb.appendLine("        type: ${quoted(finding.stepType)}")
                        sb.appendLine("        materialization: ${finding.materializationStatus}")
                        sb.appendLine("        capability: ${quoted(finding.capability)}")
                        sb.appendLine("        reason: ${quoted(finding.reason)}")
                    }
                }
            }
        }
        val manifestFindings = decision.findings.filter { it.jobId == "manifest" }
        if (manifestFindings.isNotEmpty()) {
            sb.appendLine("manifestFindings:")
            manifestFindings.forEach { finding ->
                sb.appendLine("  - materialization: ${finding.materializationStatus}")
                sb.appendLine("    capability: ${quoted(finding.capability)}")
                sb.appendLine("    reason: ${quoted(finding.reason)}")
            }
        }
        return sb.toString()
    }

    private fun appendRequestedFormat(target: String, sb: StringBuilder) {
        when (target) {
            "jenkins" -> {
                sb.appendLine("requestedArtifact: \"Jenkinsfile\"")
                sb.appendLine("requested-syntax: \"pipeline {\"")
            }
            "github-actions" -> sb.appendLine("requestedArtifact: \"GitHub Actions workflow\"")
            "tekton" -> {
                sb.appendLine("requestedArtifact: \"Tekton Pipeline\"")
                sb.appendLine("requested-kind: Pipeline")
            }
            else -> sb.appendLine("requestedArtifact: ${quoted(target)}")
        }
    }

    private fun targetReference(target: String, name: String): String = when (target) {
        "jenkins" -> "\${params.$name}"
        "github-actions" -> "\${{ inputs.$name }}"
        "tekton" -> "\$(params.$name)"
        else -> name
    }

    private fun quoted(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
