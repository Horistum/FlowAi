package org.flowlang.conformance

data class BoundedSemanticClosureEvidence(
    val id: String,
    val status: String,
    val expected: String,
    val observed: String,
    val source: String,
    val message: String
)

data class BoundedSemanticClosureReport(
    val closureVersion: String = "0.9.7.10",
    val track: String,
    val phase: String,
    val status: String,
    val declaredCoreItems: List<String>,
    val incompleteCoreItems: List<String>,
    val declaredConformanceChecks: List<String>,
    val observedConformanceChecks: List<String>,
    val missingConformanceChecks: List<String>,
    val unexpectedConformanceChecks: List<String>,
    val duplicateConformanceChecks: List<String>,
    val failedConformanceChecks: List<String>,
    val releaseProfileChecks: List<String>,
    val missingReleaseProfileChecks: List<String>,
    val anchoredRuntimeChecks: List<String>,
    val missingAnchoredRuntimeChecks: List<String>,
    val retainedReferenceChecks: List<String>,
    val missingRetainedReferenceChecks: List<String>,
    val standardModelIssues: List<String>,
    val checks: List<BoundedSemanticClosureEvidence>,
    val failedChecks: List<String>
)
