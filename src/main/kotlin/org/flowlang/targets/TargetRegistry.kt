package org.flowlang.targets

import org.flowlang.io.BoundedIo
import org.flowlang.io.InputLimits
import org.flowlang.io.IoBudget

import java.io.File
import org.flowlang.adapters.topology.AdapterTopologyEvidenceLoader
import org.flowlang.adapters.topology.AdapterTopologyProfileFactory
import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.capabilities.TargetExpressionSupportDeclaration
import org.flowlang.serialization.FlowYaml
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.topology.ExecutionTopologyProfile

/**
 * Single loading boundary for the versioned target capability registry.
 * Registry documents are declarative evidence; missing or inconsistent evidence
 * fails closed before manifest generation.
 *
 * Adapter topology evidence is distribution-owned. When the adapter topology
 * manifest is present beside the target registry, it is the sole runtime source
 * for ExecutionTopologyProfile. Legacy inline topology blocks remain parseable
 * for isolated fixtures but cannot override distribution evidence.
 */
object TargetRegistryYamlLoader {
    fun load(file: File): TargetRegistryDocument {
        val document = FlowYaml.readStrict(file, TargetRegistryDocument::class.java)
        validateDocument(document, file)
        return document
    }

    fun loadDirectory(dir: File): Map<String, TargetCapability> =
        loadDirectory(dir) { it.path.replace(File.separatorChar, '/') }

    /** Logical identities let packaged callers retain source provenance after a temporary snapshot is removed. */
    fun loadDirectory(dir: File, sourceIdentity: (File) -> String): Map<String, TargetCapability> {
        if (!dir.isDirectory) return emptyMap()
        val docs = BoundedIo.files(dir) { file ->
            file.isFile && (file.extension == "yaml" || file.extension == "yml")
        }
        val budget = IoBudget(InputLimits.MAX_TOTAL_INPUT_BYTES, code = "INPUT")
        val topologyProfiles = adapterTopologyProfiles(dir)

        val out = linkedMapOf<String, TargetCapability>()
        val identities = mutableSetOf<String>()
        docs.forEach { file ->
            val bytes = BoundedIo.readBytes(file, minOf(InputLimits.MAX_SOURCE_BYTES, budget.remainingBytes()))
            budget.add(bytes.size)
            val document = FlowYaml.readStrict(BoundedIo.decodeUtf8(bytes), TargetRegistryDocument::class.java, file.path)
            validateDocument(document, file)
            val identity = sourceIdentity(file)
            require(identity.isNotBlank() && '#' !in identity && identities.add(identity)) {
                "Target registry source identities must be non-blank, fragment-free and unique."
            }
            val profiles = expressionProfiles(document, file, identity)
            document.targets.forEach { descriptor ->
                require(topologyProfiles.isNotEmpty() || descriptor.topology != null) {
                    "Target '${descriptor.name}' must declare topology evidence in ${file.path}; missing topology evidence fails closed."
                }
                require(descriptor.name !in out) {
                    "Target '${descriptor.name}' is declared more than once across target registry files."
                }
                val topology = topologyProfiles[descriptor.name]
                    ?: descriptor.topology?.toProfile(descriptor.name)
                require(topology != null) {
                    "Adapter topology evidence does not declare target '${descriptor.name}'."
                }
                out[descriptor.name] = descriptor.toCapability(profiles, topology)
            }
        }
        if (topologyProfiles.isNotEmpty()) {
            val undeclaredProfiles = topologyProfiles.keys - out.keys
            require(undeclaredProfiles.isEmpty()) {
                "Adapter topology evidence declares unknown targets: ${undeclaredProfiles.sorted().joinToString()}."
            }
        }
        return out
    }

    private fun validateDocument(document: TargetRegistryDocument, file: File) {
        require(document.kind == "FlowTargetRegistry") {
            "Invalid target registry kind '${document.kind}' in ${file.path}."
        }
        require(document.version == FlowStandardVersions.TARGET_REGISTRY_VERSION) {
            "Target registry '${file.path}' declares version '${document.version}', expected '${FlowStandardVersions.TARGET_REGISTRY_VERSION}'."
        }
        val profiles = expressionProfiles(document, file)
        document.targets.forEach { descriptor ->
            require(descriptor.name.isNotBlank()) { "Target name must not be blank in ${file.path}." }
            require(descriptor.expressionProfile?.isNotBlank() == true) {
                "Target '${descriptor.name}' must declare expressionProfile in ${file.path}; missing expression evidence fails closed."
            }
            require(descriptor.expressionProfile in profiles) {
                "Unknown expression profile '${descriptor.expressionProfile}' for target '${descriptor.name}' in ${file.path}."
            }
            val unknownCapabilities = descriptor.capabilities.keys - TargetRegistryContractVocabulary.capabilityNames
            require(unknownCapabilities.isEmpty()) {
                "Target '${descriptor.name}' declares unknown capabilities ${unknownCapabilities.sorted()} in ${file.path}."
            }
            descriptor.capabilities.forEach { (name, raw) ->
                require(raw in TargetRegistryContractVocabulary.supportLevels) {
                    "Unknown support level '$raw' for capability '$name' on target '${descriptor.name}'."
                }
            }
            descriptor.features.forEach { (name, raw) ->
                require(name.isNotBlank()) { "Target '${descriptor.name}' contains a blank feature name in ${file.path}." }
                require(raw in TargetRegistryContractVocabulary.supportLevels) {
                    "Unknown support level '$raw' for feature '$name' on target '${descriptor.name}'."
                }
            }
            descriptor.projectionRules.forEach { it.toRule(descriptor.name) }
            descriptor.topology?.toProfile(descriptor.name)
        }
    }

    private fun adapterTopologyProfiles(dir: File): Map<String, ExecutionTopologyProfile> {
        val root = dir.absoluteFile.parentFile ?: return emptyMap()
        val evidenceFile = File(root, AdapterTopologyEvidenceLoader.PATH)
        if (!evidenceFile.isFile) return emptyMap()
        return AdapterTopologyProfileFactory.profiles(AdapterTopologyEvidenceLoader.load(root))
    }

    private fun expressionProfiles(
        document: TargetRegistryDocument,
        file: File,
        sourceIdentity: String = file.path.replace(File.separatorChar, '/')
    ): Map<String, TargetExpressionSupportDeclaration> {
        val profiles = linkedMapOf<String, TargetExpressionSupportDeclaration>()
        document.expressionProfiles.forEach { descriptor ->
            require(descriptor.id.isNotBlank()) { "Expression profile id must not be blank in ${file.path}." }
            require(descriptor.description.isNotBlank()) {
                "Expression profile '${descriptor.id}' must declare a description in ${file.path}."
            }
            require(descriptor.features.distinct().size == descriptor.features.size) {
                "Expression profile '${descriptor.id}' contains duplicate features in ${file.path}."
            }
            require(descriptor.id !in profiles) {
                "Expression profile '${descriptor.id}' is duplicated in ${file.path}."
            }
            val reference = sourceIdentity + "#expressionProfiles.${descriptor.id}"
            val declaration = descriptor.toDeclaration(reference)
            TargetExpressionSupport.declarationValidationReason(declaration)?.let { reason ->
                error("Invalid expression profile '${descriptor.id}' in ${file.path}: $reason")
            }
            profiles[descriptor.id] = declaration
        }
        return profiles
    }
}
