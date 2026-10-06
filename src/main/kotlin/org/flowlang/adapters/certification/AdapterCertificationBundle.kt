package org.flowlang.adapters.certification

import org.flowlang.generators.manifest.TargetStructuralProjectionKind

/** Candidate identity, pinned to implementation bytes rather than a display version alone. */
data class CertificationAdapterIdentity(
    val target: String,
    val adapterId: String,
    val version: String,
    val implementationSha256: String
)

/** Opaque resolver key. It is never interpreted as a filesystem path or URL by admission. */
data class CertificationEvidenceReference(val id: String, val sha256: String, val sizeBytes: Int)

sealed interface CertificationSubject {
    data class Semantic(val capability: String) : CertificationSubject
    data class Structural(val kind: TargetStructuralProjectionKind) : CertificationSubject
    data class Leaf(val kind: String, val reference: String) : CertificationSubject
}

/** Empty scenarioIds explicitly means uncovered. Presence never declares public support. */
data class CertificationCoverage(
    val subject: CertificationSubject,
    val scenarioIds: List<String>,
    val limitation: String
)

data class CertificationRuntimePrerequisite(val id: String, val version: String)

/** A deliberately changed target artifact, with its independently specified observable result. */
data class CertificationNegativeMutant(
    val id: String,
    val artifact: CertificationEvidenceReference,
    val expectedObservation: CertificationEvidenceReference
)

/**
 * All mutants run with the same canonical graph, fixture and runtime as the baseline.
 * Normalization and semantic adequacy of observation bytes belong to conformance.
 */
data class CertificationScenario(
    val id: String,
    val canonicalGraph: CertificationEvidenceReference,
    val fixture: CertificationEvidenceReference,
    val artifact: CertificationEvidenceReference,
    val expectedObservation: CertificationEvidenceReference,
    val runtimePrerequisites: List<CertificationRuntimePrerequisite>,
    val negativeMutants: List<CertificationNegativeMutant>
)

/**
 * Internal AR-06 candidate contract. No wire schema, execution authorization or
 * target-wide maturity is implied by constructing or admitting this bundle.
 */
data class AdapterCertificationBundle(
    val adapter: CertificationAdapterIdentity,
    val coverage: List<CertificationCoverage>,
    val scenarios: List<CertificationScenario>,
    val limitations: List<String>
)

enum class CertificationRunOutcome { COMPLETED, INFRASTRUCTURE_FAILURE, TIMEOUT }

/** Supplied separately by a trusted runner; never synthesized from expected fixture results. */
data class CertificationExecutionObservation(
    val runId: String,
    val adapter: CertificationAdapterIdentity,
    val scenarioId: String,
    val mutantId: String?,
    val canonicalGraphSha256: String,
    val fixtureSha256: String,
    val artifact: CertificationEvidenceReference,
    val observation: CertificationEvidenceReference,
    val runtimePrerequisites: List<CertificationRuntimePrerequisite>,
    val outcome: CertificationRunOutcome
)

/** Implementations must bound their own I/O before allocating bytes. Null means unavailable. */
fun interface CertificationEvidenceResolver {
    fun resolve(reference: CertificationEvidenceReference): ByteArray?
}

data class CertificationAdmissionFinding(val code: String, val subject: String)

/** Integrity result only. It cannot authorize rendering or promote a target support claim. */
data class CertificationAdmissionReport(
    val findings: List<CertificationAdmissionFinding>,
    val admittedScenarioIds: List<String>
) {
    val valid: Boolean get() = findings.isEmpty()
}
