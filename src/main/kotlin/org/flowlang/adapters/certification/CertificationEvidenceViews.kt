package org.flowlang.adapters.certification

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest
import java.util.Collections
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog

/** Occurrence coverage is deliberately separate from public SUPPORTED/UNSUPPORTED status. */
enum class CertificationOccurrenceStatus { OBSERVED_IN_SCENARIO, NOT_OBSERVED }
enum class CertificationScenarioShape { NATIVE_LEAF_ONLY, STRUCTURAL_OCCURRENCES, SEMANTIC_OCCURRENCES_ONLY }

data class CertificationCoverageView(
    val subject: CertificationSubject,
    val status: CertificationOccurrenceStatus,
    val scenarioIds: List<String>,
    val limitation: String
)

data class CertificationRunView(
    val runId: String,
    val mutantId: String?,
    val runnerId: String,
    val runnerKeySha256: String,
    val artifact: CertificationEvidenceReference,
    val expectedObservation: CertificationEvidenceReference,
    val observed: CertificationEvidenceReference
)

data class CertificationScenarioView(
    val id: String,
    val shape: CertificationScenarioShape,
    val source: CertificationEvidenceReference,
    val canonicalGraph: CertificationEvidenceReference,
    val runtimePrerequisites: List<CertificationRuntimePrerequisite>,
    val runs: List<CertificationRunView>
)

/**
 * A diagnostic snapshot, not an authorization token or a public support registry.
 * Only assess() produces this view; serialized copies cannot be used as admission inputs.
 */
class CertificationEvidenceView internal constructor(
    val adapter: CertificationAdapterIdentity,
    val assessmentChallenge: String,
    val coverage: List<CertificationCoverageView>,
    val scenarios: List<CertificationScenarioView>,
    val limitations: List<String>
) {
    val formatVersion: Int = 1
    val scope: String = "authenticated-bounded-observations"
    val publicSupportPromoted: Boolean = false
    val portableExecution: Boolean = false

    fun json(): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this) + "\n"

    fun markdown(): String = buildString {
        append("# Adapter observation evidence\n\n")
        append("Target: ${cell(adapter.target)}; adapter: ${cell(adapter.adapterId)}; version: ${cell(adapter.version)}.\n\n")
        append("Scope: authenticated bounded observations. Public support is not promoted; portable execution is not established.\n\n")
        append("An observed occurrence is not proof of general capability support or structural equivalence. ")
        append("NOT_OBSERVED means missing evidence in this assessment, not an unsupported target feature.\n\n")
        append("| Subject | Occurrence evidence | Scenarios | Limitation |\n| --- | --- | --- | --- |\n")
        coverage.forEach { row ->
            append("| ${cell(subjectName(row.subject))} | ${row.status} | ${cell(row.scenarioIds.joinToString(", "))} | ${cell(row.limitation)} |\n")
        }
        scenarios.forEach { scenario ->
            append("\n## ${cell(scenario.id)}\n\n")
            append("Shape: ${scenario.shape}. Source SHA-256: ${scenario.source.sha256}. Canonical graph SHA-256: ${scenario.canonicalGraph.sha256}.\n\n")
            append("| Runtime prerequisite | Version |\n| --- | --- |\n")
            scenario.runtimePrerequisites.forEach { append("| ${cell(it.id)} | ${cell(it.version)} |\n") }
            append("\n| Run | Role | Runner | Artifact SHA-256 | Expected / observed SHA-256 |\n| --- | --- | --- | --- | --- |\n")
            scenario.runs.forEach { run ->
                append("| ${cell(run.runId)} | ${cell(run.mutantId ?: "baseline")} | ${cell(run.runnerId)} | ${run.artifact.sha256} | ${run.expectedObservation.sha256} / ${run.observed.sha256} |\n")
            }
        }
        append("\n## Limitations\n\n")
        limitations.forEach { append("- ${cell(it)}\n") }
        append("\nThe assessment owner supplies runner trust and behavioral oracles. A signature authenticates the observation; it does not establish runner honesty or oracle adequacy.\n")
    }

    companion object {
        private val mapper = jacksonObjectMapper()
        // Escape every Markdown/HTML metacharacter, including line boundaries in table cells.
        private fun cell(value: String): String = buildString {
            value.forEach { c ->
                when {
                    c == '\n' || c == '\r' || c == '\t' -> append(' ')
                    c in "\\`*_{}[]<>()#+-.!|&\"'" -> append("&#${c.code};")
                    c.isISOControl() -> append(' ')
                    else -> append(c)
                }
            }
        }

        internal fun subjectName(subject: CertificationSubject): String = when (subject) {
            is CertificationSubject.Semantic -> "semantic:${subject.capability}"
            is CertificationSubject.Structural -> "structural:${subject.kind.name}"
            is CertificationSubject.Leaf -> "leaf:${subject.kind}:${subject.reference}"
        }
    }
}

