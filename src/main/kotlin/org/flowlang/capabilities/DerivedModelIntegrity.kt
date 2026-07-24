package org.flowlang.capabilities

import org.flowlang.planner.ExecutionPlan

/**
 * Stable diagnostic emitted when a derived public report contradicts its source evidence.
 */
data class DerivedModelIntegrityIssue(
    val code: String,
    val path: String,
    val message: String
)

class InvalidDerivedModelException(
    val issues: List<DerivedModelIntegrityIssue>
) : IllegalArgumentException(
    "Derived model integrity validation failed: " +
        issues.joinToString("; ") { "${it.code} at ${it.path}: ${it.message}" }
)

/**
 * Validates report fields that are derived from canonical candidates or target entries.
 *
 * Public reports intentionally keep convenient summary fields, but those fields are not
 * independent authority. They must remain reproducible from the detailed evidence carried
 * by the same report. This validator fails closed when a caller combines artifacts from
 * different plans, forges a recommendation, or mutates a summary bucket independently.
 */
object DerivedModelIntegrityAuthority {
    fun requireNegotiation(report: TargetCapabilityNegotiationReport): TargetCapabilityNegotiationReport {
        val issues = negotiationIssues(report)
        if (issues.isNotEmpty()) throw InvalidDerivedModelException(issues)
        return report
    }

    fun requireSelection(report: TargetSelectionReport): TargetSelectionReport {
        val issues = selectionIssues(report)
        if (issues.isNotEmpty()) throw InvalidDerivedModelException(issues)
        return report
    }

    fun requireTraceInputs(
        plan: ExecutionPlan,
        strict: Boolean,
        requestedTarget: String,
        configuredTargets: Set<String>,
        negotiation: TargetCapabilityNegotiationReport,
        selection: TargetSelectionReport
    ) {
        val issues = mutableListOf<DerivedModelIntegrityIssue>()
        issues += negotiationIssues(negotiation)
        issues += selectionIssues(selection)

        if (negotiation.planVersion != plan.planVersion) {
            issues += issue(
                "DERIVED_TRACE_NEGOTIATION_PLAN_VERSION_MISMATCH",
                "negotiation.planVersion",
                "Negotiation plan version '${negotiation.planVersion}' does not match '${plan.planVersion}'."
            )
        }
        if (selection.planVersion != plan.planVersion) {
            issues += issue(
                "DERIVED_TRACE_SELECTION_PLAN_VERSION_MISMATCH",
                "selection.planVersion",
                "Selection plan version '${selection.planVersion}' does not match '${plan.planVersion}'."
            )
        }
        if (negotiation.flowName != plan.flowName) {
            issues += issue(
                "DERIVED_TRACE_NEGOTIATION_FLOW_MISMATCH",
                "negotiation.flowName",
                "Negotiation flow '${negotiation.flowName}' does not match '${plan.flowName}'."
            )
        }
        if (selection.flowName != plan.flowName) {
            issues += issue(
                "DERIVED_TRACE_SELECTION_FLOW_MISMATCH",
                "selection.flowName",
                "Selection flow '${selection.flowName}' does not match '${plan.flowName}'."
            )
        }
        if (selection.strict != strict) {
            issues += issue(
                "DERIVED_TRACE_STRICTNESS_MISMATCH",
                "selection.strict",
                "Selection strictness '${selection.strict}' does not match requested strictness '$strict'."
            )
        }

        val negotiationTargets = negotiation.targets.map { it.target }.toSet()
        val selectionTargets = selection.candidates.map { it.target }.toSet()
        if (negotiationTargets != selectionTargets) {
            issues += issue(
                "DERIVED_TRACE_TARGET_SET_MISMATCH",
                "targets",
                "Negotiation targets ${negotiationTargets.sorted()} do not match selection targets ${selectionTargets.sorted()}."
            )
        }
        if (configuredTargets.isNotEmpty() && negotiationTargets != configuredTargets) {
            issues += issue(
                "DERIVED_TRACE_CONFIGURED_TARGET_SET_MISMATCH",
                "targets",
                "Derived artifacts evaluate ${negotiationTargets.sorted()}, expected ${configuredTargets.sorted()}."
            )
        }
        if (requestedTarget.isNotBlank() && requestedTarget !in configuredTargets) {
            issues += issue(
                "DERIVED_TRACE_REQUESTED_TARGET_UNKNOWN",
                "requestedTarget",
                "Requested target '$requestedTarget' is not present in the configured target registry."
            )
        }

        val negotiationBlocked = negotiation.blockedTargets.toSet()
        val selectionBlocked = selection.blockedTargets.toSet()
        if (negotiationBlocked != selectionBlocked) {
            issues += issue(
                "DERIVED_TRACE_BLOCKED_TARGETS_MISMATCH",
                "blockedTargets",
                "Negotiation blocked targets ${negotiationBlocked.sorted()} do not match selection blocked targets ${selectionBlocked.sorted()}."
            )
        }
        if (selection.recommendedTarget.isBlank() != negotiation.recommendedTargets.isEmpty()) {
            issues += issue(
                "DERIVED_TRACE_RECOMMENDATION_PRESENCE_MISMATCH",
                "recommendedTarget",
                "Negotiation and selection disagree on whether an executable recommendation exists."
            )
        }
        if (
            selection.recommendedTarget.isNotBlank() &&
            selection.recommendedTarget !in negotiation.recommendedTargets
        ) {
            issues += issue(
                "DERIVED_TRACE_RECOMMENDATION_MISMATCH",
                "recommendedTarget",
                "Selection recommends '${selection.recommendedTarget}', but negotiation recommendations are ${negotiation.recommendedTargets.sorted()}."
            )
        }

        if (issues.isNotEmpty()) throw InvalidDerivedModelException(issues.distinct())
    }

