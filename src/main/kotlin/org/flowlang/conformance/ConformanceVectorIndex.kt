package org.flowlang.conformance

import org.flowlang.serialization.FlowYaml
import org.flowlang.standard.FlowStandardVersions
import java.io.File

data class ConformanceVectorIndexEntry(
    val path: String,
    val area: String,
    val id: String,
    val introducedIn: String,
    val requiredCheck: String,
    val hasRequiredCheck: Boolean
)

data class ConformanceVectorIndexReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val indexVersion: String = "1.0",
    val status: String,
    val vectorCount: Int,
    val entries: List<ConformanceVectorIndexEntry>,
    val requiredChecksFromVectors: List<String>,
    val vectorsMissingRequiredCheck: List<String>,
    val checksMissingFromRunner: List<String>,
    val releaseProfileChecksMissingVector: List<String>
)

/**
 * Builds a data-driven index from public conformance vector YAML files.
 *
 * The Kotlin runner remains the reference implementation, but the public vector
 * files become auditable inputs instead of passive documentation.
 */
class ConformanceVectorIndexBuilder(private val rootDir: File = File(".")) {
    fun build(
        runnerChecks: Collection<String> = emptyList(),
        releaseProfileChecks: Collection<String> = emptyList()
    ): ConformanceVectorIndexReport {
        val entries = vectorFiles().map { file ->
            val rel = file.relativeTo(rootDir).path.replace(File.separatorChar, '/')
            val map = FlowYaml.readMap(file)
            val expected = map["expected"] as? Map<*, *>
            val legacyName = map["name"] as? String
            val requiredCheck = expected?.get("requiredCheck") as? String
            ConformanceVectorIndexEntry(
                path = rel,
                area = rel.removePrefix("conformance/").substringBefore('/'),
                id = map["id"] as? String ?: legacyName.orEmpty(),
                introducedIn = map["introducedIn"] as? String ?: "",
                requiredCheck = requiredCheck ?: legacyName.orEmpty(),
                hasRequiredCheck = !requiredCheck.isNullOrBlank() || !legacyName.isNullOrBlank()
            )
        }.sortedBy { it.path }

        val requiredChecks = entries.mapNotNull { it.requiredCheck.ifBlank { null } }.distinct().sorted()
        val runnerSet = runnerChecks.toSet()
        val releaseSet = releaseProfileChecks.toSet()
        val missingRequired = entries.filterNot { it.hasRequiredCheck }.map { it.path }.sorted()
        val missingRunner = if (runnerSet.isEmpty()) emptyList() else requiredChecks.filterNot { it in runnerSet }.sorted()
        val missingReleaseVector = if (releaseSet.isEmpty()) emptyList() else releaseSet.filterNot { it in requiredChecks }.sorted()
        val status = if (missingRequired.isEmpty() && missingRunner.isEmpty() && missingReleaseVector.isEmpty()) "PASS" else "FAIL"

        return ConformanceVectorIndexReport(
            status = status,
            vectorCount = entries.size,
            entries = entries,
            requiredChecksFromVectors = requiredChecks,
            vectorsMissingRequiredCheck = missingRequired,
            checksMissingFromRunner = missingRunner,
            releaseProfileChecksMissingVector = missingReleaseVector
        )
    }

    private fun vectorFiles(): List<File> {
        val dir = File(rootDir, "conformance")
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && (it.extension == "yaml" || it.extension == "yml") }
            .filter { it.readText().contains("kind: FlowConformanceVector") }
            .sortedBy { it.relativeTo(rootDir).path.replace(File.separatorChar, '/') }
            .toList()
    }
}
