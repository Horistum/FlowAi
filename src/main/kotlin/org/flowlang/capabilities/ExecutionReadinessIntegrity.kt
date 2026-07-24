package org.flowlang.capabilities

/** Validates summary fields derived from execution-readiness findings and evidence. */
object ExecutionReadinessIntegrityAuthority {
    fun requireValid(report: ExecutionReadinessReport): ExecutionReadinessReport {
        val issues = issues(report)
        if (issues.isNotEmpty()) throw InvalidDerivedModelException(issues)
        return report
    }

    fun issues(report: ExecutionReadinessReport): List<DerivedModelIntegrityIssue> {
        val issues = mutableListOf<DerivedModelIntegrityIssue>()
        if (report.flowName.isBlank()) {
            issues += issue("DERIVED_READINESS_FLOW_BLANK", "flowName", "Readiness flow name must not be blank.")
        }
        if (report.target.isBlank()) {
            issues += issue("DERIVED_READINESS_TARGET_BLANK", "target", "Readiness target must not be blank.")
        }
        if (report.targetPortabilityScore !in 0.0..1.0 || report.planPortabilityScore !in 0.0..1.0) {
            issues += issue(
                "DERIVED_READINESS_PORTABILITY_SCORE_INVALID",
                "portabilityScore",
                "Readiness portability scores must be normalized to 0.0..1.0."
            )
        }
        if (report.blockers.any { it.severity != ReadinessSeverity.BLOCKER }) {
            issues += issue(
                "DERIVED_READINESS_BLOCKER_SEVERITY_MISMATCH",
                "blockers",
                "Every blocker entry must use BLOCKER severity."
            )
        }
        if (report.warnings.any { it.severity != ReadinessSeverity.WARNING }) {
            issues += issue(
                "DERIVED_READINESS_WARNING_SEVERITY_MISMATCH",
                "warnings",
                "Every warning entry must use WARNING severity."
            )
        }
        if ((report.blockers + report.warnings).any { it.target != report.target }) {
            issues += issue(
                "DERIVED_READINESS_FINDING_TARGET_MISMATCH",
                "findings",
                "Every readiness finding must reference target '${report.target}'."
            )
        }
        if (report.readiness == ExecutionReadinessStatus.BLOCKED && report.generationAllowed) {
            issues += issue(
                "DERIVED_READINESS_BLOCKED_GENERATION_ALLOWED",
                "generationAllowed",
                "Blocked readiness cannot allow target generation."
            )
        }
        if (report.generationAllowed != (report.readiness != ExecutionReadinessStatus.BLOCKED)) {
            issues += issue(
                "DERIVED_READINESS_GENERATION_FLAG_MISMATCH",
                "generationAllowed",
                "Generation allowance must be derived from readiness status."
            )
        }

        if (!report.readinessEvidenceAvailable) {
            if (report.productionReady || report.executable) {
                issues += issue(
                    "DERIVED_READINESS_EXECUTABLE_WITHOUT_EVIDENCE",
                    "readinessEvidenceAvailable",
                    "Preliminary readiness cannot claim executable or production-ready evidence."
                )
            }
            if (
                report.materializationReadiness != MaterializationReadinessStatus.NOT_EVALUATED ||
                report.projectionReadiness != ProjectionReadinessStatus.NOT_EVALUATED
            ) {
                issues += issue(
                    "DERIVED_READINESS_UNEVALUATED_STATUS_MISMATCH",
                    "materializationReadiness",
                    "Preliminary readiness must keep concrete readiness statuses NOT_EVALUATED."
                )
            }
        }

        val executableShape = report.readinessEvidenceAvailable &&
            report.readiness == ExecutionReadinessStatus.READY &&
            report.generationAllowed &&
            report.productionReady &&
            report.executable &&
            report.compatibilityStatus == SupportLevel.SUPPORTED &&
            report.materializationReadiness == MaterializationReadinessStatus.COMPLETE &&
            report.projectionReadiness == ProjectionReadinessStatus.EXECUTABLE &&
            report.blockers.isEmpty()
        if ((report.productionReady || report.executable) && !executableShape) {
            issues += issue(
                "DERIVED_READINESS_EXECUTABLE_SHAPE_INVALID",
                "productionReady",
                "Executable readiness fields are not backed by one complete supported evidence shape."
            )
        }
        if (report.productionReady != report.executable) {
            issues += issue(
                "DERIVED_READINESS_PRODUCTION_EXECUTABLE_MISMATCH",
                "productionReady",
                "Production readiness and executable readiness must agree."
            )
        }

        val expectedReadiness = when {
            report.blockers.isNotEmpty() || report.compatibilityStatus == SupportLevel.UNSUPPORTED -> ExecutionReadinessStatus.BLOCKED
            !report.readinessEvidenceAvailable &&
                report.warnings.isEmpty() &&
                report.compatibilityStatus == SupportLevel.SUPPORTED -> ExecutionReadinessStatus.READY
            executableShape -> ExecutionReadinessStatus.READY
            else -> ExecutionReadinessStatus.DEGRADED
        }
        if (report.readiness != expectedReadiness) {
            issues += issue(
                "DERIVED_READINESS_STATUS_MISMATCH",
                "readiness",
                "Readiness '${report.readiness}' does not match evidence-derived readiness '$expectedReadiness'."
            )
        }
        return issues.distinct()
    }

    private fun issue(code: String, path: String, message: String): DerivedModelIntegrityIssue =
        DerivedModelIntegrityIssue(code, path, message)
}
