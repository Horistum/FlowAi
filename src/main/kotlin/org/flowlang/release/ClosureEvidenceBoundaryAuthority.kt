package org.flowlang.release

import java.io.File
import org.flowlang.serialization.FlowYaml

data class ClosureWorkflowEvidence(
    val status: String,
    val workflow: String,
    val runNumber: Int?,
    val runId: Long?,
    val exactHead: String,
    val mergeCandidate: String,
    val unknownFields: List<String> = emptyList(),
    val present: Boolean = true
) {
    val structurallyValid: Boolean
        get() = present &&
            unknownFields.isEmpty() &&
            status == "passed" &&
            workflow == "Flow CI" &&
            runNumber?.let { it > 0 } == true &&
            runId?.let { it > 0 } == true &&
            SHA.matches(exactHead) &&
            SHA.matches(mergeCandidate) &&
            exactHead != mergeCandidate

    fun summary(): String = if (!present) {
        "absent"
    } else {
        "status=$status,workflow=$workflow,runNumber=${runNumber ?: "invalid"}," +
            "runId=${runId ?: "invalid"},exactHead=$exactHead,mergeCandidate=$mergeCandidate," +
            "unknown=${unknownFields.joinToString()}"
    }

    companion object {
        private val SHA = Regex("[0-9a-f]{40}")

        val ABSENT = ClosureWorkflowEvidence(
            status = "",
            workflow = "",
            runNumber = null,
            runId = null,
            exactHead = "",
            mergeCandidate = "",
            present = false
        )
    }
}

data class ClosureEvidenceBoundaryInput(
    val phase: String,
    val correctionItem: String,
    val supersededByCorrection: String,
    val implementationEvidence: ClosureWorkflowEvidence,
    val completionEvidence: ClosureWorkflowEvidence
)

data class ClosureEvidenceBoundaryCheck(
    val id: String,
    val status: String,
    val evidence: List<String>,
    val message: String
)

data class ClosureEvidenceBoundaryReport(
    val reportVersion: String = "1.0",
    val status: String,
    val phase: String,
    val checks: List<ClosureEvidenceBoundaryCheck>,
    val failedChecks: List<String>
)

/**
 * Proves that implementation and completion validation are two real boundaries.
 *
 * READY requires one structurally valid implementation run and no completion
 * claim. CLOSED requires a later completion run with distinct run, head and
 * merge-candidate identities. CORRECTION_REQUIRED accepts superseded historical
 * evidence only when the active correction is named explicitly.
 */
class ClosureEvidenceBoundaryAuthority(private val rootDir: File = File(".")) {
    fun analyze(): ClosureEvidenceBoundaryReport {
        val lifecycle = ReleaseMetadataHonestyAuthority(rootDir).analyze()
        val workPackageFile = File(rootDir, WORK_PACKAGE)
        require(workPackageFile.isFile) { "Closure work package is missing: ${workPackageFile.path}" }
        val workPackage = FlowYaml.readMap(workPackageFile)
        return evaluate(
            ClosureEvidenceBoundaryInput(
                phase = lifecycle.closurePhase,
                correctionItem = lifecycle.completedCorrectionItem,
                supersededByCorrection = workPackage.string("supersededByCorrection"),
                implementationEvidence = workPackage.workflowEvidence("implementationEvidence"),
                completionEvidence = workPackage.workflowEvidence("validationEvidence")
            )
        )
    }