    fun negotiationIssues(report: TargetCapabilityNegotiationReport): List<DerivedModelIntegrityIssue> {
        val issues = mutableListOf<DerivedModelIntegrityIssue>()
        val required = report.requiredCapabilities
        val requiredSet = required.toSet()
        if (required.size != requiredSet.size) {
            issues += issue(
                "DERIVED_NEGOTIATION_REQUIRED_CAPABILITY_DUPLICATE",
                "requiredCapabilities",
                "Required capabilities must be unique."
            )
        }
        if (report.targets.isEmpty()) {
            issues += issue(
                "DERIVED_NEGOTIATION_TARGETS_EMPTY",
                "targets",
                "Negotiation must evaluate at least one target."
            )
        }
        val targetNames = report.targets.map { it.target }
        if (targetNames.size != targetNames.toSet().size) {
            issues += issue(
                "DERIVED_NEGOTIATION_TARGET_DUPLICATE",
                "targets",
                "Negotiation target entries must be unique."
            )
        }
        if (report.portabilityScore !in 0.0..1.0) {
            issues += issue(
                "DERIVED_NEGOTIATION_PORTABILITY_SCORE_INVALID",
                "portabilityScore",
                "Plan portability score must be normalized to 0.0..1.0."
            )
        }

        report.targets.forEachIndexed { index, entry ->
            val path = "targets[$index]"
            if (entry.target.isBlank()) {
                issues += issue("DERIVED_NEGOTIATION_TARGET_BLANK", "$path.target", "Target id must not be blank.")
            }
            if (entry.portabilityScore !in 0.0..1.0) {
                issues += issue(
                    "DERIVED_NEGOTIATION_TARGET_SCORE_INVALID",
                    "$path.portabilityScore",
                    "Target portability score must be normalized to 0.0..1.0."
                )
            }
            val partitions = listOf(entry.supported, entry.partial, entry.unsupported, entry.requiresRuntime)
            val flattened = partitions.flatten()
            if (flattened.size != flattened.toSet().size) {
                issues += issue(
                    "DERIVED_NEGOTIATION_CAPABILITY_PARTITION_OVERLAP",
                    path,
                    "A capability must appear in exactly one support partition for target '${entry.target}'."
                )
            }
            if (flattened.toSet() != requiredSet) {
                issues += issue(
                    "DERIVED_NEGOTIATION_CAPABILITY_PARTITION_INCOMPLETE",
                    path,
                    "Target '${entry.target}' support partitions do not exactly cover required capabilities."
                )
            }
            val expectedStatus = when {
                entry.unsupported.isNotEmpty() || entry.issues.any { it.level == CompatibilityLevel.ERROR } -> SupportLevel.UNSUPPORTED
                entry.partial.isNotEmpty() ||
                    entry.requiresRuntime.isNotEmpty() ||
                    entry.issues.any { it.level == CompatibilityLevel.WARNING } -> SupportLevel.PARTIAL
                else -> SupportLevel.SUPPORTED
            }
            if (!report.readinessEvidenceAvailable && entry.status != expectedStatus) {
                issues += issue(
                    "DERIVED_NEGOTIATION_PRELIMINARY_STATUS_MISMATCH",
                    "$path.status",
                    "Preliminary status '${entry.status}' is not derivable from support partitions; expected '$expectedStatus'."
                )
            }
        }

        val portableSet = report.portableCapabilities.toSet()
        val targetSpecificSet = report.targetSpecificCapabilities.toSet()
        if (report.portableCapabilities.size != portableSet.size) {
            issues += issue("DERIVED_NEGOTIATION_PORTABLE_DUPLICATE", "portableCapabilities", "Portable capabilities must be unique.")
        }
        if (report.targetSpecificCapabilities.size != targetSpecificSet.size) {
            issues += issue("DERIVED_NEGOTIATION_TARGET_SPECIFIC_DUPLICATE", "targetSpecificCapabilities", "Target-specific capabilities must be unique.")
        }
        if ((portableSet intersect targetSpecificSet).isNotEmpty()) {
            issues += issue(
                "DERIVED_NEGOTIATION_CAPABILITY_CLASSIFICATION_OVERLAP",
                "portableCapabilities",
                "A capability cannot be both portable and target-specific."
            )
        }
        if ((portableSet + targetSpecificSet) != requiredSet) {
            issues += issue(
                "DERIVED_NEGOTIATION_CAPABILITY_CLASSIFICATION_INCOMPLETE",
                "portableCapabilities",
                "Portable and target-specific classifications must exactly cover required capabilities."
            )
        }

        val targetsByName = report.targets.associateBy { it.target }
        val expectedBlocked = report.targets
            .filter { it.status == SupportLevel.UNSUPPORTED || it.unsupported.isNotEmpty() }
            .map { it.target }
            .distinct()
            .sorted()
        if (report.blockedTargets.distinct().sorted() != expectedBlocked) {
            issues += issue(
                "DERIVED_NEGOTIATION_BLOCKED_TARGETS_MISMATCH",
                "blockedTargets",
                "Blocked target summary ${report.blockedTargets.sorted()} does not match derived targets $expectedBlocked."
            )
        }
        if (report.recommendedTargets.size != report.recommendedTargets.toSet().size) {
            issues += issue(
                "DERIVED_NEGOTIATION_RECOMMENDATION_DUPLICATE",
                "recommendedTargets",
                "Recommended targets must be unique."
            )
        }
        if (!report.readinessEvidenceAvailable && report.recommendedTargets.isNotEmpty()) {
            issues += issue(
                "DERIVED_NEGOTIATION_RECOMMENDATION_WITHOUT_EVIDENCE",
                "recommendedTargets",
                "Capability-only negotiation cannot recommend an executable target."
            )
        }
        report.recommendedTargets.forEach { target ->
            val entry = targetsByName[target]
            when {
                entry == null -> issues += issue(
                    "DERIVED_NEGOTIATION_RECOMMENDATION_UNKNOWN",
                    "recommendedTargets",
                    "Recommended target '$target' has no target entry."
                )
                entry.status != SupportLevel.SUPPORTED -> issues += issue(
                    "DERIVED_NEGOTIATION_RECOMMENDATION_NOT_SUPPORTED",
                    "recommendedTargets",
                    "Recommended target '$target' has effective status '${entry.status}'."
                )
                target in report.blockedTargets -> issues += issue(
                    "DERIVED_NEGOTIATION_RECOMMENDATION_BLOCKED",
                    "recommendedTargets",
                    "Recommended target '$target' is also classified as blocked."
                )
            }
        }
        if (report.readinessEvidenceAvailable) {
            val expectedRecommended = report.targets
                .filter { it.status == SupportLevel.SUPPORTED }
                .map { it.target }
                .sorted()
            if (report.recommendedTargets.distinct().sorted() != expectedRecommended) {
                issues += issue(
                    "DERIVED_NEGOTIATION_RECOMMENDED_TARGETS_MISMATCH",
                    "recommendedTargets",
                    "Readiness-aware recommendations ${report.recommendedTargets.sorted()} do not match supported effective targets $expectedRecommended."
                )
            }
        }

        report.blockingPortabilityIssues.forEachIndexed { index, portabilityIssue ->
            if (portabilityIssue.target !in targetsByName || portabilityIssue.capability !in requiredSet) {
                issues += issue(
                    "DERIVED_NEGOTIATION_PORTABILITY_ISSUE_ORPHAN",
                    "blockingPortabilityIssues[$index]",
                    "Portability issue must reference a known target and required capability."
                )
            }
        }
        report.requiredWorkarounds.forEachIndexed { index, workaround ->
            if (workaround.target !in targetsByName || workaround.capability !in requiredSet) {
                issues += issue(
                    "DERIVED_NEGOTIATION_WORKAROUND_ORPHAN",
                    "requiredWorkarounds[$index]",
                    "Workaround must reference a known target and required capability."
                )
            }
        }
        return issues.distinct()
    }

