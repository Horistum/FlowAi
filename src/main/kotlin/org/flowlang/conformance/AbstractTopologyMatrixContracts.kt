package org.flowlang.conformance

import java.io.File
import org.flowlang.serialization.FlowYaml
import org.flowlang.topology.ExecutionTopologyDecisionStatus
import org.flowlang.topology.ExecutionTopologyKind

enum class AbstractTopologyMatrixFixture(val documentValue: String) {
    SEQUENTIAL("sequential"),
    PARALLEL("parallel"),
    RETRY("retry"),
    APPROVAL("approval"),
    FAILURE_HANDLER("failure-handler"),
    VALUE_CONTINUITY("value-continuity"),
    WORKSPACE_CONTINUITY("workspace-continuity"),
    STATE_CONTINUITY("state-continuity"),
    DURABLE_STATE_CONTINUITY("durable-state-continuity");

    companion object {
        fun parse(value: String): AbstractTopologyMatrixFixture =
            entries.singleOrNull { it.documentValue == value }
                ?: error("Unknown abstract topology fixture '$value'.")
    }
}

enum class AbstractTopologyMatrixMutation(val documentValue: String) {
    MISSING("missing"),
    PARTIAL("partial"),
    UNSUPPORTED("unsupported"),
    UNKNOWN("unknown"),
    CONTRADICTORY("contradictory");

    companion object {
        fun parse(value: String): AbstractTopologyMatrixMutation =
            entries.singleOrNull { it.documentValue == value }
                ?: error("Unknown abstract topology mutation '$value'.")
    }
}

data class AbstractTopologyMatrixCase(
    val id: String,
    val fixture: AbstractTopologyMatrixFixture,
    val requiredKinds: List<ExecutionTopologyKind>
) {
    init {
        require(id.isNotBlank()) { "Abstract topology matrix case id must not be blank." }
        require(requiredKinds.isNotEmpty()) { "Abstract topology matrix case '$id' must declare requiredKinds." }
        require(requiredKinds.size == requiredKinds.toSet().size) {
            "Abstract topology matrix case '$id' contains duplicate requiredKinds."
        }
    }
}

data class AbstractTopologyConcreteReference(
    val id: String,
    val target: String,
    val intent: String,
    val expectedTopologyDecision: ExecutionTopologyDecisionStatus,
    val executableSnapshot: String
) {
    init {
        require(id.isNotBlank()) { "Concrete topology reference id must not be blank." }
        require(target.isNotBlank()) { "Concrete topology reference target must not be blank." }
        require(intent.isNotBlank()) { "Concrete topology reference intent must not be blank." }
        require(executableSnapshot.isNotBlank()) { "Concrete topology reference snapshot must not be blank." }
    }
}

data class AbstractTopologyMatrixDocument(
    val version: String,
    val mutations: List<AbstractTopologyMatrixMutation>,
    val cases: List<AbstractTopologyMatrixCase>,
    val concreteReferences: List<AbstractTopologyConcreteReference>
) {
    init {
        require(version == VERSION) { "Abstract topology matrix version '$version' is unsupported; expected '$VERSION'." }
        require(mutations == AbstractTopologyMatrixMutation.entries.toList()) {
            "Abstract topology matrix mutations must be exactly ${AbstractTopologyMatrixMutation.entries.map { it.documentValue }}."
        }
        require(cases.size == AbstractTopologyMatrixFixture.entries.size) {
            "Abstract topology matrix must declare exactly one case per fixture."
        }
        require(cases.map(AbstractTopologyMatrixCase::fixture).toSet() == AbstractTopologyMatrixFixture.entries.toSet()) {
            "Abstract topology matrix fixture set is incomplete or duplicated."
        }
        require(cases.map(AbstractTopologyMatrixCase::id).size == cases.map(AbstractTopologyMatrixCase::id).toSet().size) {
            "Abstract topology matrix case ids must be unique."
        }
        require(concreteReferences.map(AbstractTopologyConcreteReference::id).size == concreteReferences.map(AbstractTopologyConcreteReference::id).toSet().size) {
            "Concrete topology reference ids must be unique."
        }
        require(concreteReferences.map(AbstractTopologyConcreteReference::target).toSet() == REQUIRED_REFERENCE_TARGETS) {
            "Concrete topology references must be exactly ${REQUIRED_REFERENCE_TARGETS.sorted()}."
        }
    }

    companion object {
        const val VERSION = "1.0"
        val REQUIRED_REFERENCE_TARGETS = setOf("jenkins", "github-actions")
    }
}

