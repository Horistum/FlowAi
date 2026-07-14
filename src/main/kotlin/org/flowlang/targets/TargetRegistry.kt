package org.flowlang.targets

import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetExpressionEvidenceKind
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.capabilities.TargetExpressionSupportDeclaration
import org.flowlang.capabilities.TargetProjectionMode
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.capabilities.TargetRendererPayloadKind
import org.flowlang.capabilities.TargetRendererPayloadTemplate
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.serialization.FlowYaml
import java.io.File

/**
 * Versioned target capability registry.
 *
 * This makes target support data a first-class standard artifact instead of
 * hardcoding all lifecycle assumptions in Kotlin. Generators and validators
 * should consume this registry before pretending that a target can represent a
 * Flow Execution Plan. Humanity has produced enough optimistic YAML already.
 */
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
    val features: Set<String> = emptySet()
) {
    fun toDeclaration(reference: String): TargetExpressionSupportDeclaration =
        TargetExpressionSupportDeclaration(
            profileId = id,
            evidenceKind = TargetExpressionEvidenceKind.TARGET_REGISTRY,
            evidenceReference = reference,
            supportsAll = supportsAll,
            features = features
        )
}

data class TargetProjectionPayloadDescriptor(
    val kind: String = "",
    val reference: String = "",
    val parameters: Map<String, String> = emptyMap()
) {
    fun toTemplate(targetName: String): TargetRendererPayloadTemplate {
        val parsedKind = when (kind.trim().lowercase().replace('-', '_')) {
            "jenkins_step" -> TargetRendererPayloadKind.JENKINS_STEP
            "github_action" -> TargetRendererPayloadKind.GITHUB_ACTION
            "tekton_task" -> TargetRendererPayloadKind.TEKTON_TASK
            else -> error("Unknown renderer payload kind '$kind' for target '$targetName'.")
        }
        require(reference.isNotBlank()) { "Projection payload reference must not be blank for target '$targetName'." }
        return TargetRendererPayloadTemplate(parsedKind, reference, parameters)
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
        require(module.isNotBlank() && action.isNotBlank()) { "Projection rule for target '$targetName' must declare module and action." }
        require(reason.isNotBlank()) { "Projection rule '$module.$action' for target '$targetName' must explain its decision." }
        require(evidenceReference.isNotBlank()) { "Projection rule '$module.$action' for target '$targetName' must cite evidence." }
        val parsedMode = when (mode.trim().lowercase().replace('-', '_')) {
            "native" -> TargetProjectionMode.NATIVE
            "notes_projected" -> TargetProjectionMode.NOTES_PROJECTED
            "adapter_required" -> TargetProjectionMode.ADAPTER_REQUIRED
            "unsupported" -> TargetProjectionMode.UNSUPPORTED
            "blocked" -> TargetProjectionMode.BLOCKED
            else -> error("Unknown projection mode '$mode' for '$module.$action' on target '$targetName'.")
        }
        if (parsedMode == TargetProjectionMode.NATIVE) require(payload != null) {
            "Native projection rule '$module.$action' for target '$targetName' must declare a renderer payload."
        }
        if (parsedMode != TargetProjectionMode.NATIVE) require(payload == null) {
            "Only native projection rules may declare renderer payloads ('$module.$action' on '$targetName')."
        }
        return TargetProjectionRule(module, action, parsedMode, reason, evidenceReference, payload?.toTemplate(targetName))
    }
}

data class TargetDescriptor(
    val name: String = "",
    val description: String = "",
    val capabilities: Map<String, String> = emptyMap(),
    val notes: List<String> = emptyList(),
    val features: Map<String, String> = emptyMap(),
    val expressionProfile: String? = null,
    val projectionRules: List<TargetProjectionRuleDescriptor> = emptyList()
) {
    fun toCapability(expressionProfiles: Map<String, TargetExpressionSupportDeclaration> = emptyMap()): TargetCapability {
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
            projectionRules = projectionRules.map { it.toRule(name) }
        )
    }

    private fun support(name: String, default: SupportLevel): SupportLevel {
        val raw = capabilities[name] ?: return default
        return parseSupport(raw, this.name, name)
    }

    private fun parseSupport(raw: String, targetName: String, featureName: String = "feature"): SupportLevel = when (raw.trim().lowercase().replace('-', '_')) {
        "supported", "full", "yes", "true" -> SupportLevel.SUPPORTED
        "partial", "limited", "supported_with_mapping" -> SupportLevel.PARTIAL
        "unsupported", "none", "no", "false" -> SupportLevel.UNSUPPORTED
        "requires_runtime", "requiresruntime", "runtime" -> SupportLevel.REQUIRES_RUNTIME
        else -> error("Unknown support level '$raw' for capability '$featureName' on target '$targetName'.")
    }
}

object TargetRegistryYamlLoader {
    fun load(file: File): TargetRegistryDocument = FlowYaml.read(file, TargetRegistryDocument::class.java)

    fun loadDirectory(dir: File): Map<String, TargetCapability> {
        if (!dir.isDirectory) return emptyMap()
        val docs = dir.listFiles { f -> f.isFile && (f.extension == "yaml" || f.extension == "yml") }
            ?.sortedBy { it.name }
            ?: emptyList()
        val out = linkedMapOf<String, TargetCapability>()
        docs.forEach { file ->
            val doc = load(file)
            require(doc.kind == "FlowTargetRegistry") { "Invalid target registry kind '${doc.kind}' in ${file.path}." }
            require(doc.version == FlowStandardVersions.TARGET_REGISTRY_VERSION) {
                "Target registry '${file.path}' declares version '${doc.version}', expected '${FlowStandardVersions.TARGET_REGISTRY_VERSION}'."
            }
            val profiles = expressionProfiles(doc, file)
            doc.targets.forEach { descriptor ->
                require(descriptor.name.isNotBlank()) { "Target name must not be blank in ${file.path}." }
                require(descriptor.expressionProfile?.isNotBlank() == true) {
                    "Target '${descriptor.name}' must declare expressionProfile in ${file.path}; missing expression evidence fails closed."
                }
                require(descriptor.name !in out) { "Target '${descriptor.name}' is declared more than once across target registry files." }
                out[descriptor.name] = descriptor.toCapability(profiles)
            }
        }
        return out
    }

    private fun expressionProfiles(
        doc: TargetRegistryDocument,
        file: File
    ): Map<String, TargetExpressionSupportDeclaration> {
        val profiles = linkedMapOf<String, TargetExpressionSupportDeclaration>()
        doc.expressionProfiles.forEach { descriptor ->
            require(descriptor.id.isNotBlank()) { "Expression profile id must not be blank in ${file.path}." }
            require(descriptor.description.isNotBlank()) {
                "Expression profile '${descriptor.id}' must declare a description in ${file.path}."
            }
            require(descriptor.id !in profiles) {
                "Expression profile '${descriptor.id}' is duplicated in ${file.path}."
            }
            val reference = file.path.replace(File.separatorChar, '/') + "#expressionProfiles.${descriptor.id}"
            val declaration = descriptor.toDeclaration(reference)
            TargetExpressionSupport.declarationValidationReason(declaration)?.let { reason ->
                error("Invalid expression profile '${descriptor.id}' in ${file.path}: $reason")
            }
            profiles[descriptor.id] = declaration
        }
        return profiles
    }
}