    fun selectionIssues(report: TargetSelectionReport): List<DerivedModelIntegrityIssue> {
        val issues = mutableListOf<DerivedModelIntegrityIssue>()
        if (report.candidates.isEmpty()) {
            issues += issue("DERIVED_SELECTION_CANDIDATES_EMPTY", "candidates", "Selection must contain at least one target candidate.")
            return issues
        }
        val targetNames = report.candidates.map { it.target }
        if (targetNames.size != targetNames.toSet().size) {
            issues += issue("DERIVED_SELECTION_TARGET_DUPLICATE", "candidates", "Selection target candidates must be unique.")
        }
        val expectedRanks = (1..report.candidates.size).toList()
        if (report.candidates.map { it.rank } != expectedRanks) {
            issues += issue(
                "DERIVED_SELECTION_RANKS_INVALID",
                "candidates.rank",
                "Candidate ranks must be contiguous and match report order."
            )
        }

        report.candidates.forEachIndexed { index, candidate ->
            val path = "candidates[$index]"
            if (candidate.target.isBlank()) {
                issues += issue("DERIVED_SELECTION_TARGET_BLANK", "$path.target", "Candidate target id must not be blank.")
            }
            if (candidate.targetPortabilityScore !in 0.0..1.0) {
                issues += issue(
                    "DERIVED_SELECTION_PORTABILITY_SCORE_INVALID",
                    "$path.targetPortabilityScore",
                    "Candidate portability score must be normalized to 0.0..1.0."
                )
            }
            if (candidate.blockerCount < 0 || candidate.warningCount < 0) {
                issues += issue(
                    "DERIVED_SELECTION_FINDING_COUNT_INVALID",
                    path,
                    "Blocker and warning counts must not be negative."
                )
            }
            if (candidate.readiness == ExecutionReadinessStatus.BLOCKED && candidate.generationAllowed) {
                issues += issue(
                    "DERIVED_SELECTION_BLOCKED_GENERATION_ALLOWED",
                    "$path.generationAllowed",
                    "Blocked candidate '${candidate.target}' cannot allow generation."
                )
            }
            if (!candidate.readinessEvidenceAvailable) {
                if (candidate.productionReady || candidate.executable) {
                    issues += issue(
                        "DERIVED_SELECTION_EXECUTABLE_WITHOUT_EVIDENCE",
                        path,
                        "Candidate '${candidate.target}' cannot be executable or production-ready without readiness evidence."
                    )
                }
                if (
                    candidate.materializationReadiness != MaterializationReadinessStatus.NOT_EVALUATED ||
                    candidate.projectionReadiness != ProjectionReadinessStatus.NOT_EVALUATED
                ) {
                    issues += issue(
                        "DERIVED_SELECTION_UNEVALUATED_STATUS_MISMATCH",
                        path,
                        "Candidate '${candidate.target}' declares concrete readiness without readiness evidence."
                    )
                }
            }
            if (candidate.productionReady || candidate.executable) {
                val executableShape = candidate.readinessEvidenceAvailable &&
                    candidate.readiness == ExecutionReadinessStatus.READY &&
                    candidate.generationAllowed &&
                    candidate.productionReady &&
                    candidate.executable &&
                    candidate.compatibilityStatus == SupportLevel.SUPPORTED &&
                    candidate.materializationReadiness == MaterializationReadinessStatus.COMPLETE &&
                    candidate.projectionReadiness == ProjectionReadinessStatus.EXECUTABLE
                if (!executableShape) {
                    issues += issue(
                        "DERIVED_SELECTION_EXECUTABLE_SHAPE_INVALID",
                        path,
                        "Executable recommendation evidence for '${candidate.target}' is internally inconsistent."
                    )
                }
            }
        }

        val expectedReady = mutableListOf<String>()
        val expectedDegraded = mutableListOf<String>()
        val expectedBlocked = mutableListOf<String>()
        report.candidates.forEach { candidate ->
            when {
                candidate.readiness == ExecutionReadinessStatus.BLOCKED -> expectedBlocked += candidate.target
                !candidate.readinessEvidenceAvailable -> expectedDegraded += candidate.target
                candidate.readiness == ExecutionReadinessStatus.READY -> expectedReady += candidate.target
                else -> expectedDegraded += candidate.target
            }
        }
        if (report.readyTargets != expectedReady) {
            issues += issue(
                "DERIVED_SELECTION_READY_TARGETS_MISMATCH",
                "readyTargets",
                "Ready target summary ${report.readyTargets} does not match derived targets $expectedReady."
            )
        }
        if (report.degradedTargets != expectedDegraded) {
            issues += issue(
                "DERIVED_SELECTION_DEGRADED_TARGETS_MISMATCH",
                "degradedTargets",
                "Degraded target summary ${report.degradedTargets} does not match derived targets $expectedDegraded."
            )
        }
        if (report.blockedTargets != expectedBlocked) {
            issues += issue(
                "DERIVED_SELECTION_BLOCKED_TARGETS_MISMATCH",
                "blockedTargets",
                "Blocked target summary ${report.blockedTargets} does not match derived targets $expectedBlocked."
            )
        }

        val expectedRecommended = report.candidates
            .firstOrNull {
                it.readinessEvidenceAvailable &&
                    it.readiness == ExecutionReadinessStatus.READY &&
                    it.generationAllowed &&
                    it.productionReady &&
                    it.executable &&
                    it.compatibilityStatus == SupportLevel.SUPPORTED &&
                    it.materializationReadiness == MaterializationReadinessStatus.COMPLETE &&
                    it.projectionReadiness == ProjectionReadinessStatus.EXECUTABLE
            }
            ?.target
            .orEmpty()
        if (report.recommendedTarget != expectedRecommended) {
            issues += issue(
                "DERIVED_SELECTION_RECOMMENDATION_MISMATCH",
                "recommendedTarget",
                "Recommended target '${report.recommendedTarget}' does not match derived recommendation '$expectedRecommended'."
            )
        }
        return issues.distinct()
    }

    private fun issue(code: String, path: String, message: String): DerivedModelIntegrityIssue =
        DerivedModelIntegrityIssue(code, path, message)
}
