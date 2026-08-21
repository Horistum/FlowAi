package org.flowlang.conformance

import java.io.File

enum class ExternalCorpusStatus {
    FOUNDATION,
    EVIDENCE_ACTIVE
}

enum class ExternalSemanticExpectation {
    PRESERVE,
    REJECT,
    UNRESOLVED
}

data class ExternalCorpusManifest(
    val kind: String,
    val version: String,
    val status: ExternalCorpusStatus,
    val casePackages: List<String>,
    val invariants: List<String>
)

data class ExternalSourceLicense(
    val spdx: String,
    val evidence: String
)

data class ExternalSourceProvenance(
    val repository: String,
    val revision: String,
    val path: String,
    val license: ExternalSourceLicense
)

data class ExternalSourceCapture(
    val path: String,
    val sha256: String
)

data class ExternalSourceEvidence(
    val startLine: Int,
    val endLine: Int
)

data class ExternalAuthoredBehavior(
    val id: String,
    val statement: String,
    val evidence: List<ExternalSourceEvidence>
)

data class ExternalSemanticObservation(
    val id: String,
    val expectation: ExternalSemanticExpectation,
    val statement: String,
    val behaviorRefs: List<String>,
    val rationale: String
)

data class ExternalUnsupportedFact(
    val id: String,
    val statement: String,
    val evidence: List<ExternalSourceEvidence>,
    val reason: String
)

data class ExternalCorpusCase(
    val kind: String,
    val version: String,
    val id: String,
    val domain: String,
    val provenance: ExternalSourceProvenance,
    val sourceCapture: ExternalSourceCapture,
    val authoredBehaviors: List<ExternalAuthoredBehavior>,
    val expectedSemanticObservations: List<ExternalSemanticObservation>,
    val unsupportedFacts: List<ExternalUnsupportedFact>
)

data class LoadedExternalCorpusCase(
    val definition: ExternalCorpusCase,
    val directory: File,
    val sourceFile: File
)

data class LoadedExternalCorpus(
    val manifest: ExternalCorpusManifest,
    val cases: List<LoadedExternalCorpusCase>
)
