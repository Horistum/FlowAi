package org.flowlang.generators.manifest

/**
 * Decides whether a target manifest may be serialized as an executable target
 * artifact or must remain a review-only Flow artifact.
 *
 * Materialization status alone is not executable evidence. A target-native
 * projection must be explicit at manifest, leaf-step and renderer boundaries.
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
    val reason: String,
    val opaqueReferences: List<String> = emptyList()
)

data class TargetRenderDecision(
    val mode: TargetRenderMode,
    val executable: Boolean,
    val reason: String,
    val findings: List<TargetRenderFinding>
)

object TargetRenderPolicy {
    fun evaluate(manifest: TargetManifest): TargetRenderDecision {
        requireContractValidity(manifest)

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
                val nativeStatus = step.materialization.status == TargetMaterializationStatus.NATIVE
                val explicitStepProjection = step.metadata["targetNativeProjection"] == "true"
                val rendererEvidence = hasRendererEvidence(manifest.target, step)
                val targetNative = nativeStatus && explicitStepProjection && rendererEvidence
                if (!targetNative) {
                    findings += TargetRenderFinding(
                        jobId = job.id,
                        stepId = step.id,
                        stepType = step.type,
                        materializationStatus = step.materialization.status,
                        capability = step.materialization.capability,
                        reason = when {
                            step.materialization.status == TargetMaterializationStatus.NOTES_PROJECTED ->
                                "Notes-backed semantic materialization exists, but no target-native executable projection is attached."
                            nativeStatus && !explicitStepProjection ->
                                "Native materialization is declared, but the step does not carry explicit target-native projection evidence."
                            nativeStatus && !rendererEvidence ->
                                "Native materialization is declared, but renderer-specific executable metadata is missing."
                            else -> step.materialization.reason
                        },
                        opaqueReferences = step.params.values
                            .flatMap { value -> OPAQUE_REFERENCE.findAll(value).map { it.groupValues[1] }.toList() }
                            .distinct()
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

    private fun requireContractValidity(manifest: TargetManifest) {
        val generatedTarget = when (manifest.metadata["generator"]) {
            "JenkinsManifestGenerator" -> "jenkins"
            "GitHubActionsManifestGenerator" -> "github-actions"
            "TektonManifestGenerator" -> "tekton"
            else -> null
        }
        if (generatedTarget != null && generatedTarget != manifest.target) {
            throw IllegalArgumentException(
                "TargetManifest is not renderable: RENDERER_TARGET_MISMATCH at target: " +
                    "generator '$generatedTarget' produced a manifest now labelled '${manifest.target}'."
            )
        }
        TargetRendererContractValidator.requireRenderable(manifest, manifest.target)
    }

    private fun hasRendererEvidence(target: String, step: TargetStep): Boolean = when (target) {
        "jenkins" -> step.type == "approval" || !step.metadata["jenkinsDirective"].isNullOrBlank()
        "github-actions" -> !step.metadata["githubUses"].isNullOrBlank()
        "tekton" -> !step.metadata["tektonTaskRef"].isNullOrBlank()
        else -> false
    }

    private fun TargetStep.leafSteps(): List<TargetStep> =
        if (children.isEmpty()) listOf(this) else children.flatMap { it.leafSteps() }

    private val OPAQUE_REFERENCE = Regex("secret:([A-Za-z0-9_.-]+)")
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
                        if (finding.opaqueReferences.isNotEmpty()) {
                            sb.appendLine("        opaqueRequirements:")
                            finding.opaqueReferences.forEach { reference ->
                                sb.appendLine("          - ${quoted(reference)}")
                            }
                        }
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
