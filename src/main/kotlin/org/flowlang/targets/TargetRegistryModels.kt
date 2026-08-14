package org.flowlang.targets

import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetExpressionEvidenceKind
import org.flowlang.capabilities.TargetExpressionSupportDeclaration
import org.flowlang.capabilities.TargetProjectionMode
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.capabilities.TargetRendererPayloadTemplate
import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingContract
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologyProfile
import org.flowlang.topology.ExecutionTopologyProfileAuthority
import org.flowlang.topology.ExecutionTopologySupportDeclaration
import org.flowlang.topology.ExecutionTopologySupportStatus

/** Versioned declarative target registry document. */
data class TargetRegistryDocument(
    val kind: String = "FlowTargetRegistry",
    val version: String = FlowStandardVersions.TARGET_REGISTRY_VERSION,
    val description: String? = null,
    val expressionProfiles: List<TargetExpressionProfileDescriptor> = emptyList(),
    val targets: List<TargetDescriptor> = emptyList()
)

data class TargetExpressionProfileDescriptor(
    val id: String = "",
    val description: String = "",
    val supportsAll: Boolean = false,
    val features: List<String> = emptyList()
) {
    fun toDeclaration(reference: String): TargetExpressionSupportDeclaration =
        TargetExpressionSupportDeclaration(
            profileId = id,
            evidenceKind = TargetExpressionEvidenceKind.TARGET_REGISTRY,
            evidenceReference = reference,
            supportsAll = supportsAll,
            features = features.toSet()
        )
}

data class TargetProjectionPayloadDescriptor(
    val kind: String = "",
    val reference: String = "",
    val bindings: Map<String, ProjectionBinding> = emptyMap()
) {
    fun toTemplate(targetName: String): TargetRendererPayloadTemplate {
        val payloadKind = kind
        require(payloadKind.isNotBlank()) { "Projection payload kind must not be blank for target '$targetName'." }
        require(PAYLOAD_KIND.matches(payloadKind)) {
            "Projection payload kind '$kind' for target '$targetName' is not a valid opaque projection identifier."
        }
        require(reference.isNotBlank()) { "Projection payload reference must not be blank for target '$targetName'." }
        bindings.forEach { (name, binding) ->
            require(BINDING_NAME.matches(name)) {
                "Projection binding name '$name' for target '$targetName' is not renderer-safe."
            }
            ProjectionBindingContract.requireTemplate(binding, "$targetName.$payloadKind.bindings.$name")
        }
        return TargetRendererPayloadTemplate(
            kind = payloadKind.uppercase().replace('-', '_'),
            reference = reference,
            bindings = bindings
        )
    }

    companion object {
        private val PAYLOAD_KIND = Regex("^[A-Za-z0-9][A-Za-z0-9._+:/-]*$")
        private val BINDING_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    }
}

data class TargetProjectionRuleDescriptor(
    val module: String = "",
    val action: String = "",
    val mode: String = "adapter_required",
    val reason: String = "",
    val evidenceReference: String = "",
    val payload: TargetProjectionPayloadDescriptor? = null
) {
    fun toRule(targetName: String): TargetProjectionRule {
        require(module.isNotBlank() && action.isNotBlank()) {
            "Projection rule for target '$targetName' must declare module and action."
        }
        require(reason.isNotBlank()) {
            "Projection rule '$module.$action' for target '$targetName' must explain its decision."
        }
        require(evidenceReference.isNotBlank()) {
            "Projection rule '$module.$action' for target '$targetName' must cite evidence."
        }
        val parsedMode = TargetRegistryContractVocabulary.projectionModes[mode]
            ?: error("Unknown projection mode '$mode' for '$module.$action' on target '$targetName'.")
        if (parsedMode == TargetProjectionMode.NATIVE) require(payload != null) {
            "Native projection rule '$module.$action' for target '$targetName' must declare a renderer payload."
        }
        if (parsedMode != TargetProjectionMode.NATIVE) require(payload == null) {
            "Only native projection rules may declare renderer payloads ('$module.$action' on '$targetName')."
        }
        return TargetProjectionRule(
            module,
            action,
            parsedMode,
            reason,
            evidenceReference,
            payload?.toTemplate(targetName)
        )
    }
}

