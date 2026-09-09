package org.flowlang.adapters.continuity

import java.io.File

data class AdapterContinuityScopedSupportFinding(
    val code: String,
    val identity: String,
    val message: String
)

data class AdapterContinuityScopedSupportReport(
    val status: String,
    val findings: List<AdapterContinuityScopedSupportFinding>,
    val declarationCount: Int
)

/** Validates supplied evidence without choosing or loading a concrete adapter. */
class AdapterContinuityScopedSupportIntegrityAuthority(
    private val rootDir: File = File("."),
    private val declarations: List<AdapterContinuityScopedSupport>
) {
    fun analyze(): AdapterContinuityScopedSupportReport {
        val findings = mutableListOf<AdapterContinuityScopedSupportFinding>()
        declarations.groupingBy(AdapterContinuityScopedSupport::identity)
            .eachCount().filterValues { it > 1 }.keys.sorted()
            .forEach { identity ->
                findings += finding("ADAPTER_CONTINUITY_SCOPED_SUPPORT_DUPLICATE", identity, "Scoped support identity is declared more than once.")
            }
        declarations.forEach { declaration ->
            val paths = declaration.evidenceReferences.map { it.substringBefore('#') }
            declaration.evidenceReferences.forEach { reference ->
                if (reference.startsWith("http://") || reference.startsWith("https://")) {
                    findings += finding(
                        "ADAPTER_CONTINUITY_SCOPED_SUPPORT_EXTERNAL_EVIDENCE", declaration.identity,
                        "External documentation cannot certify provider implementation: $reference"
                    )
                }
                if (!File(rootDir, reference.substringBefore('#')).isFile) {
                    findings += finding(
                        "ADAPTER_CONTINUITY_SCOPED_SUPPORT_EVIDENCE_MISSING", declaration.identity,
                        "Evidence reference does not resolve: $reference"
                    )
                }
            }
            if (paths.none { it.startsWith("src/main/") }) {
                findings += finding(
                    "ADAPTER_CONTINUITY_SCOPED_SUPPORT_IMPLEMENTATION_MISSING", declaration.identity,
                    "Scoped support requires production implementation evidence."
                )
            }
            if (paths.none { it.startsWith("src/test/") || it.startsWith("tests/") }) {
                findings += finding(
                    "ADAPTER_CONTINUITY_SCOPED_SUPPORT_BEHAVIOR_MISSING", declaration.identity,
                    "Scoped support requires independent behavioral evidence."
                )
            }
        }
        return AdapterContinuityScopedSupportReport(
            status = if (findings.isEmpty()) "PASS" else "FAIL",
            findings = findings.sortedWith(compareBy({ it.identity }, { it.code }, { it.message })),
            declarationCount = declarations.size
        )
    }

    private fun finding(code: String, identity: String, message: String) =
        AdapterContinuityScopedSupportFinding(code, identity, message)
}
