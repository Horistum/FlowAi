package org.flowlang.conformance

import java.io.File
import java.security.MessageDigest
import org.flowlang.serialization.FlowYaml

enum class SemanticObservationKind(val documentValue: String) {
    EFFECT("effect"),
    RESULT_IDENTITY("result-identity"),
    RESULT_VALUE("result-value"),
    CONTINUITY("continuity");

    companion object {
        fun parse(value: String): SemanticObservationKind =
            entries.singleOrNull { it.documentValue == value }
                ?: error("Unknown semantic observation kind '$value'.")
    }
}

enum class SemanticEquivalenceMutation(val documentValue: String) {
    MISSING("missing"),
    WEAKENED("weakened"),
    UNKNOWN("unknown"),
    CONTRADICTORY("contradictory");

    companion object {
        fun parse(value: String): SemanticEquivalenceMutation =
            entries.singleOrNull { it.documentValue == value }
                ?: error("Unknown semantic equivalence mutation '$value'.")
    }
}

enum class SemanticEquivalenceFixture(val documentValue: String) {
    EFFECT("effect"),
    RESULT_IDENTITY("result-identity"),
    RESULT_VALUE("result-value"),
    VALUE_CONTINUITY("value-continuity"),
    WORKSPACE_CONTINUITY("workspace-continuity"),
    STATE_CONTINUITY("state-continuity");

    companion object {
        fun parse(value: String): SemanticEquivalenceFixture =
            entries.singleOrNull { it.documentValue == value }
                ?: error("Unknown semantic equivalence fixture '$value'.")
    }
}

enum class SemanticObservationEvidenceStatus {
    PRESERVED,
    MISSING,
    WEAKENED,
    UNKNOWN,
    CONTRADICTORY
}

enum class SemanticEquivalenceDecisionStatus {
    EQUIVALENT,
    NOT_EQUIVALENT
}

data class SemanticObservationRequirement(
    val id: String,
    val kind: SemanticObservationKind,
    val subject: String,
    val value: String,
    val producerIdentity: String? = null,
    val consumerIdentity: String? = null
) {
    init {
        require(subject.isNotBlank()) { "Semantic observation requirement subject must not be blank." }
        require(value.isNotBlank()) { "Semantic observation requirement value must not be blank." }
        val expectedId = SemanticObservationIdentity.requirementId(
            kind,
            subject,
            value,
            producerIdentity,
            consumerIdentity
        )
        require(id == expectedId) {
            "Semantic observation requirement id '$id' must equal derived id '$expectedId'."
        }
    }

    val fingerprint: String = SemanticObservationIdentity.fingerprint(
        kind.name,
        subject,
        value,
        producerIdentity,
        consumerIdentity
    )
}

