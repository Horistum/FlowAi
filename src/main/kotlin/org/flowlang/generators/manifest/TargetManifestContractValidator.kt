package org.flowlang.generators.manifest

/**
 * Generator projection contract validation.
 *
 * The manifest is the supported boundary between the platform-neutral ExecutionPlan and concrete
 * target renderers. Action steps carry structured materialization status and typed projection
 * bindings rather than command text or prefix-encoded pseudo-types.
 */
object TargetManifestContractValidator {
    private val idPattern = Regex("^[a-z0-9][a-z0-9_-]*$")
    private val payloadKindPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._+:/-]*$")
    private val noteLevels = setOf("info", "warning", "error")

    fun validate(manifest: TargetManifest): TargetManifestContractReport {
        val issues = mutableListOf<TargetManifestContractIssue>()
        fun error(code: String, path: String, message: String) {
            issues += TargetManifestContractIssue("error", code, path, message)
        }

        if (manifest.manifestVersion.isBlank()) {
            error("MANIFEST_VERSION_BLANK", "manifestVersion", "TargetManifest.manifestVersion must be present.")
        }
        if (manifest.standardVersion.isBlank()) {
            error("STANDARD_VERSION_BLANK", "standardVersion", "TargetManifest.standardVersion must be present.")
        }
        if (manifest.target.isBlank()) error("TARGET_BLANK", "target", "TargetManifest.target must be present.")
        if (manifest.flowName.isBlank()) error("FLOW_NAME_BLANK", "flowName", "TargetManifest.flowName must be present.")
        if (manifest.jobs.isEmpty()) {
            error("JOBS_EMPTY", "jobs", "TargetManifest must contain at least one job, even for an empty flow projection.")
        }

        val requiredMetadata = mapOf(
            "sourcePlanVersion" to "source plan version",
            "generator" to "generator identity",
            "standardVersion" to "public standard version"
        )
        requiredMetadata.forEach { (key, label) ->
            if (manifest.metadata[key].isNullOrBlank()) {
                error("MANIFEST_METADATA_MISSING", "metadata.$key", "TargetManifest metadata must include $label.")
            }
        }
        val metadataStandard = manifest.metadata["standardVersion"]
        if (!metadataStandard.isNullOrBlank() && metadataStandard != manifest.standardVersion) {
            error(
                "MANIFEST_STANDARD_VERSION_MISMATCH",
                "metadata.standardVersion",
                "Manifest metadata standardVersion must match TargetManifest.standardVersion."
            )
        }
        validateReadinessMetadata(manifest, issues)
        validateNotes(manifest.mappingNotes, "mappingNotes", issues)

        val jobIds = mutableSetOf<String>()
        val stepIds = mutableSetOf<String>()
        manifest.jobs.forEachIndexed { index, job ->
            val path = "jobs[$index]"
            validateId(job.id, "$path.id", "JOB_ID_INVALID", issues)
            if (!jobIds.add(job.id)) {
                error("JOB_ID_DUPLICATE", "$path.id", "Job id '${job.id}' is duplicated in the manifest.")
            }
            validateNotes(job.mappingNotes, "$path.mappingNotes", issues)
            if (job.steps.isEmpty()) {
                error(
                    "JOB_STEPS_EMPTY",
                    "$path.steps",
                    "A projected job must carry at least one structural step or explicit blocked projection step before rendering."
                )
            }
            job.steps.forEachIndexed { stepIndex, step ->
                validateStep(step, "$path.steps[$stepIndex]", stepIds, issues)
            }
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

    private fun validateReadinessMetadata(
        manifest: TargetManifest,
        issues: MutableList<TargetManifestContractIssue>
    ) {
        fun error(code: String, path: String, message: String) {
            issues += TargetManifestContractIssue("error", code, path, message)
        }
        if (manifest.metadata["generator"].isNullOrBlank()) return

        val required = listOf(
            "capabilityCompatibility",
            "effectiveCompatibility",
            "materializationReadiness",
            "projectionReadiness",
            "executable"
        )
        required.forEach { key ->
            if (manifest.metadata[key].isNullOrBlank()) {
                error("READINESS_METADATA_MISSING", "metadata.$key", "Generated target manifests must expose '$key'.")
            }
        }

        val effective = manifest.metadata["effectiveCompatibility"]
        if (!effective.isNullOrBlank() && effective != manifest.compatibility.status.name) {
            error(
                "EFFECTIVE_COMPATIBILITY_MISMATCH",
                "metadata.effectiveCompatibility",
                "Manifest compatibility status must match readiness-reconciled effective compatibility."
            )
        }
        val executable = manifest.metadata["executable"]
        if (!executable.isNullOrBlank() && executable !in setOf("true", "false")) {
            error(
                "EXECUTABLE_METADATA_INVALID",
                "metadata.executable",
                "Executable readiness metadata must be a boolean string."
            )
        }
        if (manifest.compatibility.status.name == "SUPPORTED") {
            if (executable != "true") {
                error(
                    "SUPPORTED_WITHOUT_EXECUTABLE_READINESS",
                    "compatibility.status",
                    "A generated manifest cannot be SUPPORTED without executable projection evidence."
                )
            }
            if (manifest.metadata["materializationReadiness"] != "COMPLETE") {
                error(
                    "SUPPORTED_WITHOUT_COMPLETE_MATERIALIZATION",
                    "metadata.materializationReadiness",
                    "A generated manifest cannot be SUPPORTED while materialization is incomplete."
                )
            }
            if (manifest.metadata["projectionReadiness"] != "EXECUTABLE") {
                error(
                    "SUPPORTED_WITHOUT_EXECUTABLE_PROJECTION",
                    "metadata.projectionReadiness",
                    "A generated manifest cannot be SUPPORTED while projection is non-executable."
                )
            }
        }
    }

    private fun validateStep(
        step: TargetStep,
        path: String,
        stepIds: MutableSet<String>,
        issues: MutableList<TargetManifestContractIssue>
    ) {
        fun error(code: String, issuePath: String, message: String) {
            issues += TargetManifestContractIssue("error", code, issuePath, message)
        }
        validateId(step.id, "$path.id", "STEP_ID_INVALID", issues)
        if (!stepIds.add(step.id)) {
            error("STEP_ID_DUPLICATE", "$path.id", "Step id '${step.id}' is duplicated in the manifest.")
        }
        if (step.type.isBlank()) error("STEP_TYPE_BLANK", "$path.type", "TargetStep.type must be present.")
        validateNotes(step.mappingNotes, "$path.mappingNotes", issues)
        step.rendererPayload?.let { payload ->
            if (payload.kind.isBlank()) {
                error(
                    "RENDERER_PAYLOAD_KIND_BLANK",
                    "$path.rendererPayload.kind",
                    "Renderer payload must identify its projection consumer kind."
                )
            }
            if (payload.kind.isNotBlank() && !payloadKindPattern.matches(payload.kind)) {
                error(
                    "RENDERER_PAYLOAD_KIND_INVALID",
                    "$path.rendererPayload.kind",
                    "Renderer payload kind must be an opaque projection identifier."
                )
            }
            if (payload.target.isBlank()) {
                error(
                    "RENDERER_PAYLOAD_TARGET_BLANK",
                    "$path.rendererPayload.target",
                    "Renderer payload must identify its target."
                )
            }
            if (payload.reference.isBlank()) {
                error(
                    "RENDERER_PAYLOAD_REFERENCE_BLANK",
                    "$path.rendererPayload.reference",
                    "Renderer payload must identify a concrete native reference."
                )
            }
            if (payload.evidenceReference.isBlank()) {
                error(
                    "RENDERER_PAYLOAD_EVIDENCE_BLANK",
                    "$path.rendererPayload.evidenceReference",
                    "Renderer payload must preserve its declarative evidence reference."
                )
            }
            TargetManifestBindingValidation.issues(payload).forEach { bindingIssue ->
                error(
                    "RENDERER_PAYLOAD_BINDING_INVALID",
                    "$path.rendererPayload.bindings.${bindingIssue.name}",
                    bindingIssue.reason
                )
            }
            if (step.materialization.status != TargetMaterializationStatus.NATIVE) {
                error(
                    "RENDERER_PAYLOAD_WITHOUT_NATIVE_MATERIALIZATION",
                    "$path.rendererPayload",
                    "Only NATIVE materialization may carry an executable renderer payload."
                )
            }
        }

        TargetStructuralProjectionKind.fromStepType(step.type)?.let { structure ->
            validateStructuralStep(step, structure, path, issues)
        }

        if (step.type == "action") {
            if (step.module.isNullOrBlank()) {
                error("ACTION_MODULE_BLANK", "$path.module", "Action step must carry the source module.")
            }
            if (step.action.isNullOrBlank()) {
                error("ACTION_NAME_BLANK", "$path.action", "Action step must carry the source action.")
            }
            if (step.target.isNullOrBlank()) {
                error("ACTION_TARGET_BLANK", "$path.target", "Action step must carry the target system path.")
            }
            if (step.materialization.capability.isBlank()) {
                error(
                    "ACTION_MATERIALIZATION_CAPABILITY_BLANK",
                    "$path.materialization.capability",
                    "Action step must declare the semantic capability being materialized or blocked."
                )
            }
            if (step.materialization.reason.isBlank()) {
                error(
                    "ACTION_MATERIALIZATION_REASON_BLANK",
                    "$path.materialization.reason",
                    "Action step must explain its materialization status."
                )
            }
        }

        step.children.forEachIndexed { index, child ->
            validateStep(child, "$path.children[$index]", stepIds, issues)
        }
    }

    private fun validateStructuralStep(
        step: TargetStep,
        structure: TargetStructuralProjectionKind,
        path: String,
        issues: MutableList<TargetManifestContractIssue>
    ) {
        if (step.materialization.status != TargetMaterializationStatus.NATIVE) return

        fun error(code: String, issuePath: String, message: String) {
            issues += TargetManifestContractIssue("error", code, issuePath, message)
        }
        if (step.materialization.capability != structure.capability) {
            error(
                "STRUCTURAL_CAPABILITY_MISMATCH",
                "$path.materialization.capability",
                "Native '${structure.name}' projection must materialize '${structure.capability}', found '${step.materialization.capability}'."
            )
        }
        val payload = step.rendererPayload
        if (payload == null) {
            error(
                "STRUCTURAL_RENDERER_PAYLOAD_MISSING",
                "$path.rendererPayload",
                "Native '${structure.name}' projection must carry a provider-owned renderer payload."
            )
        }
        val metadata = step.materialization.metadata
        if (metadata[TargetNativeProjectionCatalog.STRUCTURAL_KIND_METADATA] != structure.name) {
            error(
                "STRUCTURAL_KIND_EVIDENCE_MISSING",
                "$path.materialization.metadata.${TargetNativeProjectionCatalog.STRUCTURAL_KIND_METADATA}",
                "Native structural projection must preserve exact kind '${structure.name}'."
            )
        }
        val implementation = metadata[TargetNativeProjectionCatalog.STRUCTURAL_IMPLEMENTATION_EVIDENCE_METADATA]
        if (implementation.isNullOrBlank()) {
            error(
                "STRUCTURAL_IMPLEMENTATION_EVIDENCE_MISSING",
                "$path.materialization.metadata.${TargetNativeProjectionCatalog.STRUCTURAL_IMPLEMENTATION_EVIDENCE_METADATA}",
                "Native structural projection must cite production implementation evidence."
            )
        }
        val behavior = metadata[TargetNativeProjectionCatalog.STRUCTURAL_BEHAVIOR_EVIDENCE_METADATA]
        if (behavior.isNullOrBlank()) {
            error(
                "STRUCTURAL_BEHAVIOR_EVIDENCE_MISSING",
                "$path.materialization.metadata.${TargetNativeProjectionCatalog.STRUCTURAL_BEHAVIOR_EVIDENCE_METADATA}",
                "Native structural projection must cite independent behavioral evidence."
            )
        }
        if (payload != null && !implementation.isNullOrBlank() && payload.evidenceReference != implementation) {
            error(
                "STRUCTURAL_PAYLOAD_EVIDENCE_MISMATCH",
                "$path.rendererPayload.evidenceReference",
                "Structural payload evidence must equal the provider-owned implementation evidence."
            )
        }
    }

    private fun validateId(
        id: String,
        path: String,
        code: String,
        issues: MutableList<TargetManifestContractIssue>
    ) {
        if (id.isBlank() || !idPattern.matches(id)) {
            issues += TargetManifestContractIssue(
                "error",
                code,
                path,
                "Projection ids must be non-blank, lower-case, renderer-safe ids. Got '$id'."
            )
        }
    }

    private fun validateNotes(
        notes: List<TargetMappingNote>,
        path: String,
        issues: MutableList<TargetManifestContractIssue>
    ) {
        notes.forEachIndexed { index, note ->
            val notePath = "$path[$index]"
            if (note.level !in noteLevels) {
                issues += TargetManifestContractIssue(
                    "error",
                    "MAPPING_NOTE_LEVEL_INVALID",
                    "$notePath.level",
                    "Mapping note level must be one of ${noteLevels.joinToString()}."
                )
            }
            if (note.target.isBlank()) {
                issues += TargetManifestContractIssue("error", "MAPPING_NOTE_TARGET_BLANK", "$notePath.target", "Mapping note target must be present.")
            }
            if (note.nodeId.isBlank()) {
                issues += TargetManifestContractIssue("error", "MAPPING_NOTE_NODE_BLANK", "$notePath.nodeId", "Mapping note nodeId must be present.")
            }
            if (note.feature.isBlank()) {
                issues += TargetManifestContractIssue("error", "MAPPING_NOTE_FEATURE_BLANK", "$notePath.feature", "Mapping note feature must be present.")
            }
            if (note.message.isBlank()) {
                issues += TargetManifestContractIssue("error", "MAPPING_NOTE_MESSAGE_BLANK", "$notePath.message", "Mapping note message must be present.")
            }
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
