package org.flowlang.generators.manifest

/**
 * v0.9.0 Generator Projection Contract.
 *
 * The manifest is the only supported boundary between the platform-neutral ExecutionPlan and concrete
 * target renderers. Renderers may translate TargetManifest into Jenkins, GitHub Actions, Tekton, etc.,
 * but they must not silently invent planning semantics, hide unsupported work, or accept malformed
 * manifests. This validator checks the structural contract of that boundary without executing anything
 * and without introducing a target-specific public DSL.
 */
object TargetManifestContractValidator {
    private val idPattern = Regex("^[a-z0-9][a-z0-9_-]*$")
    private val noteLevels = setOf("info", "warning", "error")

    fun validate(manifest: TargetManifest): TargetManifestContractReport {
        val issues = mutableListOf<TargetManifestContractIssue>()
        fun error(code: String, path: String, message: String) {
            issues += TargetManifestContractIssue("error", code, path, message)
        }

        if (manifest.manifestVersion.isBlank()) error("MANIFEST_VERSION_BLANK", "manifestVersion", "TargetManifest.manifestVersion must be present.")
        if (manifest.standardVersion.isBlank()) error("STANDARD_VERSION_BLANK", "standardVersion", "TargetManifest.standardVersion must be present.")
        if (manifest.target.isBlank()) error("TARGET_BLANK", "target", "TargetManifest.target must be present.")
        if (manifest.flowName.isBlank()) error("FLOW_NAME_BLANK", "flowName", "TargetManifest.flowName must be present.")
        if (manifest.jobs.isEmpty()) error("JOBS_EMPTY", "jobs", "TargetManifest must contain at least one job, even for an empty flow projection.")

        val requiredMetadata = mapOf(
            "sourcePlanVersion" to "source plan version",
            "generator" to "generator identity",
            "standardVersion" to "public standard version"
        )
        requiredMetadata.forEach { (key, label) ->
            if (manifest.metadata[key].isNullOrBlank()) error("MANIFEST_METADATA_MISSING", "metadata.$key", "TargetManifest metadata must include $label.")
        }
        val metadataStandard = manifest.metadata["standardVersion"]
        if (!metadataStandard.isNullOrBlank() && metadataStandard != manifest.standardVersion) {
            error("MANIFEST_STANDARD_VERSION_MISMATCH", "metadata.standardVersion", "Manifest metadata standardVersion must match TargetManifest.standardVersion.")
        }

        validateNotes(manifest.mappingNotes, "mappingNotes", issues)

        val jobIds = mutableSetOf<String>()
        val stepIds = mutableSetOf<String>()
        manifest.jobs.forEachIndexed { index, job ->
            val path = "jobs[$index]"
            validateId(job.id, "$path.id", "JOB_ID_INVALID", issues)
            if (!jobIds.add(job.id)) error("JOB_ID_DUPLICATE", "$path.id", "Job id '${job.id}' is duplicated in the manifest.")
            validateNotes(job.mappingNotes, "$path.mappingNotes", issues)
            if (job.steps.isEmpty()) error("JOB_STEPS_EMPTY", "$path.steps", "A projected job must carry at least one step or an explicit placeholder job must be explained before rendering.")
            job.steps.forEachIndexed { stepIndex, step -> validateStep(step, "$path.steps[$stepIndex]", stepIds, issues) }
        }

        return TargetManifestContractReport(
            target = manifest.target,
            flowName = manifest.flowName,
            valid = issues.none { it.level == "error" },
            issues = issues,
            metadata = mapOf(
                "manifestVersion" to manifest.manifestVersion,
                "standardVersion" to manifest.standardVersion,
                "jobCount" to manifest.jobs.size.toString()
            )
        )
    }

    private fun validateStep(step: TargetStep, path: String, stepIds: MutableSet<String>, issues: MutableList<TargetManifestContractIssue>) {
        fun error(code: String, p: String, message: String) {
            issues += TargetManifestContractIssue("error", code, p, message)
        }
        validateId(step.id, "$path.id", "STEP_ID_INVALID", issues)
        if (!stepIds.add(step.id)) error("STEP_ID_DUPLICATE", "$path.id", "Step id '${step.id}' is duplicated in the manifest.")
        if (step.type.isBlank()) error("STEP_TYPE_BLANK", "$path.type", "TargetStep.type must be present.")
        validateNotes(step.mappingNotes, "$path.mappingNotes", issues)

        if (step.type == "action") {
            if (step.module.isNullOrBlank()) error("ACTION_MODULE_BLANK", "$path.module", "Action step must carry the source module.")
            if (step.action.isNullOrBlank()) error("ACTION_NAME_BLANK", "$path.action", "Action step must carry the source action.")
            if (step.target.isNullOrBlank()) error("ACTION_TARGET_BLANK", "$path.target", "Action step must carry the target system path.")
            val run = step.run.orEmpty()
            if (run.isBlank()) error("ACTION_RUN_BLANK", "$path.run", "Action step must carry the command or explicit failing diagnostic rendered by the manifest layer.")
            if (run.contains("Flow executes")) {
                error("ACTION_PLACEBO_COMMAND", "$path.run", "Action step must not use a green placeholder command that claims Flow executed work without a real mapping or failing diagnostic.")
            }
        }

        step.children.forEachIndexed { index, child -> validateStep(child, "$path.children[$index]", stepIds, issues) }
    }

    private fun validateId(id: String, path: String, code: String, issues: MutableList<TargetManifestContractIssue>) {
        if (id.isBlank() || !idPattern.matches(id)) {
            issues += TargetManifestContractIssue("error", code, path, "Projection ids must be non-blank, lower-case, renderer-safe ids. Got '$id'.")
        }
    }

    private fun validateNotes(notes: List<TargetMappingNote>, path: String, issues: MutableList<TargetManifestContractIssue>) {
        notes.forEachIndexed { index, note ->
            val notePath = "$path[$index]"
            if (note.level !in noteLevels) {
                issues += TargetManifestContractIssue("error", "MAPPING_NOTE_LEVEL_INVALID", "$notePath.level", "Mapping note level must be one of ${noteLevels.joinToString()}.")
            }
            if (note.target.isBlank()) issues += TargetManifestContractIssue("error", "MAPPING_NOTE_TARGET_BLANK", "$notePath.target", "Mapping note target must be present.")
            if (note.nodeId.isBlank()) issues += TargetManifestContractIssue("error", "MAPPING_NOTE_NODE_BLANK", "$notePath.nodeId", "Mapping note nodeId must be present.")
            if (note.feature.isBlank()) issues += TargetManifestContractIssue("error", "MAPPING_NOTE_FEATURE_BLANK", "$notePath.feature", "Mapping note feature must be present.")
            if (note.message.isBlank()) issues += TargetManifestContractIssue("error", "MAPPING_NOTE_MESSAGE_BLANK", "$notePath.message", "Mapping note message must be present.")
        }
    }
}

data class TargetManifestContractReport(
    val target: String,
    val flowName: String,
    val valid: Boolean,
    val issues: List<TargetManifestContractIssue>,
    val metadata: Map<String, String> = emptyMap()
)

data class TargetManifestContractIssue(
    val level: String,
    val code: String,
    val path: String,
    val message: String
)
