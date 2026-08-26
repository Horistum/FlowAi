package org.flowlang.conformance

import java.io.File
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

data class HumanApprovalChangeControlBaselineFact(
    val caseId: String,
    val factId: String,
    val observationRef: String,
    val requirement: HumanApprovalChangeControlRequirement,
    val initialOutcome: ExternalFalsificationOutcome
)

data class HumanApprovalChangeControlBaseline(
    val kind: String,
    val version: String,
    val facts: List<HumanApprovalChangeControlBaselineFact>
)

data class HumanApprovalChangeControlBaselineVerification(
    val status: String,
    val errors: List<String>
)

/**
 * Verifies the EF-09 initial mixed falsification snapshot.
 *
 * Active and validating states must match exactly. Once complete, historical REPRESENTABLE facts
 * may not regress while a MODEL_GAP may improve only through a later independently authorized
 * semantic correction.
 */
class HumanApprovalChangeControlBaselineVerifier(
    private val rootDir: File = File("."),
    private val evaluator: HumanApprovalChangeControlFalsification =
        HumanApprovalChangeControlFalsification(rootDir)
) {
    fun verify(
        current: HumanApprovalChangeControlFalsificationReport = evaluator.evaluate()
    ): HumanApprovalChangeControlBaselineVerification {
        val errors = mutableListOf<String>()
        val baseline = runCatching { loadBaseline() }.getOrElse { return failed(it) }
        val lifecycleStatus = runCatching { loadLifecycleStatus() }.getOrElse {
            return failed(it)
        }

        if (baseline.kind != KIND || baseline.version != VERSION) {
            errors += "EF-09 baseline must use $KIND version $VERSION."
        }
        if (baseline.facts.isEmpty()) {
            errors += "EF-09 baseline must record at least one historical fact."
        }
        val baselineKeys = baseline.facts.map(::key)
        if (baselineKeys.distinct().size != baselineKeys.size) {
            errors += "EF-09 baseline contains duplicate case/fact identities."
        }
        val initialOutcomes = baseline.facts.map { it.initialOutcome }.toSet()
        if (
            ExternalFalsificationOutcome.REPRESENTABLE !in initialOutcomes ||
            ExternalFalsificationOutcome.MODEL_GAP !in initialOutcomes
        ) {
            errors += "EF-09 baseline must preserve a mixed initial falsification result."
        }

        val currentByKey = current.findings.associateBy(::key)
        val baselineKeySet = baselineKeys.toSet()
        val currentKeySet = currentByKey.keys
        if (baselineKeySet != currentKeySet) {
            val missing = (baselineKeySet - currentKeySet).sorted()
            val unexpected = (currentKeySet - baselineKeySet).sorted()
            if (missing.isNotEmpty()) {
                errors += "EF-09 current evaluation is missing baseline facts: " +
                    missing.joinToString() + "."
            }
            if (unexpected.isNotEmpty()) {
                errors += "EF-09 current evaluation contains unbaselined facts: " +
                    unexpected.joinToString() + "."
            }
        }

        baseline.facts.forEach { historical ->
            val currentFinding = currentByKey[key(historical)] ?: return@forEach
            if (historical.observationRef != currentFinding.observationRef) {
                errors += "EF-09 fact '${key(historical)}' observation identity drifted: " +
                    "baseline=${historical.observationRef} " +
                    "current=${currentFinding.observationRef}."
            }
            if (historical.requirement != currentFinding.requirement) {
                errors += "EF-09 fact '${key(historical)}' typed requirement drifted: " +
                    "baseline=${historical.requirement} current=${currentFinding.requirement}."
            }
            when {
                lifecycleStatus != COMPLETE_STATUS &&
                    historical.initialOutcome != currentFinding.outcome ->
                    errors += "EF-09 initial snapshot mismatch for '${key(historical)}': " +
                        "baseline=${historical.initialOutcome} current=${currentFinding.outcome}; " +
                        "active/validating baseline recording must exactly match live evaluation."
                lifecycleStatus == COMPLETE_STATUS &&
                    historical.initialOutcome == ExternalFalsificationOutcome.REPRESENTABLE &&
                    currentFinding.outcome != ExternalFalsificationOutcome.REPRESENTABLE ->
                    errors += "EF-09 representability regression for '${key(historical)}': " +
                        "the historical REPRESENTABLE fact is now ${currentFinding.outcome}."
            }
        }
        return HumanApprovalChangeControlBaselineVerification(
            status = if (errors.isEmpty()) "PASS" else "FAIL",
            errors = errors
        )
    }

    private fun loadBaseline(): HumanApprovalChangeControlBaseline {
        val file = File(rootDir, BASELINE_PATH)
        require(file.isFile) { "Missing EF-09 falsification baseline: ${file.path}" }
        return try {
            FlowYaml.readStrict(file, HumanApprovalChangeControlBaseline::class.java)
        } catch (error: FlowYamlException) {
            throw IllegalArgumentException(
                "Invalid EF-09 falsification baseline '${file.path}': " +
                    (error.message ?: error.javaClass.simpleName),
                error
            )
        }
    }

    private fun loadLifecycleStatus(): String {
        val file = File(rootDir, WORK_PACKAGE_PATH)
        require(file.isFile) { "Missing EF-09 work package: ${file.path}" }
        val document = runCatching { FlowYaml.readMap(file) }.getOrElse { error ->
            throw IllegalArgumentException(
                "Invalid EF-09 work package '${file.path}': " +
                    (error.message ?: error.javaClass.simpleName),
                error
            )
        }
        val status = document["status"]?.toString()?.trim().orEmpty()
        require(status in LIFECYCLE_STATUSES) {
            "EF-09 work package status must be one of " +
                LIFECYCLE_STATUSES.sorted().joinToString() + ", got '$status'."
        }
        return status
    }

    private fun failed(error: Throwable) = HumanApprovalChangeControlBaselineVerification(
        status = "FAIL",
        errors = listOf(error.message ?: error.javaClass.simpleName)
    )

    private fun key(fact: HumanApprovalChangeControlBaselineFact) =
        "${fact.caseId}::${fact.factId}"

    private fun key(finding: HumanApprovalChangeControlFinding) =
        "${finding.caseId}::${finding.factId}"

    companion object {
        const val BASELINE_PATH =
            "conformance/corpus/external/baselines/ef-09-human-approval-change-control.yaml"
        const val WORK_PACKAGE_PATH =
            ".flow-agent/work-packages/EF-09-human-approval-change-control-falsification.yaml"
        const val KIND = "FlowHumanApprovalChangeControlFalsificationBaseline"
        const val VERSION = "1.0"
        private const val COMPLETE_STATUS = "complete"
        private val LIFECYCLE_STATUSES = setOf("active", "validating", COMPLETE_STATUS)
    }
}

class HumanApprovalChangeControlConformanceRunner(private val rootDir: File = File(".")) {
    fun checks(): List<ConformanceCheck> {
        val evaluation = runCatching {
            HumanApprovalChangeControlFalsification(rootDir).evaluate()
        }
        val report = evaluation.getOrNull()
        val evaluationCheck = ConformanceCheck(
            EVALUATION_CHECK,
            report != null,
            evaluation.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        )
        if (report == null) {
            return listOf(
                evaluationCheck,
                ConformanceCheck(
                    BASELINE_CHECK,
                    false,
                    "EF-09 baseline cannot be verified because domain evaluation failed."
                )
            )
        }

        val verification = HumanApprovalChangeControlBaselineVerifier(rootDir).verify(report)
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
        const val EVALUATION_CHECK =
            "conformance.ef-09.human-approval-change-control-evaluation"
        const val BASELINE_CHECK =
            "conformance.ef-09.human-approval-change-control-baseline"
    }
}
