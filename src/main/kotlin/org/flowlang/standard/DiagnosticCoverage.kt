package org.flowlang.standard

data class ObservedDiagnosticCode(
    val artifact: String,
    val code: String,
    val source: String = "",
    val severity: String = ""
)

data class DiagnosticCoverageUnknownCode(
    val artifact: String,
    val code: String,
    val source: String = "",
    val severity: String = ""
)

data class DiagnosticCoverageReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val diagnosticCoverageVersion: String = "1.0",
    val diagnosticCatalogVersion: String,
    val status: String,
    val totalObservedCodes: Int,
    val totalUnknownCodes: Int,
    val catalogCodes: List<String>,
    val observedCodes: List<ObservedDiagnosticCode>,
    val unknownCodes: List<DiagnosticCoverageUnknownCode>,
    val unusedCatalogCodes: List<String>
)

/**
 * Verifies that diagnostic codes emitted by public reports are part of the
 * standard diagnostic catalog.
 *
 * This deliberately does not interpret intent, safety or target semantics.
 * Those decisions belong upstream. This layer only checks whether public
 * diagnostics remain machine-readable and stable for external consumers.
 */
class DiagnosticCoverageAnalyzer(
    private val catalog: StandardDiagnosticCatalogReport = StandardDiagnosticCatalog.report()
) {
    fun analyze(observed: List<ObservedDiagnosticCode>): DiagnosticCoverageReport {
        val catalogCodes = catalog.codes.map { it.code }.toSortedSet()
        val normalizedObserved = observed
            .filter { it.code.isNotBlank() }
            .map {
                it.copy(
                    artifact = it.artifact.trim(),
                    code = it.code.trim(),
                    source = it.source.trim(),
                    severity = it.severity.trim()
                )
            }
            .distinct()
            .sortedWith(compareBy<ObservedDiagnosticCode> { it.artifact }.thenBy { it.code }.thenBy { it.source })
        val unknown = normalizedObserved
            .filter { it.code !in catalogCodes }
            .map { DiagnosticCoverageUnknownCode(it.artifact, it.code, it.source, it.severity) }
        val observedCodes = normalizedObserved.map { it.code }.toSet()
        return DiagnosticCoverageReport(
            diagnosticCatalogVersion = catalog.diagnosticCatalogVersion,
            status = if (unknown.isEmpty()) "PASS" else "FAIL",
            totalObservedCodes = normalizedObserved.size,
            totalUnknownCodes = unknown.size,
            catalogCodes = catalogCodes.toList(),
            observedCodes = normalizedObserved,
            unknownCodes = unknown,
            unusedCatalogCodes = catalogCodes.filter { it !in observedCodes }
        )
    }
}
