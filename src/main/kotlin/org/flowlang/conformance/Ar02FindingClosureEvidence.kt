package org.flowlang.conformance

import java.io.File
import org.flowlang.architecture.KotlinSourceBoundaryScanner
import org.flowlang.serialization.FlowYaml

internal data class Ar02ImplementationRevision(val pullRequest: Int, val commit: String)
internal data class Ar02ProductionContract(val path: String, val symbol: String)
internal data class Ar02RegressionWitness(val path: String, val symbol: String, val formerBehavior: String)
internal data class Ar02FindingClosureEvidence(
    val findingId: String,
    val implementations: List<Ar02ImplementationRevision>,
    val productionContracts: List<Ar02ProductionContract>,
    val positiveChecks: List<String>,
    val negativeChecks: List<String>,
    val regressionWitnesses: List<Ar02RegressionWitness>
)
internal data class Ar02FindingClosureDocument(val version: String, val findings: List<Ar02FindingClosureEvidence>)

internal object Ar02FindingClosureCatalog {
    const val PATH = "architecture-recovery/ar-02/closure-evidence.yaml"

    fun load(rootDir: File): Ar02FindingClosureDocument =
        FlowYaml.readStrict(File(rootDir, PATH), Ar02FindingClosureDocument::class.java)
}

/**
 * Links reviewed historical revisions to current executable results. Offline
 * conformance validates the reference structure and live source/test symbols;
 * GitHub revision ancestry and CI receipts are independently checked at review.
 * File existence is not accepted as a substitute for successful behavior checks.
 */
internal object Ar02FindingClosureEvidenceValidator {
    private val expectedChecks = mapOf(
        "F-02" to (
            setOf(Ar02FlowSensitiveConformanceChecks.LATTICE_CHECK, Ar02ExplicitMergeConformanceChecks.MERGE_CHECK) to
                setOf(Ar02FlowSensitiveConformanceChecks.REJECTION_CHECK, Ar02ExplicitMergeConformanceChecks.PERMUTATION_CHECK)
        ),
        "F-08" to (setOf(Ar02WorkflowOwnershipConformanceChecks.MEMBERSHIP) to setOf(Ar02WorkflowOwnershipConformanceChecks.TARGET_GATE)),
        "F-15" to (setOf(Ar02WorkflowFailureConformanceChecks.FAILURE_POLICY_INTEGRITY) to
            setOf(Ar02WorkflowFailureConformanceChecks.NO_SYNTHETIC_HANDLER_AUTHORITY))
    )

    fun errors(
        document: Ar02FindingClosureDocument,
        declaredChecks: Set<String>,
        executedChecks: List<ConformanceCheck>,
        rootDir: File
    ): List<String> = buildList {
        if (document.version != "1.0") add("Unsupported AR-02 closure evidence version '${document.version}'.")
        val ids = document.findings.map { it.findingId }
        if (ids.toSet() != expectedChecks.keys || ids.size != expectedChecks.size) {
            add("AR-02 closure catalog must map exactly F-02, F-08 and F-15 once each.")
        }
        val results = executedChecks.groupBy { it.name }
        document.findings.forEach { evidence ->
            val id = evidence.findingId
            val polarity = expectedChecks[id]
            if (polarity == null || evidence.positiveChecks.toSet() != polarity.first ||
                evidence.negativeChecks.toSet() != polarity.second ||
                evidence.positiveChecks.distinct().size != evidence.positiveChecks.size ||
                evidence.negativeChecks.distinct().size != evidence.negativeChecks.size
            ) add("$id has missing, duplicate or incorrectly assigned positive/negative check polarity.")
            if (evidence.implementations.isEmpty() || evidence.implementations.distinct().size != evidence.implementations.size) {
                add("$id has no unique historical implementation revisions.")
            }
            evidence.implementations.forEach { revision ->
                if (revision.pullRequest <= 0 || !revision.commit.matches(Regex("[0-9a-f]{40}")) || revision.commit.toSet().size == 1) {
                    add("$id has a malformed implementation revision.")
                }
            }
            val paths = evidence.productionContracts.map { it.path }
            if (paths.size < 3 || paths.distinct().size != paths.size) {
                add("$id requires at least three distinct production contracts.")
            }
            evidence.productionContracts.forEach { contract ->
                if (!declares(rootDir, contract.path, contract.symbol, "src/main/kotlin/", false)) {
                    add("$id production declaration is missing or outside the source boundary: ${contract.path}#${contract.symbol}")
                }
            }
            if (evidence.regressionWitnesses.isEmpty() || evidence.regressionWitnesses.distinct().size != evidence.regressionWitnesses.size) {
                add("$id lacks distinct regression witnesses.")
            }
            evidence.regressionWitnesses.forEach { witness ->
                if (witness.formerBehavior.isBlank() || !declares(rootDir, witness.path, witness.symbol, "src/test/kotlin/", true)) {
                    add("$id regression witness is missing: ${witness.path}#${witness.symbol}")
                }
            }
            (evidence.positiveChecks + evidence.negativeChecks).forEach { name ->
                if (name !in declaredChecks) add("$id references undeclared conformance check '$name'.")
                val observed = results[name].orEmpty()
                when {
                    observed.size != 1 -> add("$id check '$name' must have exactly one current execution result.")
                    !observed.single().passed -> add("$id check '$name' failed: ${observed.single().message.orEmpty()}")
                }
            }
        }
    }

    private fun declares(root: File, path: String, symbol: String, prefix: String, test: Boolean): Boolean {
        if (!path.startsWith(prefix) || !path.endsWith(".kt") || !symbol.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return false
        val base = root.canonicalFile
        val file = File(base, path).canonicalFile
        if (!file.toPath().startsWith(File(base, prefix).toPath()) || !file.isFile) return false
        val structural = KotlinSourceBoundaryScanner.structuralSource(file.readText())
        val declaration = if (test) "fun" else "(?:class|object|interface|fun)"
        return Regex("\\b$declaration\\s+${Regex.escape(symbol)}\\b").containsMatchIn(structural)
    }
}