    fun evaluate(input: ClosureEvidenceBoundaryInput): ClosureEvidenceBoundaryReport {
        val supportedPhase = input.phase in SUPPORTED_PHASES
        val correctionRequired = input.phase == "CORRECTION_REQUIRED"
        val ready = input.phase == "READY"
        val closed = input.phase == "CLOSED"

        val supersessionValid = when {
            correctionRequired -> input.correctionItem.isNotBlank() &&
                input.supersededByCorrection == input.correctionItem
            ready || closed -> input.supersededByCorrection.isBlank()
            else -> false
        }
        val implementationValid = when {
            correctionRequired -> true
            ready || closed -> input.implementationEvidence.structurallyValid
            else -> false
        }
        val completionValid = when {
            correctionRequired -> true
            ready -> !input.completionEvidence.present
            closed -> input.completionEvidence.structurallyValid
            else -> false
        }
        val boundariesDistinct = when {
            correctionRequired || ready -> true
            closed -> distinctAndOrdered(input.implementationEvidence, input.completionEvidence)
            else -> false
        }

        val checks = listOf(
            check(
                id = "closure.evidence.phase-supported",
                passed = supportedPhase,
                evidence = listOf("phase=${input.phase}"),
                message = "Closure evidence must be evaluated in CORRECTION_REQUIRED, READY or CLOSED."
            ),
            check(
                id = "closure.evidence.correction-supersession",
                passed = supersessionValid,
                evidence = listOf(
                    "phase=${input.phase}",
                    "correctionItem=${input.correctionItem}",
                    "supersededByCorrection=${input.supersededByCorrection}"
                ),
                message = "Only CORRECTION_REQUIRED may retain superseded evidence, and it must name the active correction."
            ),
            check(
                id = "closure.evidence.implementation-structured",
                passed = implementationValid,
                evidence = listOf(input.implementationEvidence.summary()),
                message = "READY and CLOSED require one structurally valid implementation validation boundary."
            ),
            check(
                id = "closure.evidence.completion-structured",
                passed = completionValid,
                evidence = listOf(input.completionEvidence.summary()),
                message = "READY must not claim completion evidence; CLOSED requires one structurally valid completion boundary."
            ),
            check(
                id = "closure.evidence.boundaries-distinct",
                passed = boundariesDistinct,
                evidence = boundaryEvidence(input.implementationEvidence, input.completionEvidence),
                message = "CLOSED requires a later completion run with distinct run id, exact head and merge candidate."
            )
        )
        val failed = checks.filter { it.status == "FAIL" }.map { it.id }
        return ClosureEvidenceBoundaryReport(
            status = if (failed.isEmpty()) "PASS" else "FAIL",
            phase = input.phase,
            checks = checks,
            failedChecks = failed
        )
    }

    private fun distinctAndOrdered(
        implementation: ClosureWorkflowEvidence,
        completion: ClosureWorkflowEvidence
    ): Boolean = implementation.structurallyValid &&
        completion.structurallyValid &&
        completion.runNumber!! > implementation.runNumber!! &&
        completion.runId != implementation.runId &&
        completion.exactHead != implementation.exactHead &&
        completion.mergeCandidate != implementation.mergeCandidate

    private fun boundaryEvidence(
        implementation: ClosureWorkflowEvidence,
        completion: ClosureWorkflowEvidence
    ): List<String> = listOf(
        "implementationRun=${implementation.runNumber ?: "invalid"}",
        "completionRun=${completion.runNumber ?: "invalid"}",
        "runIdDistinct=${implementation.runId != completion.runId}",
        "exactHeadDistinct=${implementation.exactHead != completion.exactHead}",
        "mergeCandidateDistinct=${implementation.mergeCandidate != completion.mergeCandidate}"
    )

    private fun check(
        id: String,
        passed: Boolean,
        evidence: List<String>,
        message: String
    ): ClosureEvidenceBoundaryCheck = ClosureEvidenceBoundaryCheck(
        id = id,
        status = if (passed) "PASS" else "FAIL",
        evidence = evidence,
        message = message
    )

    private fun Map<String, Any?>.workflowEvidence(key: String): ClosureWorkflowEvidence {
        val raw = map(key)
        if (raw.isEmpty()) return ClosureWorkflowEvidence.ABSENT
        return ClosureWorkflowEvidence(
            status = raw.string("status"),
            workflow = raw.string("workflow"),
            runNumber = raw.string("runNumber").toIntOrNull(),
            runId = raw.string("runId").toLongOrNull(),
            exactHead = raw.string("exactHead"),
            mergeCandidate = raw.string("mergeCandidate"),
            unknownFields = (raw.keys - EVIDENCE_FIELDS).sorted()
        )
    }

    private fun Map<String, Any?>.string(key: String): String = get(key)?.toString().orEmpty()

    private fun Map<String, Any?>.map(key: String): Map<String, Any?> =
        (get(key) as? Map<*, *>)
            ?.entries
            ?.associate { it.key.toString() to it.value }
            .orEmpty()

    companion object {
        const val CHECK_ID = "governance.closure-evidence-boundary-integrity"
        const val WORK_PACKAGE = ".flow-agent/work-packages/v0.9.7.10-bounded-semantic-closure-gate.yaml"
        private val SUPPORTED_PHASES = setOf("CORRECTION_REQUIRED", "READY", "CLOSED")
        private val EVIDENCE_FIELDS = setOf(
            "status",
            "workflow",
            "runNumber",
            "runId",
            "exactHead",
            "mergeCandidate"
        )
    }
}
