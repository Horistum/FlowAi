package org.flowlang.conformance

import java.io.File
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

data class DatabaseMigrationRecoveryBaselineFact(
    val caseId: String,
    val factId: String,
    val observationRef: String,
    val requirement: DatabaseMigrationRecoveryRequirement,
    val initialOutcome: ExternalFalsificationOutcome
)

data class DatabaseMigrationRecoveryBaseline(
    val kind: String,
    val version: String,
    val facts: List<DatabaseMigrationRecoveryBaselineFact>
)

data class DatabaseMigrationRecoveryBaselineVerification(
    val status: String,
    val errors: List<String>
)

/**
 * Verifies the immutable EF-02 falsification snapshot against the current semantic model.
 *
 * The baseline records what EF-02 actually observed after externally grounded evaluation.
 * It is intentionally monotonic rather than frozen-output testing: facts that were
 * REPRESENTABLE may never regress to MODEL_GAP, while historical MODEL_GAP findings may
 * improve to REPRESENTABLE in a later, independently authorized semantic correction.
 */
class DatabaseMigrationRecoveryBaselineVerifier(
    private val rootDir: File = File("."),
    private val evaluator: DatabaseMigrationRecoveryFalsification = DatabaseMigrationRecoveryFalsification(rootDir)
) {
    fun verify(
        current: DatabaseMigrationRecoveryFalsificationReport = evaluator.evaluate()
    ): DatabaseMigrationRecoveryBaselineVerification {
        val errors = mutableListOf<String>()
        val baseline = runCatching { loadBaseline() }.getOrElse { error ->
            return DatabaseMigrationRecoveryBaselineVerification(
                status = "FAIL",
                errors = listOf(error.message ?: error.javaClass.simpleName)
            )
        }

        if (baseline.kind != KIND || baseline.version != VERSION) {
            errors += "EF-02 baseline must use $KIND version $VERSION."
        }
        if (baseline.facts.isEmpty()) {
            errors += "EF-02 baseline must record at least one historical fact."
        }

        val baselineKeys = baseline.facts.map(::key)
        if (baselineKeys.distinct().size != baselineKeys.size) {
            errors += "EF-02 baseline contains duplicate case/fact identities."
        }
        val initialOutcomes = baseline.facts.map { it.initialOutcome }.toSet()
        if (ExternalFalsificationOutcome.REPRESENTABLE !in initialOutcomes ||
            ExternalFalsificationOutcome.MODEL_GAP !in initialOutcomes
        ) {
            errors += "EF-02 baseline must preserve the mixed initial falsification result rather than a manufactured all-green or all-gap snapshot."
        }

        val currentByKey = current.findings.associateBy(::key)
        val baselineKeySet = baselineKeys.toSet()
        val currentKeySet = currentByKey.keys
        if (baselineKeySet != currentKeySet) {
            val missing = (baselineKeySet - currentKeySet).sorted()
            val unexpected = (currentKeySet - baselineKeySet).sorted()
            if (missing.isNotEmpty()) errors += "EF-02 current evaluation is missing baseline facts: ${missing.joinToString()}."
            if (unexpected.isNotEmpty()) errors += "EF-02 current evaluation contains unbaselined facts: ${unexpected.joinToString()}."
        }

        baseline.facts.forEach { historical ->
            val currentFinding = currentByKey[key(historical)] ?: return@forEach
            if (historical.observationRef != currentFinding.observationRef) {
                errors += "EF-02 fact '${key(historical)}' observation identity drifted: baseline=${historical.observationRef} current=${currentFinding.observationRef}."
            }
            if (historical.requirement != currentFinding.requirement) {
                errors += "EF-02 fact '${key(historical)}' typed requirement drifted: baseline=${historical.requirement} current=${currentFinding.requirement}."
            }
            if (historical.initialOutcome == ExternalFalsificationOutcome.REPRESENTABLE &&
                currentFinding.outcome != ExternalFalsificationOutcome.REPRESENTABLE
            ) {
                errors += "EF-02 representability regression for '${key(historical)}': the historical REPRESENTABLE fact is now ${currentFinding.outcome}."
            }
        }

        return DatabaseMigrationRecoveryBaselineVerification(
            status = if (errors.isEmpty()) "PASS" else "FAIL",
            errors = errors
        )
    }

    private fun loadBaseline(): DatabaseMigrationRecoveryBaseline {
        val file = File(rootDir, BASELINE_PATH)
        require(file.isFile) { "Missing EF-02 falsification baseline: ${file.path}" }
        return try {
            FlowYaml.readStrict(file, DatabaseMigrationRecoveryBaseline::class.java)
        } catch (error: FlowYamlException) {
            throw IllegalArgumentException(
                "Invalid EF-02 falsification baseline '${file.path}': ${error.message ?: error.javaClass.simpleName}",
                error
            )
        }
    }

    private fun key(fact: DatabaseMigrationRecoveryBaselineFact): String = "${fact.caseId}::${fact.factId}"
    private fun key(finding: DatabaseMigrationRecoveryFinding): String = "${finding.caseId}::${finding.factId}"

    companion object {
        const val BASELINE_PATH = "conformance/corpus/external/baselines/ef-02-database-migration-recovery.yaml"
        const val KIND = "FlowDatabaseMigrationRecoveryFalsificationBaseline"
        const val VERSION = "1.0"
    }
}

class DatabaseMigrationRecoveryConformanceRunner(private val rootDir: File = File(".")) {
    fun checks(): List<ConformanceCheck> {
        val evaluation = runCatching { DatabaseMigrationRecoveryFalsification(rootDir).evaluate() }
        val report = evaluation.getOrNull()
        val evaluationCheck = ConformanceCheck(
            EVALUATION_CHECK,
            report != null,
            evaluation.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        )
        if (report == null) {
            return listOf(
                evaluationCheck,
                ConformanceCheck(BASELINE_CHECK, false, "EF-02 baseline cannot be verified because domain evaluation failed.")
            )
        }

        val verification = DatabaseMigrationRecoveryBaselineVerifier(rootDir).verify(report)
        return listOf(
            evaluationCheck,
            ConformanceCheck(
                BASELINE_CHECK,
                verification.status == "PASS",
                verification.errors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            )
        )
    }

    companion object {
        const val EVALUATION_CHECK = "conformance.ef-02.database-migration-recovery-evaluation"
        const val BASELINE_CHECK = "conformance.ef-02.database-migration-recovery-baseline"
    }
}
