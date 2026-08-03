package org.flowlang.adapters.trigger

import java.io.File
import org.flowlang.adapters.portfolio.AdapterPortfolioDocument
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.AdapterPortfolioRole
import org.flowlang.adapters.portfolio.AdapterSupportClass
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.targets.builtin.BuiltInTargetProjections

/**
 * Validates adapter-owned trigger materialization evidence before runtime use.
 *
 * Target registry feature flags and platform documentation are compatibility
 * context only. A supported trigger claim requires a composed provider, concrete
 * production rendering behavior and an independent repository behavior test.
 */
class AdapterTriggerEvidenceIntegrityAuthority(
    private val rootDir: File = File("."),
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry,
    private val portfolio: AdapterPortfolioDocument = AdapterPortfolioLoader.load(rootDir)
) {
    fun analyze(
        document: AdapterTriggerEvidenceDocument = AdapterTriggerEvidenceLoader.load(rootDir),
        requireCompletePortfolio: Boolean = true
    ): AdapterTriggerEvidenceReport {
        val findings = mutableListOf<AdapterTriggerFinding>()
        val duplicateTargets = document.targets.groupingBy(AdapterTriggerTargetRecord::target)
            .eachCount().filterValues { it > 1 }.keys.sorted()
        duplicateTargets.forEach { target ->
            findings += finding(
                "ADAPTER_TRIGGER_TARGET_DUPLICATE",
                target,
                "",
                "Target has more than one trigger materialization record."
            )
        }

        val declaredTargets = document.targets.map(AdapterTriggerTargetRecord::target).toSet()
        val portfolioTargets = portfolio.records.map { it.target }.toSet()
        (targets.keys - declaredTargets).sorted().forEach { target ->
            findings += finding(
                "ADAPTER_TRIGGER_TARGET_MISSING",
                target,
                "",
                "Every active target registry entry requires trigger materialization evidence."
            )
        }
        (declaredTargets - targets.keys).sorted().forEach { target ->
            findings += finding(
                "ADAPTER_TRIGGER_TARGET_UNKNOWN",
                target,
                "",
                "Trigger evidence has no active target registry entry."
            )
        }
        if (requireCompletePortfolio) {
            (portfolioTargets - declaredTargets).sorted().forEach { target ->
                findings += finding(
                    "ADAPTER_TRIGGER_PORTFOLIO_TARGET_MISSING",
                    target,
                    "",
                    "Every adapter portfolio record requires trigger materialization evidence."
                )
            }
        }

        val portfolioByTarget = portfolio.records.associateBy { it.target }
        document.targets.sortedBy { it.target }.forEach { record ->
            val portfolioRecord = portfolioByTarget[record.target]
            val claimsByFamily = record.claims.groupBy(AdapterTriggerClaim::family)
            claimsByFamily.filterValues { it.size > 1 }.keys.sortedBy { it.name }.forEach { family ->
                findings += finding(
                    "ADAPTER_TRIGGER_FAMILY_DUPLICATE",
                    record.target,
                    family.name,
                    "Trigger family is declared more than once."
                )
            }
            (AdapterTriggerSemanticContract.requiredFamilies - claimsByFamily.keys)
                .sortedBy { it.name }
                .forEach { family ->
                    findings += finding(
                        "ADAPTER_TRIGGER_FAMILY_MISSING",
                        record.target,
                        family.name,
                        "Required trigger family is missing."
                    )
                }
            (claimsByFamily.keys - AdapterTriggerSemanticContract.requiredFamilies)
                .sortedBy { it.name }
                .forEach { family ->
                    findings += finding(
                        "ADAPTER_TRIGGER_FAMILY_UNKNOWN",
                        record.target,
                        family.name,
                        "Trigger evidence contains a family outside the closed adapter contract."
                    )
                }

            if (portfolioRecord == null) {
                findings += finding(
                    "ADAPTER_TRIGGER_PORTFOLIO_RECORD_MISSING",
                    record.target,
                    "",
                    "Adapter portfolio record is missing."
                )
            }
            record.claims.sortedBy { it.family.name }.forEach { claim ->
                validateClaim(record.target, claim, portfolioRecord, findings)
            }
        }

        return AdapterTriggerEvidenceReport(
            status = if (findings.isEmpty()) "PASS" else "FAIL",
            findings = findings.sortedWith(compareBy({ it.target }, { it.family }, { it.code }, { it.message })),
            targetCount = document.targets.size,
            claimCount = document.targets.sumOf { it.claims.size }
        )
    }

    private fun validateClaim(
        target: String,
        claim: AdapterTriggerClaim,
        portfolioRecord: org.flowlang.adapters.portfolio.AdapterPortfolioRecord?,
        findings: MutableList<AdapterTriggerFinding>
    ) {
        val family = claim.family.name
        val contract = AdapterTriggerSemanticContract.byFamily[claim.family]
        if (contract == null) {
            findings += finding(
                "ADAPTER_TRIGGER_SEMANTIC_CONTRACT_MISSING",
                target,
                family,
                "Trigger family has no closed semantic contract."
            )
            return
        }

        val overlaps = (claim.semantics.supported intersect claim.semantics.unsupported.keys) +
            (claim.semantics.supported intersect claim.semantics.unknown.keys) +
            (claim.semantics.unsupported.keys intersect claim.semantics.unknown.keys)
        if (overlaps.isNotEmpty()) {
            findings += finding(
                "ADAPTER_TRIGGER_SEMANTIC_OVERLAP",
                target,
                family,
                "Semantic partition overlaps: ${overlaps.sorted().joinToString()}."
            )
        }
        val unknownSemantics = claim.semantics.all - contract
        val missingSemantics = contract - claim.semantics.all
        if (unknownSemantics.isNotEmpty()) {
            findings += finding(
                "ADAPTER_TRIGGER_SEMANTIC_UNKNOWN",
                target,
                family,
                "Claim contains semantics outside the closed family contract: ${unknownSemantics.sorted().joinToString()}."
            )
        }
        if (missingSemantics.isNotEmpty()) {
            findings += finding(
                "ADAPTER_TRIGGER_SEMANTIC_MISSING",
                target,
                family,
                "Claim does not classify every family semantic: ${missingSemantics.sorted().joinToString()}."
            )
        }
        val expectedStatus = runCatching {
            AdapterTriggerSemanticContract.expectedStatus(claim.semantics)
        }.getOrElse {
            findings += finding(
                "ADAPTER_TRIGGER_PARTITION_MIXED",
                target,
                family,
                it.message ?: "Trigger semantic partition mixes evidence polarities."
            )
            null
        }
        if (expectedStatus != null && claim.status != expectedStatus) {
            findings += finding(
                "ADAPTER_TRIGGER_STATUS_MISMATCH",
                target,
                family,
                "Claim status ${claim.status} does not match partition status $expectedStatus."
            )
        }

        validateConstraints(target, claim, findings)
        val evidencePaths = claim.evidenceReferences.map { it.substringBefore('#') }
        claim.evidenceReferences.forEach { reference ->
            if (reference.startsWith("http://") || reference.startsWith("https://")) {
                findings += finding(
                    "ADAPTER_TRIGGER_EXTERNAL_EVIDENCE",
                    target,
                    family,
                    "External platform documentation cannot be implementation evidence: $reference"
                )
            }
            val path = reference.substringBefore('#')
            if (!File(rootDir, path).isFile) {
                findings += finding(
                    "ADAPTER_TRIGGER_EVIDENCE_UNRESOLVED",
                    target,
                    family,
                    "Evidence reference '$reference' does not resolve to a repository file."
                )
            }
        }

        val profileOnly = portfolioRecord?.role == AdapterPortfolioRole.SEMANTIC_REFERENCE ||
            portfolioRecord?.supportClass == AdapterSupportClass.PROFILE_ONLY
        if (profileOnly && claim.status != AdapterTriggerClaimStatus.UNKNOWN) {
            findings += finding(
                "ADAPTER_TRIGGER_PROFILE_ONLY_PROMOTED",
                target,
                family,
                "Profile-only and semantic-reference targets must remain UNKNOWN until a provider supplies evidence."
            )
        }

        if (claim.status == AdapterTriggerClaimStatus.SUPPORTED) {
            if (projections.providerFor(target) == null) {
                findings += finding(
                    "ADAPTER_TRIGGER_PROVIDER_MISSING",
                    target,
                    family,
                    "Supported trigger materialization requires a composed projection provider."
                )
            }
            if (portfolioRecord?.role != AdapterPortfolioRole.TARGET_ADAPTER || profileOnly) {
                findings += finding(
                    "ADAPTER_TRIGGER_PORTFOLIO_INELIGIBLE",
                    target,
                    family,
                    "Supported trigger materialization requires a concrete target-adapter portfolio record."
                )
            }
            if (evidencePaths.none { it.startsWith("src/main/") }) {
                findings += finding(
                    "ADAPTER_TRIGGER_IMPLEMENTATION_EVIDENCE_MISSING",
                    target,
                    family,
                    "Supported trigger materialization requires repository production implementation evidence."
                )
            }
            if (evidencePaths.none { it.startsWith("src/test/") || it.startsWith("tests/") }) {
                findings += finding(
                    "ADAPTER_TRIGGER_BEHAVIOR_EVIDENCE_MISSING",
                    target,
                    family,
                    "Supported trigger materialization requires independent behavioral test evidence."
                )
            }
            evidencePaths.filter {
                it == AdapterTriggerEvidenceLoader.PATH || it.startsWith("targets/")
            }.forEach { path ->
                findings += finding(
                    "ADAPTER_TRIGGER_EVIDENCE_SELF_REFERENTIAL",
                    target,
                    family,
                    "Supported trigger materialization cannot cite '$path' as implementation evidence."
                )
            }
        }
    }

    private fun validateConstraints(
        target: String,
        claim: AdapterTriggerClaim,
        findings: MutableList<AdapterTriggerFinding>
    ) {
        val family = claim.family.name
        val constraints = claim.constraints
        if (constraints.workflowScopes.any(String::isBlank)) {
            findings += finding(
                "ADAPTER_TRIGGER_WORKFLOW_SCOPE_BLANK",
                target,
                family,
                "Workflow scope constraints must be non-blank."
            )
        }
        if (constraints.eventNames.any(String::isBlank)) {
            findings += finding(
                "ADAPTER_TRIGGER_EVENT_NAME_BLANK",
                target,
                family,
                "Event-name constraints must be non-blank."
            )
        }
        if (constraints.parameterNames.any(String::isBlank)) {
            findings += finding(
                "ADAPTER_TRIGGER_PARAMETER_NAME_BLANK",
                target,
                family,
                "Parameter-name constraints must be non-blank."
            )
        }
        if (claim.status != AdapterTriggerClaimStatus.SUPPORTED) return
        if (constraints.workflowScopes.isEmpty()) {
            findings += finding(
                "ADAPTER_TRIGGER_WORKFLOW_SCOPE_MISSING",
                target,
                family,
                "Supported trigger materialization must bound the workflow scopes it preserves."
            )
        }
        when (claim.family) {
            AdapterTriggerFamily.MANUAL -> {
                requireConstraint(
                    target,
                    family,
                    constraints.expressionMode == AdapterTriggerExpressionMode.NONE,
                    "ADAPTER_TRIGGER_MANUAL_EXPRESSION_MODE_INVALID",
                    "Manual trigger evidence must not claim an expression language.",
                    findings
                )
                requireConstraint(
                    target,
                    family,
                    constraints.timezoneMode == AdapterTriggerTimezoneMode.NOT_APPLICABLE,
                    "ADAPTER_TRIGGER_MANUAL_TIMEZONE_MODE_INVALID",
                    "Manual trigger evidence must mark timezone as not applicable.",
                    findings
                )
                requireConstraint(
                    target,
                    family,
                    constraints.eventNames.isEmpty() && constraints.parameterNames.isEmpty(),
                    "ADAPTER_TRIGGER_MANUAL_CONSTRAINTS_INVALID",
                    "Manual trigger evidence cannot claim event names or implicit parameters.",
                    findings
                )
            }
            AdapterTriggerFamily.CRON -> {
                requireConstraint(
                    target,
                    family,
                    constraints.expressionMode == AdapterTriggerExpressionMode.POSIX_CRON_5_FIELD,
                    "ADAPTER_TRIGGER_CRON_EXPRESSION_MODE_INVALID",
                    "Supported CRON evidence must require a portable five-field POSIX expression.",
                    findings
                )
                requireConstraint(
                    target,
                    family,
                    constraints.timezoneMode in setOf(
                        AdapterTriggerTimezoneMode.FORBIDDEN,
                        AdapterTriggerTimezoneMode.IANA_OPTIONAL
                    ),
                    "ADAPTER_TRIGGER_CRON_TIMEZONE_MODE_INVALID",
                    "Supported CRON evidence must explicitly forbid timezone or support an optional IANA timezone.",
                    findings
                )
                requireConstraint(
                    target,
                    family,
                    constraints.eventNames.isEmpty() && constraints.parameterNames.isEmpty(),
                    "ADAPTER_TRIGGER_CRON_CONSTRAINTS_INVALID",
                    "CRON trigger evidence cannot claim event names or arbitrary parameters.",
                    findings
                )
            }
            AdapterTriggerFamily.EVENT,
            AdapterTriggerFamily.WEBHOOK -> {
                requireConstraint(
                    target,
                    family,
                    constraints.expressionMode == AdapterTriggerExpressionMode.NONE &&
                        constraints.timezoneMode == AdapterTriggerTimezoneMode.NOT_APPLICABLE,
                    "ADAPTER_TRIGGER_EVENT_CONSTRAINTS_INVALID",
                    "Event-like trigger evidence must not claim scheduling expression or timezone semantics.",
                    findings
                )
                requireConstraint(
                    target,
                    family,
                    constraints.eventNames.isNotEmpty(),
                    "ADAPTER_TRIGGER_EVENT_VOCABULARY_MISSING",
                    "Supported event-like trigger evidence must declare a closed event vocabulary.",
                    findings
                )
            }
            AdapterTriggerFamily.INTERVAL,
            AdapterTriggerFamily.CALENDAR,
            AdapterTriggerFamily.UNKNOWN -> Unit
        }
    }

    private fun requireConstraint(
        target: String,
        family: String,
        valid: Boolean,
        code: String,
        message: String,
        findings: MutableList<AdapterTriggerFinding>
    ) {
        if (!valid) findings += finding(code, target, family, message)
    }

    private fun finding(code: String, target: String, family: String, message: String) =
        AdapterTriggerFinding(code, target, family, message)
}
