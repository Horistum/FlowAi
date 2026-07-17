package org.flowlang.notes

import java.io.File
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

/** Loads the single Core-owned notes authority declared by standard/notes/packages.yaml. */
object CanonicalNotesPackageLoader {
    class ContractException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    fun load(rootDir: File = File(".")): List<NotesPackageContract> {
        val manifestFile = File(rootDir, "standard/notes/packages.yaml")
        if (!manifestFile.isFile) throw ContractException("Notes authority manifest is missing: ${manifestFile.path}")
        val manifest = readMap(manifestFile)
        val version = manifest["version"] as? Number
            ?: throw ContractException("${manifestFile.path}.version must be numeric.")
        if (version.toInt() != 1) throw ContractException("Unsupported notes authority version: $version")
        val sourceDirectory = text(manifest, "sourceDirectory", manifestFile.path)
        if (text(manifest, "ownership", manifestFile.path) != "core-semantic-and-safety-contracts") {
            throw ContractException("${manifestFile.path}.ownership must identify Core semantic and safety contracts.")
        }
        if (text(manifest, "adapterEvidence", manifestFile.path) != "targets") {
            throw ContractException("${manifestFile.path}.adapterEvidence must point to adapter-owned targets.")
        }

        val root = rootDir.canonicalFile
        val directory = File(rootDir, sourceDirectory).canonicalFile
        if (!directory.path.startsWith(root.path + File.separator)) {
            throw ContractException("Notes source directory escapes the repository root: $sourceDirectory")
        }
        if (!directory.isDirectory) throw ContractException("Notes source directory is missing: ${directory.path}")
        val files = directory.listFiles { file -> file.isFile && file.extension in setOf("yaml", "yml") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) throw ContractException("Notes source directory is empty: ${directory.path}")

        val contracts = files.map(::loadPackage)
        val duplicateIds = contracts.groupBy { it.packageId }.filterValues { it.size > 1 }.keys.sorted()
        if (duplicateIds.isNotEmpty()) throw ContractException("Duplicate notes package ids: ${duplicateIds.joinToString()}")
        val ids = contracts.map { it.packageId }.toSet()
        contracts.flatMap { contract ->
            contract.dependencies.mapNotNull { dependency ->
                dependency.packageId.takeUnless { it in ids }?.let { contract.packageId to it }
            }
        }.firstOrNull()?.let { (owner, missing) ->
            throw ContractException("Notes package '$owner' depends on unknown package '$missing'.")
        }

        val report = NotesPackageContractValidator().validateAll(contracts)
        if (!report.valid) {
            throw ContractException(report.issues.joinToString("; ") { "${it.code}:${it.packageId}" })
        }
        return contracts
    }

    private fun loadPackage(file: File): NotesPackageContract {
        val root = readMap(file)
        val unknown = root.keys - PACKAGE_KEYS
        if (unknown.isNotEmpty()) {
            throw ContractException("${file.path} has unknown fields: ${unknown.sorted().joinToString()}")
        }
        if (root.containsKey("targetCapabilities") || root.containsKey("projectionRules")) {
            throw ContractException("${file.path} cannot own adapter target or projection evidence.")
        }
        val kind = when (text(root, "kind", file.path)) {
            "domain" -> NotesPackageKind.DOMAIN
            "capability" -> NotesPackageKind.CAPABILITY
            "safety" -> NotesPackageKind.SAFETY
            "runtime" -> NotesPackageKind.RUNTIME
            "conformance" -> NotesPackageKind.CONFORMANCE
            "target", "projection" -> throw ContractException("${file.path} declares adapter-owned notes kind.")
            else -> throw ContractException("${file.path}.kind is not a supported Core notes kind.")
        }
        return NotesPackageContract(
            packageId = text(root, "packageId", file.path),
            packageVersion = text(root, "packageVersion", file.path),
            kind = kind,
            description = text(root, "description", file.path),
            declaredSemantics = strings(root["declaredSemantics"], "${file.path}.declaredSemantics").toSet(),
            declaredCapabilities = strings(root["declaredCapabilities"], "${file.path}.declaredCapabilities").toSet(),
            safetyPolicies = strings(root["safetyPolicies"], "${file.path}.safetyPolicies").toSet(),
            runtimeRequirements = strings(root["runtimeRequirements"], "${file.path}.runtimeRequirements").toSet(),
            conformanceChecks = strings(root["conformanceChecks"], "${file.path}.conformanceChecks").toSet(),
            dependencies = objectList(root["dependencies"], "${file.path}.dependencies").map { dependency ->
                NotesPackageDependency(
                    packageId = text(dependency, "packageId", "${file.path}.dependencies"),
                    versionConstraint = text(dependency, "versionConstraint", "${file.path}.dependencies")
                )
            }.toSet(),
            boundaries = objectList(root["boundaries"], "${file.path}.boundaries").map { boundary ->
                NotesPackageBoundary(
                    name = text(boundary, "name", "${file.path}.boundaries"),
                    description = text(boundary, "description", "${file.path}.boundaries")
                )
            }.toSet()
        )
    }

    private fun readMap(file: File): Map<String, Any?> = try {
        FlowYaml.readMap(file)
    } catch (error: FlowYamlException) {
        throw ContractException(error.message ?: "Invalid YAML: ${file.path}", error)
    }

    private fun text(map: Map<String, Any?>, key: String, path: String): String =
        map[key] as? String
            ?.takeIf { it.isNotBlank() }
            ?: throw ContractException("$path.$key must be non-blank text.")

    private fun strings(value: Any?, path: String): List<String> = when (value) {
        null -> emptyList()
        is List<*> -> value.mapIndexed { index, item ->
            item as? String ?: throw ContractException("$path[$index] must be text.")
        }.also { values -> if (values.any { it.isBlank() }) throw ContractException("$path contains blank text.") }
        else -> throw ContractException("$path must be a list.")
    }

    @Suppress("UNCHECKED_CAST")
    private fun objectList(value: Any?, path: String): List<Map<String, Any?>> = when (value) {
        null -> emptyList()
        is List<*> -> value.mapIndexed { index, item ->
            item as? Map<String, Any?> ?: throw ContractException("$path[$index] must be a map.")
        }
        else -> throw ContractException("$path must be a list.")
    }

    private val PACKAGE_KEYS = setOf(
        "packageId", "packageVersion", "kind", "description",
        "declaredSemantics", "declaredCapabilities", "safetyPolicies",
        "runtimeRequirements", "conformanceChecks", "dependencies", "boundaries",
        "targetCapabilities", "projectionRules"
    )
}
