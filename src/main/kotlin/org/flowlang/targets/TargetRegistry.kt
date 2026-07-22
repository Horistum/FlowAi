package org.flowlang.targets

import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.capabilities.TargetExpressionSupportDeclaration
import org.flowlang.serialization.FlowYaml
import org.flowlang.standard.FlowStandardVersions
import java.io.File

/**
 * Single loading boundary for the versioned target capability registry.
 * Registry documents are declarative evidence; missing or inconsistent evidence
 * fails closed before manifest generation.
 */
object TargetRegistryYamlLoader {
    fun load(file: File): TargetRegistryDocument = FlowYaml.read(file, TargetRegistryDocument::class.java)

    fun loadDirectory(dir: File): Map<String, TargetCapability> {
        if (!dir.isDirectory) return emptyMap()
        val docs = dir.listFiles { file ->
            file.isFile && (file.extension == "yaml" || file.extension == "yml")
        }?.sortedBy { it.name } ?: emptyList()

        val out = linkedMapOf<String, TargetCapability>()
        docs.forEach { file ->
            val document = load(file)
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
                require(descriptor.topology != null) {
                    "Target '${descriptor.name}' must declare topology evidence in ${file.path}; missing topology evidence fails closed."
                }
                require(descriptor.name !in out) {
                    "Target '${descriptor.name}' is declared more than once across target registry files."
                }
                out[descriptor.name] = descriptor.toCapability(profiles)
            }
        }
        return out
    }

    private fun expressionProfiles(
        document: TargetRegistryDocument,
        file: File
    ): Map<String, TargetExpressionSupportDeclaration> {
        val profiles = linkedMapOf<String, TargetExpressionSupportDeclaration>()
        document.expressionProfiles.forEach { descriptor ->
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
