package org.flowlang.generators.manifest

/**
 * v0.9.2 renderer contract hardening.
 *
 * Renderers are serialization boundaries. They must only render manifests that are already valid,
 * targeted at the renderer being invoked, dependency-consistent, and structurally unambiguous. v0.9.5.x
 * removes command text from this contract, so renderers validate structure and materialization status
 * instead of accepting shell-oriented fields.
 */
object TargetRendererContractValidator {
    fun validate(manifest: TargetManifest, rendererTarget: String): TargetRendererContractReport {
        val issues = mutableListOf<TargetRendererContractIssue>()
        fun error(code: String, path: String, message: String) {
            issues += TargetRendererContractIssue("error", code, path, message)
        }

        val manifestReport = TargetManifestContractValidator.validate(manifest)
        manifestReport.issues.forEach { issue ->
            issues += TargetRendererContractIssue(issue.level, "MANIFEST_${issue.code}", issue.path, issue.message)
        }

        if (rendererTarget.isBlank()) {
            error("RENDERER_TARGET_BLANK", "rendererTarget", "Renderer target identity must be present.")
        }
        if (manifest.target != rendererTarget) {
            error(
                "RENDERER_TARGET_MISMATCH",
                "target",
                "Renderer '$rendererTarget' must not render a manifest for target '${manifest.target}'."
            )
        }

        val jobIds = manifest.jobs.map { it.id }.toSet()
        manifest.jobs.forEachIndexed { jobIndex, job ->
            val jobPath = "jobs[$jobIndex]"
            job.dependsOn.forEach { dep ->
                if (dep !in jobIds) error("JOB_DEPENDENCY_UNKNOWN", "$jobPath.dependsOn", "Job '${job.id}' depends on unknown job '$dep'.")
            }
            val stepIds = job.steps.flatMap { it.collectStepIds() }.toSet()
            val knownDependencyIds = jobIds + stepIds
            job.steps.forEachIndexed { stepIndex, step ->
                validateStep(step, "$jobPath.steps[$stepIndex]", knownDependencyIds, issues)
            }
        }

        return TargetRendererContractReport(
            target = manifest.target,
            rendererTarget = rendererTarget,
            valid = issues.none { it.level == "error" },
            issues = issues,
            metadata = mapOf(
                "manifestTarget" to manifest.target,
                "rendererTarget" to rendererTarget,
                "jobCount" to manifest.jobs.size.toString()
            )
        )
    }

    fun requireRenderable(manifest: TargetManifest, rendererTarget: String) {
        val report = validate(manifest, rendererTarget)
        if (!report.valid) {
            val message = report.issues.joinToString("; ") { "${it.code} at ${it.path}: ${it.message}" }
            throw IllegalArgumentException("TargetManifest is not renderable by '$rendererTarget': $message")
        }
    }

    private fun validateStep(
        step: TargetStep,
        path: String,
        knownDependencyIds: Set<String>,
        issues: MutableList<TargetRendererContractIssue>
    ) {
        fun error(code: String, p: String, message: String) {
            issues += TargetRendererContractIssue("error", code, p, message)
        }

        if (step.type == "action" && step.children.isNotEmpty()) {
            error("ACTION_CHILDREN_UNSUPPORTED", path, "Action step '${step.id}' must be a leaf semantic action; nested work must be represented by a structural parent step.")
        }
        step.dependsOn.forEach { dep ->
            if (dep !in knownDependencyIds) error("STEP_DEPENDENCY_UNKNOWN", "$path.dependsOn", "Step '${step.id}' depends on unknown projected id '$dep'.")
        }
        when (step.type) {
            "parallel" -> if (step.children.isEmpty()) error("PARALLEL_STEP_EMPTY", "$path.children", "Parallel step '${step.id}' must contain branches.")
            "parallel-branch" -> if (step.children.isEmpty()) error("PARALLEL_BRANCH_EMPTY", "$path.children", "Parallel branch '${step.id}' must contain projected work.")
            "condition" -> {
                if (step.params["condition"].isNullOrBlank()) error("CONDITION_EXPRESSION_BLANK", "$path.params.condition", "Condition step '${step.id}' must carry the Flow condition expression.")
                if (step.children.isEmpty()) error("CONDITION_BODY_EMPTY", "$path.children", "Condition step '${step.id}' must contain projected work.")
            }
            "try" -> {
                val body = step.children.count { it.type == "try-body" }
                val handler = step.children.count { it.type == "error-handler" }
                if (body != 1) error("TRY_BODY_INVALID", "$path.children", "Try step '${step.id}' must contain exactly one try-body child.")
                if (handler > 1) error("TRY_HANDLER_DUPLICATE", "$path.children", "Try step '${step.id}' must not contain multiple error-handler children.")
            }
            "try-body" -> if (step.children.isEmpty()) error("TRY_BODY_EMPTY", "$path.children", "Try body '${step.id}' must contain projected work.")
            "error-handler" -> if (step.children.isEmpty()) error("ERROR_HANDLER_EMPTY", "$path.children", "Error handler '${step.id}' must contain projected work.")
        }
        step.children.forEachIndexed { index, child -> validateStep(child, "$path.children[$index]", knownDependencyIds, issues) }
    }

    private fun TargetStep.collectStepIds(): List<String> = listOf(id) + children.flatMap { it.collectStepIds() }
}

data class TargetRendererContractReport(
    val target: String,
    val rendererTarget: String,
    val valid: Boolean,
    val issues: List<TargetRendererContractIssue>,
    val metadata: Map<String, String> = emptyMap()
)

data class TargetRendererContractIssue(
    val level: String,
    val code: String,
    val path: String,
    val message: String
)
