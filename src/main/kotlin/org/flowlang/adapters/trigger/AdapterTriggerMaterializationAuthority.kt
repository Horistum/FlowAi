package org.flowlang.adapters.trigger

import java.io.File
import org.flowlang.adapters.AdapterDiagnosticReconciliation
import org.flowlang.capabilities.CompatibilityIssue
import org.flowlang.capabilities.CompatibilityLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.providerFor
import org.flowlang.generators.manifest.requireProvider
import org.flowlang.planner.ExecutionPlan

/**
 * Matches preserved Core trigger requirements against adapter-owned materialization evidence.
 *
 * The authority never rewrites trigger kind, timing, event identity or delivery semantics.
 * A platform feature flag, a provider name or an approximately equivalent scheduler
 * is not accepted as proof that the selected adapter preserves the authored requirement.
 */
class AdapterTriggerMaterializationAuthority(
    private val rootDir: File = File("."),
    private val targets: Map<String, TargetCapability>,
    private val projections: AdapterCatalog<TargetProjectionProvider>,
    private val documentOverride: AdapterTriggerEvidenceDocument? = null
) {
    private val document: AdapterTriggerEvidenceDocument by lazy {
        documentOverride ?: AdapterTriggerEvidenceLoader.load(rootDir)
    }
    private val integrity = AdapterTriggerEvidenceIntegrityAuthority(rootDir, targets, projections)

    private val certifiedDocument: AdapterTriggerEvidenceDocument by lazy {
        val active = document.copy(targets = document.targets.filter { it.target in targets })
        val report = integrity.analyze(active, requireCompletePortfolio = false)
        check(report.status == "PASS") {
            "Adapter trigger materialization evidence is invalid: " + report.findings.joinToString(" | ") {
                "${it.code}:${it.target}:${it.family}:${it.message}"
            }
        }
        active
    }

    fun analyze(
        input: AdapterTriggerEvidenceDocument = document
    ): AdapterTriggerEvidenceReport = integrity.analyze(input)

    fun requirementsFor(plan: ExecutionPlan): List<AdapterTriggerRequirement> =
        AdapterTriggerRequirementAuthority.derive(plan)

    fun assess(plan: ExecutionPlan, target: String): AdapterTriggerAssessment {
        require(target in targets) { "Unknown target '$target'." }
        val record = certifiedDocument.targets.single { it.target == target }
        val claims = record.claims.associateBy(AdapterTriggerClaim::family)
        val requirements = requirementsFor(plan)
        val evidence = requirements.map { requirement ->
            val claim = claims[requirement.family]
            when {
                requirement.completeness != AdapterTriggerRequirementCompleteness.RESOLVED ->
                    AdapterTriggerEvidence(
                        requirementId = requirement.id,
                        status = AdapterTriggerEvidenceStatus.UNKNOWN,
                        detail = requirement.diagnostic
                            ?: "Trigger requirement is unresolved; adapter evidence cannot repair missing semantic meaning."
                    )
                claim == null -> AdapterTriggerEvidence(
                    requirementId = requirement.id,
                    status = AdapterTriggerEvidenceStatus.UNKNOWN,
                    detail = "No adapter trigger claim exists for ${requirement.family}."
                )
                requirement.semantic in claim.semantics.supported ->
                    evaluateSupported(requirement, claim)
                requirement.semantic in claim.semantics.unsupported -> AdapterTriggerEvidence(
                    requirementId = requirement.id,
                    status = AdapterTriggerEvidenceStatus.UNSUPPORTED,
                    detail = claim.semantics.unsupported.getValue(requirement.semantic)
                )
                requirement.semantic in claim.semantics.unknown -> AdapterTriggerEvidence(
                    requirementId = requirement.id,
                    status = AdapterTriggerEvidenceStatus.UNKNOWN,
                    detail = claim.semantics.unknown.getValue(requirement.semantic)
                )
                else -> AdapterTriggerEvidence(
                    requirementId = requirement.id,
                    status = AdapterTriggerEvidenceStatus.UNKNOWN,
                    detail = "Trigger semantic '${requirement.semantic}' is absent from the declared adapter partition."
                )
            }
        }
        val blockers = evidence.filter { it.status != AdapterTriggerEvidenceStatus.SATISFIED }
            .map(AdapterTriggerEvidence::requirementId)
        return AdapterTriggerAssessment(
            target = target,
            requirements = requirements,
            evidence = evidence,
            decision = if (blockers.isEmpty()) AdapterTriggerDecision.MATCHED else AdapterTriggerDecision.BLOCKED,
            blockingRequirementIds = blockers
        )
    }

    fun requireMatched(plan: ExecutionPlan, target: String): AdapterTriggerAssessment =
        assess(plan, target).also { assessment ->
            if (assessment.decision != AdapterTriggerDecision.MATCHED) {
                throw UnresolvedAdapterTriggerMaterializationException(assessment)
            }
        }

    fun reconcileDiagnostic(
        manifest: TargetManifest,
        assessment: AdapterTriggerAssessment
    ): TargetManifest {
        require(manifest.target == assessment.target) {
            "Diagnostic manifest target '${manifest.target}' does not match trigger assessment target '${assessment.target}'."
        }
        if (assessment.requirements.isEmpty()) {
            require(assessment.evidence.isEmpty() && assessment.blockingRequirementIds.isEmpty()) {
                "Trigger-free assessment for '${assessment.target}' contains synthetic evidence or blockers."
            }
            return manifest
        }
        val metadata = manifest.metadata + assessmentMetadata(assessment)
        if (assessment.decision == AdapterTriggerDecision.MATCHED) {
            return manifest.copy(metadata = metadata)
        }

        val evidenceById = assessment.evidence.associateBy(AdapterTriggerEvidence::requirementId)
        require(evidenceById.size == assessment.evidence.size) {
            "Adapter trigger assessment for '${assessment.target}' contains duplicate evidence ids."
        }
        val issues = assessment.requirements.mapNotNull { requirement ->
            val evidence = evidenceById[requirement.id]
                ?: error("Adapter trigger requirement '${requirement.id}' has no assessment evidence.")
            if (evidence.status == AdapterTriggerEvidenceStatus.SATISFIED) return@mapNotNull null
            CompatibilityIssue(
                level = CompatibilityLevel.ERROR,
                target = assessment.target,
                nodeId = requirement.sourceTriggerId,
                feature = "trigger.${requirement.semantic}.adapter",
                message = "Adapter trigger requirement '${requirement.id}' for '${requirement.sourceTriggerId}' is " +
                    "${evidence.status.name.lowercase()}: ${evidence.detail}"
            )
        }
        return AdapterDiagnosticReconciliation.blocked(manifest, metadata, issues)
    }

    private fun evaluateSupported(
        requirement: AdapterTriggerRequirement,
        claim: AdapterTriggerClaim
    ): AdapterTriggerEvidence {
        val violation = constraintViolation(requirement, claim)
        return if (violation == null) {
            AdapterTriggerEvidence(
                requirementId = requirement.id,
                status = AdapterTriggerEvidenceStatus.SATISFIED,
                detail = "${claim.mechanism} Evidence: ${claim.evidenceReferences.joinToString()}"
            )
        } else {
            AdapterTriggerEvidence(
                requirementId = requirement.id,
                status = AdapterTriggerEvidenceStatus.UNSUPPORTED,
                detail = violation
            )
        }
    }

    private fun constraintViolation(
        requirement: AdapterTriggerRequirement,
        claim: AdapterTriggerClaim
    ): String? {
        val constraints = claim.constraints
        val unsupportedWorkflows = requirement.workflows.filterNot { it in constraints.workflowScopes }
        if (unsupportedWorkflows.isNotEmpty()) {
            return "Adapter mechanism does not preserve workflow scopes: ${unsupportedWorkflows.sorted().joinToString()}."
        }
        val unsupportedParams = requirement.params.keys - constraints.parameterNames
        if (unsupportedParams.isNotEmpty()) {
            return "Adapter mechanism does not preserve trigger parameters: ${unsupportedParams.sorted().joinToString()}."
        }
        return when (requirement.family) {
            AdapterTriggerFamily.MANUAL -> null
            AdapterTriggerFamily.CRON -> cronViolation(requirement, constraints)
            AdapterTriggerFamily.EVENT,
            AdapterTriggerFamily.WEBHOOK -> {
                val event = requirement.event.orEmpty()
                if (event !in constraints.eventNames) {
                    "Adapter mechanism does not preserve event identity '$event'."
                } else {
                    null
                }
            }
            AdapterTriggerFamily.INTERVAL,
            AdapterTriggerFamily.CALENDAR,
            AdapterTriggerFamily.UNKNOWN ->
                "Adapter evidence cannot materialize trigger family ${requirement.family}."
        }
    }

    private fun cronViolation(
        requirement: AdapterTriggerRequirement,
        constraints: AdapterTriggerClaimConstraints
    ): String? {
        val expression = requirement.expression.orEmpty()
        if (
            constraints.expressionMode == AdapterTriggerExpressionMode.POSIX_CRON_5_FIELD &&
            !AdapterTriggerRequirementAuthority.isPortablePosixCron(expression)
        ) {
            return "CRON expression '$expression' is not a portable five-field POSIX expression."
        }
        return when (constraints.timezoneMode) {
            AdapterTriggerTimezoneMode.FORBIDDEN -> requirement.timezone?.let {
                "Adapter mechanism cannot preserve explicit timezone '$it'."
            }
            AdapterTriggerTimezoneMode.IANA_OPTIONAL -> requirement.timezone
                ?.takeUnless(AdapterTriggerRequirementAuthority::isIanaTimezone)
                ?.let { "Timezone '$it' is not a valid IANA timezone identity." }
            AdapterTriggerTimezoneMode.NOT_APPLICABLE -> requirement.timezone?.let {
                "Adapter mechanism marks timezone as not applicable but requirement declares '$it'."
            }
        }
    }

    private fun assessmentMetadata(assessment: AdapterTriggerAssessment): Map<String, String> = mapOf(
        "adapterTriggerEvidenceVersion" to AdapterTriggerEvidenceLoader.SUPPORTED_VERSION,
        "adapterTriggerDecision" to assessment.decision.name,
        "adapterTriggerRequirementCount" to assessment.requirements.size.toString(),
        "adapterTriggerBlockerCount" to assessment.blockingRequirementIds.size.toString(),
        "adapterTriggerFamilies" to assessment.requirements
            .map { it.family.name }
            .distinct()
            .sorted()
            .joinToString(",")
    )
}
