package org.flowlang.adapters.continuity

import java.io.File
import org.flowlang.adapters.AdapterDiagnosticReconciliation
import org.flowlang.capabilities.CompatibilityIssue
import org.flowlang.capabilities.CompatibilityLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.targets.builtin.BuiltInTargetProjections

/**
 * Matches resolved Core continuity requirements against adapter-owned evidence.
 *
 * Planning proves that a provider exists in the semantic graph. This authority
 * separately proves that the selected adapter materializes the required transfer
 * and lifetime mechanism. Ordering, registry features and renderer layout are not
 * accepted as substitutes.
 */
class AdapterContinuitySatisfactionAuthority(
    private val rootDir: File = File("."),
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry,
    private val documentOverride: AdapterContinuityEvidenceDocument? = null,
    private val scopedSupports: List<AdapterContinuityScopedSupport> =
        BuiltInAdapterContinuityScopedSupport.declarations
) {
    private val document: AdapterContinuityEvidenceDocument by lazy {
        documentOverride ?: AdapterContinuityEvidenceLoader.load(rootDir)
    }
    private val integrity = AdapterContinuityEvidenceIntegrityAuthority(rootDir, targets, projections)

    private val certifiedDocument: AdapterContinuityEvidenceDocument by lazy {
        val active = document.copy(targets = document.targets.filter { it.target in targets })
        val report = integrity.analyze(active, requireCompletePortfolio = false)
        check(report.status == "PASS") {
            "Adapter continuity evidence is invalid: " + report.findings.joinToString(" | ") {
                "${it.code}:${it.target}:${it.family}:${it.message}"
            }
        }
        val scopedReport = AdapterContinuityScopedSupportIntegrityAuthority(rootDir, scopedSupports).analyze()
        check(scopedReport.status == "PASS") {
            "Scoped adapter continuity evidence is invalid: " + scopedReport.findings.joinToString(" | ") {
                "${it.code}:${it.identity}:${it.message}"
            }
        }
        active
    }

    fun analyze(
        input: AdapterContinuityEvidenceDocument = document
    ): AdapterContinuityEvidenceReport = integrity.analyze(input)

    fun requirementsFor(plan: ExecutionPlan): List<AdapterContinuityRequirement> =
        AdapterContinuityRequirementAuthority.derive(plan)

    fun assess(plan: ExecutionPlan, target: String): AdapterContinuityAssessment {
        require(target in targets) { "Unknown target '$target'." }
        val record = certifiedDocument.targets.single { it.target == target }
        val claims = record.claims.associateBy(AdapterContinuityClaim::family)
        val requirements = requirementsFor(plan)
        val evidence = requirements.map { requirement ->
            val claim = claims[requirement.family]
            val scopedSupport = AdapterContinuityScopedSupportAuthority.matchingSupport(
                plan = plan,
                target = target,
                requirement = requirement,
                declarations = scopedSupports
            )
            when {
                requirement.completeness != AdapterContinuityRequirementCompleteness.RESOLVED ->
                    AdapterContinuityEvidence(
                        requirementId = requirement.id,
                        status = AdapterContinuityEvidenceStatus.UNKNOWN,
                        detail = "Planning continuity is ${requirement.completeness.name.lowercase()}; adapter evidence cannot repair an unresolved semantic relation."
                    )
                scopedSupport != null -> AdapterContinuityEvidence(
                    requirementId = requirement.id,
                    status = AdapterContinuityEvidenceStatus.SATISFIED,
                    detail = "Bounded adapter continuity support '${scopedSupport.identity}' materializes this exact producer, channel and consumer path. Evidence: ${scopedSupport.evidenceReferences.joinToString()}"
                )
                claim == null -> AdapterContinuityEvidence(
                    requirementId = requirement.id,
                    status = AdapterContinuityEvidenceStatus.UNKNOWN,
                    detail = "No adapter continuity claim exists for ${requirement.family}."
                )
                requirement.semantic in claim.semantics.supported -> AdapterContinuityEvidence(
                    requirementId = requirement.id,
                    status = AdapterContinuityEvidenceStatus.SATISFIED,
                    detail = "${claim.mechanism} Evidence: ${claim.evidenceReferences.joinToString()}"
                )
                requirement.semantic in claim.semantics.unsupported -> AdapterContinuityEvidence(
                    requirementId = requirement.id,
                    status = AdapterContinuityEvidenceStatus.UNSUPPORTED,
                    detail = claim.semantics.unsupported.getValue(requirement.semantic)
                )
                requirement.semantic in claim.semantics.unknown -> AdapterContinuityEvidence(
                    requirementId = requirement.id,
                    status = AdapterContinuityEvidenceStatus.UNKNOWN,
                    detail = claim.semantics.unknown.getValue(requirement.semantic)
                )
                else -> AdapterContinuityEvidence(
                    requirementId = requirement.id,
                    status = AdapterContinuityEvidenceStatus.UNKNOWN,
                    detail = "Continuity semantic '${requirement.semantic}' is absent from the declared adapter partition."
                )
            }
        }
        val blockers = evidence.filter { it.status != AdapterContinuityEvidenceStatus.SATISFIED }
            .map(AdapterContinuityEvidence::requirementId)
        return AdapterContinuityAssessment(
            target = target,
            requirements = requirements,
            evidence = evidence,
            decision = if (blockers.isEmpty()) AdapterContinuityDecision.MATCHED else AdapterContinuityDecision.BLOCKED,
            blockingRequirementIds = blockers
        )
    }

    fun requireMatched(plan: ExecutionPlan, target: String): AdapterContinuityAssessment =
        assess(plan, target).also { assessment ->
            if (assessment.decision != AdapterContinuityDecision.MATCHED) {
                throw UnresolvedAdapterContinuitySatisfactionException(assessment)
            }
        }

    fun reconcileDiagnostic(
        manifest: TargetManifest,
        assessment: AdapterContinuityAssessment
    ): TargetManifest {
        require(manifest.target == assessment.target) {
            "Diagnostic manifest target '${manifest.target}' does not match continuity assessment target '${assessment.target}'."
        }
        val metadata = manifest.metadata + assessmentMetadata(assessment)
        if (assessment.decision == AdapterContinuityDecision.MATCHED) {
            return manifest.copy(metadata = metadata)
        }

        val evidenceById = assessment.evidence.associateBy(AdapterContinuityEvidence::requirementId)
        require(evidenceById.size == assessment.evidence.size) {
            "Adapter continuity assessment for '${assessment.target}' contains duplicate evidence ids."
        }
        val issues = assessment.requirements.mapNotNull { requirement ->
            val evidence = evidenceById[requirement.id]
                ?: error("Adapter continuity requirement '${requirement.id}' has no assessment evidence.")
            if (evidence.status == AdapterContinuityEvidenceStatus.SATISFIED) return@mapNotNull null
            CompatibilityIssue(
                level = CompatibilityLevel.ERROR,
                target = assessment.target,
                nodeId = requirement.targetNodeId,
                feature = "continuity.${requirement.semantic}.adapter",
                message = "Adapter continuity requirement '${requirement.id}' from " +
                    "'${requirement.sourceNodeId ?: "unresolved"}' to '${requirement.targetNodeId}' is " +
                    "${evidence.status.name.lowercase()}: ${evidence.detail}"
            )
        }
        return AdapterDiagnosticReconciliation.blocked(manifest, metadata, issues)
    }

    private fun assessmentMetadata(assessment: AdapterContinuityAssessment): Map<String, String> = mapOf(
        "adapterContinuityEvidenceVersion" to AdapterContinuityEvidenceLoader.SUPPORTED_VERSION,
        "adapterContinuityDecision" to assessment.decision.name,
        "adapterContinuityRequirementCount" to assessment.requirements.size.toString(),
        "adapterContinuityBlockerCount" to assessment.blockingRequirementIds.size.toString()
    )
}
