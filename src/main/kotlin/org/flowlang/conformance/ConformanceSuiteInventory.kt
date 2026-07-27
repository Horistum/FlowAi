package org.flowlang.conformance

import java.io.File
import org.flowlang.serialization.FlowYaml

/**
 * Independent declaration of the complete conformance sequence that must run
 * before semantic closure. The runner produces evidence; this inventory proves
 * that the producer itself has not silently lost a check or an entire group.
 */
data class ConformanceSuiteInventory(
    val version: String,
    val closureCheck: String,
    val preClosureChecks: List<String>
) {
    init {
        require(version.isNotBlank()) { "Conformance suite inventory version must not be blank." }
        require(closureCheck.isNotBlank()) { "Conformance suite inventory closureCheck must not be blank." }
        require(preClosureChecks.isNotEmpty()) { "Conformance suite inventory must declare pre-closure checks." }
        require(preClosureChecks.none(String::isBlank)) { "Conformance suite inventory contains a blank check id." }
        require(preClosureChecks.size == preClosureChecks.toSet().size) {
            "Conformance suite inventory contains duplicate check ids."
        }
        require(closureCheck !in preClosureChecks) {
            "The final closure check must not appear in the pre-closure inventory."
        }
    }

    companion object {
        const val PATH = "standard/conformance/pre-closure-check-inventory.yaml"
        private val TOP_LEVEL_KEYS = setOf("version", "closureCheck", "preClosureChecks")

        fun load(rootDir: File = File(".")): ConformanceSuiteInventory {
            val file = File(rootDir, PATH)
            require(file.isFile) { "Conformance suite inventory is missing: ${file.path}" }
            val document = FlowYaml.readMap(file)
            val unknown = document.keys - TOP_LEVEL_KEYS
            require(unknown.isEmpty()) {
                "Conformance suite inventory contains unknown fields: ${unknown.sorted().joinToString()}"
            }
            val checks = (document["preClosureChecks"] as? Iterable<*>)
                ?.map { value -> value as? String ?: error("Conformance suite inventory check ids must be strings.") }
                ?: error("Conformance suite inventory preClosureChecks must be a list.")
            return ConformanceSuiteInventory(
                version = document["version"] as? String
                    ?: error("Conformance suite inventory version must be a string."),
                closureCheck = document["closureCheck"] as? String
                    ?: error("Conformance suite inventory closureCheck must be a string."),
                preClosureChecks = checks
            )
        }
    }
}
