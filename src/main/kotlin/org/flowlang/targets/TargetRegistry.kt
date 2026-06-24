package org.flowlang.targets

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
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
    val version: String = "1.0",
    val description: String? = null,
    val targets: List<TargetDescriptor> = emptyList()
)

data class TargetDescriptor(
    val name: String = "",
    val description: String = "",
    val capabilities: Map<String, String> = emptyMap(),
    val notes: List<String> = emptyList(),
    val features: Map<String, String> = emptyMap()
) {
    fun toCapability(): TargetCapability = TargetCapability(
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
        features = features.mapValues { (_, raw) -> parseSupport(raw, name) }
    )

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
    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(file: File): TargetRegistryDocument = mapper.readValue(file, TargetRegistryDocument::class.java)

    fun loadDirectory(dir: File): Map<String, TargetCapability> {
        if (!dir.isDirectory) return emptyMap()
        val docs = dir.listFiles { f -> f.isFile && (f.extension == "yaml" || f.extension == "yml") }
            ?.sortedBy { it.name }
            ?: emptyList()
        val out = linkedMapOf<String, TargetCapability>()
        docs.forEach { file ->
            val doc = load(file)
            require(doc.kind == "FlowTargetRegistry") { "Invalid target registry kind '${doc.kind}' in ${file.path}." }
            doc.targets.forEach { descriptor ->
                require(descriptor.name.isNotBlank()) { "Target name must not be blank in ${file.path}." }
                out[descriptor.name] = descriptor.toCapability()
            }
        }
        return out
    }
}