data class TargetTopologyProfileDescriptor(
    val evidenceReference: String = "",
    val capabilities: Map<String, String> = emptyMap()
) {
    fun toProfile(targetName: String): ExecutionTopologyProfile {
        require(evidenceReference.isNotBlank()) {
            "Target '$targetName' topology profile must declare an evidenceReference."
        }
        val unknown = capabilities.keys.filter { ExecutionTopologyKind.fromRegistryKey(it) == null }
        require(unknown.isEmpty()) {
            "Target '$targetName' topology profile declares unknown capabilities: ${unknown.sorted()}."
        }
        val declarations = capabilities.map { (key, raw) ->
            val kind = requireNotNull(ExecutionTopologyKind.fromRegistryKey(key))
            ExecutionTopologySupportDeclaration(
                kind = kind,
                status = TargetRegistryContractVocabulary.topologySupportLevels[raw]
                    ?: error("Unknown topology support '$raw' for '$key' on target '$targetName'."),
                evidenceReference = "$evidenceReference#$key"
            )
        }
        val profile = ExecutionTopologyProfile(targetName, declarations)
        ExecutionTopologyProfileAuthority.requireValid(profile)
        return profile
    }
}

data class TargetDescriptor(
    val name: String = "",
    val description: String = "",
    val capabilities: Map<String, String> = emptyMap(),
    val notes: List<String> = emptyList(),
    val features: Map<String, String> = emptyMap(),
    val expressionProfile: String? = null,
    val topology: TargetTopologyProfileDescriptor? = null,
    val projectionRules: List<TargetProjectionRuleDescriptor> = emptyList()
) {
    fun toCapability(
        expressionProfiles: Map<String, TargetExpressionSupportDeclaration> = emptyMap(),
        topologyProfile: ExecutionTopologyProfile? = null
    ): TargetCapability {
        val expressionDeclaration = expressionProfile?.let { profileId ->
            expressionProfiles[profileId]
                ?: error("Unknown expression profile '$profileId' for target '$name'.")
        }
        return TargetCapability(
            target = name,
            description = description,
            sequentialTasks = support("sequentialTasks", SupportLevel.SUPPORTED),
            parallel = support("parallel", SupportLevel.SUPPORTED),
            conditions = support("conditions", SupportLevel.SUPPORTED),
            dynamicLoops = support("dynamicLoops", SupportLevel.PARTIAL),
            match = support("match", SupportLevel.PARTIAL),
            retry = support("retry", SupportLevel.PARTIAL),
            approvals = support("approvals", SupportLevel.PARTIAL),
            errorHandlers = support("errorHandlers", SupportLevel.PARTIAL),
            artifacts = support("artifacts", SupportLevel.PARTIAL),
            secrets = support("secrets", SupportLevel.PARTIAL),
            nativeRuntime = support("nativeRuntime", SupportLevel.PARTIAL),
            notes = notes,
            features = features.mapValues { (_, raw) -> parseSupport(raw, name) },
            expressionSupport = expressionDeclaration,
            projectionRules = projectionRules.map { it.toRule(name) },
            topologyProfile = topologyProfile
                ?: requireNotNull(topology) { "Target '$name' must declare a complete topology profile." }.toProfile(name)
        )
    }

    private fun support(name: String, default: SupportLevel): SupportLevel {
        val raw = capabilities[name] ?: return default
        return parseSupport(raw, this.name, name)
    }

    private fun parseSupport(raw: String, targetName: String, featureName: String = "feature"): SupportLevel =
        TargetRegistryContractVocabulary.supportLevels[raw]
            ?: error("Unknown support level '$raw' for capability '$featureName' on target '$targetName'.")
}