object AbstractTopologyMatrixLoader {
    const val PATH = "conformance/topology/abstract-topology-matrix.yaml"
    private val ROOT_KEYS = setOf("version", "mutations", "cases", "concreteReferences")
    private val CASE_KEYS = setOf("id", "fixture", "requiredKinds")
    private val REFERENCE_KEYS = setOf("id", "target", "intent", "expectedTopologyDecision", "executableSnapshot")

    fun load(rootDir: File = File(".")): AbstractTopologyMatrixDocument {
        val file = File(rootDir, PATH)
        require(file.isFile) { "Abstract topology matrix is missing: ${file.path}" }
        val yaml = FlowYaml.readMap(file)
        requireExactKeys(yaml, ROOT_KEYS, PATH)
        val mutations = yaml.stringList("mutations", PATH).map(AbstractTopologyMatrixMutation::parse)
        val cases = yaml.mapList("cases", PATH).mapIndexed { index, raw ->
            val path = "$PATH.cases[$index]"
            requireExactKeys(raw, CASE_KEYS, path)
            AbstractTopologyMatrixCase(
                id = raw.requiredString("id", path),
                fixture = AbstractTopologyMatrixFixture.parse(raw.requiredString("fixture", path)),
                requiredKinds = raw.stringList("requiredKinds", path).map { key ->
                    ExecutionTopologyKind.fromRegistryKey(key)
                        ?: error("$path.requiredKinds contains unknown topology kind '$key'.")
                }
            )
        }
        val references = yaml.mapList("concreteReferences", PATH).mapIndexed { index, raw ->
            val path = "$PATH.concreteReferences[$index]"
            requireExactKeys(raw, REFERENCE_KEYS, path)
            val decision = raw.requiredString("expectedTopologyDecision", path)
            AbstractTopologyConcreteReference(
                id = raw.requiredString("id", path),
                target = raw.requiredString("target", path),
                intent = raw.requiredString("intent", path),
                expectedTopologyDecision = runCatching { ExecutionTopologyDecisionStatus.valueOf(decision) }
                    .getOrElse { error("$path.expectedTopologyDecision '$decision' is unsupported.") },
                executableSnapshot = raw.requiredString("executableSnapshot", path)
            )
        }
        return AbstractTopologyMatrixDocument(
            version = yaml.requiredString("version", PATH),
            mutations = mutations,
            cases = cases,
            concreteReferences = references
        )
    }

    private fun requireExactKeys(raw: Map<String, Any?>, expected: Set<String>, path: String) {
        val unknown = raw.keys - expected
        val missing = expected - raw.keys
        require(unknown.isEmpty()) { "$path has unknown fields: ${unknown.sorted()}." }
        require(missing.isEmpty()) { "$path is missing fields: ${missing.sorted()}." }
    }

    private fun Map<String, Any?>.requiredString(key: String, path: String): String =
        (get(key) as? String)?.takeIf(String::isNotBlank)
            ?: error("$path.$key must be non-blank text.")

    private fun Map<String, Any?>.stringList(key: String, path: String): List<String> =
        (get(key) as? Iterable<*>)?.mapIndexed { index, value ->
            (value as? String)?.takeIf(String::isNotBlank)
                ?: error("$path.$key[$index] must be non-blank text.")
        } ?: error("$path.$key must be a list.")

    private fun Map<String, Any?>.mapList(key: String, path: String): List<Map<String, Any?>> =
        (get(key) as? Iterable<*>)?.mapIndexed { index, value ->
            (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
                ?: error("$path.$key[$index] must be a mapping.")
        } ?: error("$path.$key must be a list.")
}
