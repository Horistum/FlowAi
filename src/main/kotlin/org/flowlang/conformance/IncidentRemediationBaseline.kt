package org.flowlang.conformance

import java.io.File
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

data class IncidentRemediationBaselineFact(
    val caseId: String,
    val factId: String,
    val observationRef: String,
    val requirement: IncidentRemediationRequirement,
    val initialOutcome: ExternalFalsificationOutcome
)

data class IncidentRemediationBaseline(
    val kind: String,
    val version: String,
    val facts: List<IncidentRemediationBaselineFact>
)

data class IncidentRemediationBaselineVerification(
    val status: String,
    val errors: List<String>
)

/**
 * Verifies the EF-04 incident-remediation falsification snapshot.
 *
 * While EF-04 is active or validating, the baseline is an exact immutable snapshot of the live
 * evaluator. After closure, historical REPRESENTABLE findings may never regress while a MODEL_GAP
 * may improve only because a later independently authorized semantic correction changes Core.
 */
class IncidentRemediationBaselineVerifier(
    private val rootDir: File = File("."),
    private val evaluator: IncidentRemediationFalsification = IncidentRemediationFalsification(rootDir)
) {
    fun verify(
        current: IncidentRemediationFalsificationReport = evaluator.evaluate()
    ): IncidentRemediationBaselineVerification {
        val errors = mutableListOf<String>()
        val baseline = runCatching { loadBaseline() }.getOrElse { error ->
            return failed(error)
        }
        val lifecycleStatus = runCatching { loadLifecycleStatus() }.getOrElse { error ->
            return failed(error)
        }

        if (baseline.kind != KIND || baseline.version != VERSION) {
            errors += "EF-04 baseline must use $KIND version $VERSION."
        }
        if (baseline.facts.isEmpty()) {
            errors += "EF-04 baseline must record at least one historical fact."
        }

        val baselineKeys = baseline.facts.map(::key)
        if (baselineKeys.distinct().size != baselineKeys.size) {
            errors += "EF-04 baseline contains duplicate case/fact identities."
        }
        val initialOutcomes = baseline.facts.map { it.initialOutcome }.toSet()
        if (ExternalFalsificationOutcome.REPRESENTABLE !in initialOutcomes ||
            ExternalFalsificationOutcome.MODEL_GAP !in initialOutcomes
        ) {
            errors += "EF-04 baseline must preserve the mixed initial falsification result rather than a manufactured all-green or all-gap snapshot."
        }

        val currentByKey = current.findings.associateBy(::key)
        val baselineKeySet = baselineKeys.toSet()
        val currentKeySet = currentByKey.keys
        if (baselineKeySet != currentKeySet) {
            val missing = (baselineKeySet - currentKeySet).sorted()
            val unexpected = (currentKeySet - baselineKeySet).sorted()
            if (missing.isNotEmpty()) errors += "EF-04 current evaluation is missing baseline facts: ${missing.joinToString()}."
            if (unexpected.isNotEmpty()) errors += "EF-04 current evaluation contains unbaselined facts: ${unexpected.joinToString()}."
        }

        baseline.facts.forEach { historical ->
            val currentFinding = currentByKey[key(historical)] ?: return@forEach
            if (historical.observationRef != currentFinding.observationRef) {
                errors += "EF-04 fact '${key(historical)}' observation identity drifted: baseline=${historical.observationRef} current=${currentFinding.observationRef}."
            }
            if (historical.requirement != currentFinding.requirement) {
                errors += "EF-04 fact '${key(historical)}' typed requirement drifted: baseline=${historical.requirement} current=${currentFinding.requirement}."
            }

            when {
                lifecycleStatus != COMPLETE_STATUS && historical.initialOutcome != currentFinding.outcome ->
                    errors += "EF-04 initial snapshot mismatch for '${key(historical)}': baseline=${historical.initialOutcome} current=${currentFinding.outcome}; active/validating baseline recording must exactly match live evaluation."
                lifecycleStatus == COMPLETE_STATUS &&
                    historical.initialOutcome == ExternalFalsificationOutcome.REPRESENTABLE &&
                    currentFinding.outcome != ExternalFalsificationOutcome.REPRESENTABLE ->
                    errors += "EF-04 representability regression for '${key(historical)}': the historical REPRESENTABLE fact is now ${currentFinding.outcome}."
            }
        }

        return IncidentRemediationBaselineVerification(
            status = if (errors.isEmpty()) "PASS" else "FAIL",
            errors = errors
        )
    }

    private fun loadBaseline(): IncidentRemediationBaseline {
        val file = File(rootDir, BASELINE_PATH)
        require(file.isFile) { "Missing EF-04 falsification baseline: ${file.path}" }
        return try {
            FlowYaml.readStrict(file, IncidentRemediationBaseline::class.java)
        } catch (error: FlowYamlException) {
            throw IllegalArgumentException(
                "Invalid EF-04 falsification baseline '${file.path}': ${error.message ?: error.javaClass.simpleName}",
                error
            )
        }
    }

    private fun loadLifecycleStatus(): String {
        val file = File(rootDir, WORK_PACKAGE_PATH)
        require(file.isFile) { "Missing EF-04 work package: ${file.path}" }
        val document = runCatching { FlowYaml.readMap(file) }.getOrElse { error ->
            throw IllegalArgumentException(
                "Invalid EF-04 work package '${file.path}': ${error.message ?: error.javaClass.simpleName}",
                error
            )
        }
        val status = document["status"]?.toString()?.trim().orEmpty()
        require(status in LIFECYCLE_STATUSES) {
            "EF-04 work package status must be one of ${LIFECYCLE_STATUSES.sorted().joinToString()}, got '$status'."
        }
        return status
    }

    private fun failed(error: Throwable) = IncidentRemediationBaselineVerification(
        status = "FAIL",
        errors = listOf(error.message ?: error.javaClass.simpleName)
    )

    private fun key(fact: IncidentRemediationBaselineFact): String = "${fact.caseId}::${fact.factId}"
    private fun key(finding: IncidentRemediationFinding): String = "${finding.caseId}::${finding.factId}"

    companion object {
        const val BASELINE_PATH = "conformance/corpus/external/baselines/ef-04-incident-remediation.yaml"
        const val WORK_PACKAGE_PATH = ".flow-agent/work-packages/EF-04-incident-remediation-falsification.yaml"
        const val KIND = "FlowIncidentRemediationFalsificationBaseline"
        const val VERSION = "1.0"
        private const val COMPLETE_STATUS = "complete"
        private val LIFECYCLE_STATUSES = setOf("active", "validating", COMPLETE_STATUS)
    }
}

class IncidentRemediationConformanceRunner(private val rootDir: File = File(".")) {
    fun checks(): List<ConformanceCheck> {
        val evaluation = runCatching { IncidentRemediationFalsification(rootDir).evaluate() }
        val report = evaluation.getOrNull()
        val evaluationCheck = ConformanceCheck(
            EVALUATION_CHECK,
            report != null,
            evaluation.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        )
        if (report == null) {
            return listOf(
                evaluationCheck,
                ConformanceCheck(BASELINE_CHECK, false, "EF-04 baseline cannot be verified because domain evaluation failed.")
            )
        }

        val verification = IncidentRemediationBaselineVerifier(rootDir).verify(report)
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
        const val EVALUATION_CHECK = "conformance.ef-04.incident-remediation-evaluation"
        const val BASELINE_CHECK = "conformance.ef-04.incident-remediation-baseline"
    }
}