data class CertificationEvidenceViewResult(val admission: CertificationAdmissionReport, val view: CertificationEvidenceView?)

/** Derives display data only after authenticating and admitting the same frozen inputs. */
object CertificationEvidenceViews {
    fun assess(
        bundle: AdapterCertificationBundle,
        expectedAdapter: CertificationAdapterIdentity,
        boundScenarios: List<BoundCertificationScenario>,
        provider: TargetNativeProjectionCatalog,
        observations: List<SignedCertificationObservation>,
        trust: CertificationObservationTrust,
        resolver: CertificationEvidenceResolver
    ): CertificationEvidenceViewResult {
        // Resolver callbacks may mutate caller-owned collections. Snapshot before any callback,
        // and use this same snapshot for both admission and projection (including limitations).
        val frozen = bundle.copy(
            coverage = freeze(bundle.coverage.map { it.copy(scenarioIds = freeze(it.scenarioIds)) }),
            scenarios = freeze(bundle.scenarios.map { it.copy(runtimePrerequisites = freeze(it.runtimePrerequisites),
                negativeMutants = freeze(it.negativeMutants)) }),
            limitations = freeze(bundle.limitations))
        val bound = freeze(boundScenarios)
        val signed = freeze(observations)
        val report = AuthenticatedAdapterCertificationAdmission.evaluate(frozen, expectedAdapter, bound, provider, signed, trust, resolver)
        if (!report.valid) return CertificationEvidenceViewResult(report, null)

        val runs = signed.associateBy { it.observation.scenarioId to it.observation.mutantId }
        val scenarios = frozen.scenarios.sortedBy { it.id }.map { scenario ->
            fun run(mutantId: String?, expected: CertificationEvidenceReference): CertificationRunView {
                val statement = runs.getValue(scenario.id to mutantId)
                val observation = statement.observation
                return CertificationRunView(observation.runId, mutantId, statement.runnerId,
                    sha256(trust.trustedRunners.getValue(statement.runnerId).encoded), observation.artifact, expected, observation.observation)
            }
            val subjects = bound.single { it.id == scenario.id }.subjects
            CertificationScenarioView(scenario.id,
                if (subjects.any { it is CertificationSubject.Structural }) CertificationScenarioShape.STRUCTURAL_OCCURRENCES
                else if (subjects.any { it is CertificationSubject.Leaf }) CertificationScenarioShape.NATIVE_LEAF_ONLY
                else CertificationScenarioShape.SEMANTIC_OCCURRENCES_ONLY,
                scenario.fixture, scenario.canonicalGraph,
                freeze(scenario.runtimePrerequisites.sortedBy { it.id }),
                freeze(listOf(run(null, scenario.expectedObservation)) + scenario.negativeMutants.sortedBy { it.id }.map {
                    run(it.id, it.expectedObservation)
                }))
        }
        val coverage = frozen.coverage.sortedWith(compareBy(
            { when (it.subject) { is CertificationSubject.Semantic -> 0; is CertificationSubject.Structural -> 1; is CertificationSubject.Leaf -> 2 } },
            { CertificationEvidenceView.subjectName(it.subject) })).map { row ->
            CertificationCoverageView(row.subject,
                if (row.scenarioIds.isEmpty()) CertificationOccurrenceStatus.NOT_OBSERVED else CertificationOccurrenceStatus.OBSERVED_IN_SCENARIO,
                freeze(row.scenarioIds.sorted()), row.limitation)
        }
        return CertificationEvidenceViewResult(report, CertificationEvidenceView(frozen.adapter, trust.challenge,
            freeze(coverage), freeze(scenarios), freeze(frozen.limitations.sorted())))
    }

    private fun <T> freeze(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
