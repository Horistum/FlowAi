package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.capabilities.TargetRendererPayloadKind
import org.flowlang.planner.*
import org.flowlang.projection.ProjectionBinding
import org.flowlang.standard.FlowStandardVersions

/**
 * Platform-neutral generator contract output.
 *
 * TargetManifest is the auditable artifact between Flow ExecutionPlan and concrete target syntax.
 * Renderers render this manifest; they do not re-plan Flow. Structured typed bindings preserve
 * provenance and runtime references without command text or prefix-encoded pseudo-types.
 */
data class TargetManifest(
    val manifestVersion: String = FlowStandardVersions.TARGET_MANIFEST_VERSION,
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val target: String,
    val flowName: String,
    val compatibility: CompatibilityReport,
    val inputs: List<TargetInput> = emptyList(),
    val triggers: List<TargetTrigger> = emptyList(),
    val jobs: List<TargetJob> = emptyList(),
    val mappingNotes: List<TargetMappingNote> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

data class TargetMappingNote(
    val level: String,
    val target: String,
    val nodeId: String,
    val feature: String,
    val message: String
)

data class TargetTrigger(
    val id: String,
    val type: String,
    val workflows: List<String> = listOf("main"),
    val scheduleKind: String? = null,
    val scheduleExpression: String? = null,
    val timezone: String? = null,
    val event: String? = null,
    val params: Map<String, String> = emptyMap()
)

data class TargetInput(
    val name: String,
    val type: String = "text",
    val required: Boolean = false,
    val defaultValue: String? = null,
    val choices: List<String> = emptyList()
)

data class TargetJob(
    val id: String,
    val name: String = id,
    val dependsOn: List<String> = emptyList(),
    val steps: List<TargetStep> = emptyList(),
    val mappingNotes: List<TargetMappingNote> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

data class TargetRendererPayload(
    val kind: TargetRendererPayloadKind,
    val target: String,
    val reference: String,
    val bindings: Map<String, ProjectionBinding> = emptyMap(),
    val evidenceReference: String
)

data class TargetStep(
    val id: String,
    val name: String = id,
    val type: String,
    val module: String? = null,
    val action: String? = null,
    val target: String? = null,
    val materialization: TargetMaterialization = TargetMaterialization.semanticOnly("No materialization attached to this non-action projection step."),
    val rendererPayload: TargetRendererPayload? = null,
    val dependsOn: List<String> = emptyList(),
    val params: Map<String, String> = emptyMap(),
    val children: List<TargetStep> = emptyList(),
    val mappingNotes: List<TargetMappingNote> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

data class TargetMaterialization(
    val status: TargetMaterializationStatus,
    val capability: String,
    val reason: String,
    val requirements: Map<String, String> = emptyMap(),
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        fun native(capability: String, reason: String, metadata: Map<String, String> = emptyMap()): TargetMaterialization =
            TargetMaterialization(TargetMaterializationStatus.NATIVE, capability, reason, metadata = metadata)

        fun adapterRequired(capability: String, reason: String, requirements: Map<String, String> = emptyMap()): TargetMaterialization =
            TargetMaterialization(TargetMaterializationStatus.ADAPTER_REQUIRED, capability, reason, requirements)

        fun semanticOnly(reason: String, capability: String = "flow.semantic"): TargetMaterialization =
            TargetMaterialization(TargetMaterializationStatus.SEMANTIC_ONLY, capability, reason)

        fun blocked(capability: String, reason: String): TargetMaterialization =
            TargetMaterialization(TargetMaterializationStatus.BLOCKED, capability, reason)
    }
}