data class SemanticObservationEvidence(
    val requirementId: String,
    val fingerprint: String,
    val status: SemanticObservationEvidenceStatus,
    val evidenceReference: String
) {
    init {
        require(requirementId.isNotBlank()) { "Semantic observation evidence requirementId must not be blank." }
        require(fingerprint.matches(SHA_256_PATTERN)) {
            "Semantic observation evidence '$requirementId' fingerprint must be a lowercase SHA-256 digest."
        }
        require(status != SemanticObservationEvidenceStatus.MISSING) {
            "MISSING is derived from absent evidence and cannot be authored as an evidence record."
        }
        require(evidenceReference.isNotBlank()) { "Semantic observation evidence '$requirementId' reference must not be blank." }
    }

    companion object {
        private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

data class SemanticObservationAssessment(
    val requirement: SemanticObservationRequirement?,
    val status: SemanticObservationEvidenceStatus,
    val evidenceReferences: List<String>,
    val message: String? = null
)

data class SemanticEquivalenceDecision(
    val status: SemanticEquivalenceDecisionStatus,
    val requiredObservations: Int,
    val preservedObservations: Int,
    val blockingObservations: Int
)

data class SemanticEquivalenceAssessment(
    val decision: SemanticEquivalenceDecision,
    val observations: List<SemanticObservationAssessment>
)

data class SemanticEquivalenceCase(
    val id: String,
    val fixture: SemanticEquivalenceFixture,
    val requiredKinds: List<SemanticObservationKind>
) {
    init {
        require(id.isNotBlank()) { "Semantic equivalence case id must not be blank." }
        require(requiredKinds.isNotEmpty()) { "Semantic equivalence case '$id' must declare requiredKinds." }
        require(requiredKinds.size == requiredKinds.toSet().size) {
            "Semantic equivalence case '$id' contains duplicate requiredKinds."
        }
    }
}

data class SemanticEquivalenceConcretePair(
    val id: String,
    val intent: String,
    val leftTarget: String,
    val leftSnapshot: String,
    val rightTarget: String,
    val rightSnapshot: String
) {
    init {
        require(id.isNotBlank()) { "Semantic equivalence concrete pair id must not be blank." }
        require(intent.isNotBlank()) { "Semantic equivalence concrete pair '$id' intent must not be blank." }
        require(leftTarget.isNotBlank() && rightTarget.isNotBlank()) {
            "Semantic equivalence concrete pair '$id' targets must not be blank."
        }
        require(leftTarget != rightTarget) {
            "Semantic equivalence concrete pair '$id' must compare two different implementation targets."
        }
        require(leftSnapshot.isNotBlank() && rightSnapshot.isNotBlank()) {
            "Semantic equivalence concrete pair '$id' snapshots must not be blank."
        }
    }
}

data class SemanticEquivalenceDocument(
    val version: String,
    val mutations: List<SemanticEquivalenceMutation>,
    val cases: List<SemanticEquivalenceCase>,
    val concretePairs: List<SemanticEquivalenceConcretePair>
) {
    init {
        require(version == VERSION) {
            "Semantic equivalence rules version '$version' is unsupported; expected '$VERSION'."
        }
        require(mutations == SemanticEquivalenceMutation.entries.toList()) {
            "Semantic equivalence mutations must be exactly ${SemanticEquivalenceMutation.entries.map { it.documentValue }}."
        }
        require(cases.size == SemanticEquivalenceFixture.entries.size) {
            "Semantic equivalence rules must declare exactly one case per fixture."
        }
        require(cases.map(SemanticEquivalenceCase::fixture).toSet() == SemanticEquivalenceFixture.entries.toSet()) {
            "Semantic equivalence fixture set is incomplete or duplicated."
        }
        require(cases.map(SemanticEquivalenceCase::id).size == cases.map(SemanticEquivalenceCase::id).toSet().size) {
            "Semantic equivalence case ids must be unique."
        }
        require(concretePairs.size == 1) {
            "Semantic equivalence rules must declare exactly one bounded concrete implementation pair."
        }
        require(concretePairs.map(SemanticEquivalenceConcretePair::id).size == concretePairs.map(SemanticEquivalenceConcretePair::id).toSet().size) {
            "Semantic equivalence concrete pair ids must be unique."
        }
        require(setOf(concretePairs.single().leftTarget, concretePairs.single().rightTarget) == REQUIRED_REFERENCE_TARGETS) {
            "Semantic equivalence concrete pair must compare exactly ${REQUIRED_REFERENCE_TARGETS.sorted()}."
        }
    }

    companion object {
        const val VERSION = "1.0"
        val REQUIRED_REFERENCE_TARGETS = setOf("jenkins", "github-actions")
    }
}

object SemanticObservationIdentity {
    fun requirementId(
        kind: SemanticObservationKind,
        subject: String,
        value: String,
        producerIdentity: String? = null,
        consumerIdentity: String? = null
    ): String = "semantic.${kind.documentValue}.${fingerprint(kind.name, subject, value, producerIdentity, consumerIdentity).take(20)}"

    fun fingerprint(vararg parts: String?): String {
        val canonical = parts.joinToString(separator = "|") { part ->
            val value = part.orEmpty()
            "${value.length}:$value"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
    }
}

object SemanticEquivalenceLoader {
    const val PATH = "conformance/equivalence/semantic-equivalence-rules.yaml"
    private val ROOT_KEYS = setOf("version", "mutations", "cases", "concretePairs")
    private val CASE_KEYS = setOf("id", "fixture", "requiredKinds")
    private val PAIR_KEYS = setOf("id", "intent", "leftTarget", "leftSnapshot", "rightTarget", "rightSnapshot")

    fun load(rootDir: File = File(".")): SemanticEquivalenceDocument {
        val file = File(rootDir, PATH)
        require(file.isFile) { "Semantic equivalence rules are missing: ${file.path}" }
        val yaml = FlowYaml.readMap(file)
        requireExactKeys(yaml, ROOT_KEYS, PATH)
        val mutations = yaml.stringList("mutations", PATH).map(SemanticEquivalenceMutation::parse)
        val cases = yaml.mapList("cases", PATH).mapIndexed { index, raw ->
            val path = "$PATH.cases[$index]"
            requireExactKeys(raw, CASE_KEYS, path)
            SemanticEquivalenceCase(
                id = raw.requiredString("id", path),
                fixture = SemanticEquivalenceFixture.parse(raw.requiredString("fixture", path)),
                requiredKinds = raw.stringList("requiredKinds", path).map(SemanticObservationKind::parse)
            )
        }
        val concretePairs = yaml.mapList("concretePairs", PATH).mapIndexed { index, raw ->
            val path = "$PATH.concretePairs[$index]"
            requireExactKeys(raw, PAIR_KEYS, path)
            SemanticEquivalenceConcretePair(
                id = raw.requiredString("id", path),
                intent = raw.requiredString("intent", path),
                leftTarget = raw.requiredString("leftTarget", path),
                leftSnapshot = raw.requiredString("leftSnapshot", path),
                rightTarget = raw.requiredString("rightTarget", path),
                rightSnapshot = raw.requiredString("rightSnapshot", path)
            )
        }
        return SemanticEquivalenceDocument(
            version = yaml.requiredString("version", PATH),
            mutations = mutations,
            cases = cases,
            concretePairs = concretePairs
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
