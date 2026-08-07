package org.flowlang.adapters.control

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
 * Single orchestration boundary for adapter control materialization.
 *
 * Evidence integrity and plan requirement derivation are independent internal
 * authorities. This class is the only production entry point that combines
 * them into a target decision and reconciles that decision into manifest
 * evidence. Core meaning remains target-neutral throughout.
 */
class AdapterControlMaterializationAuthority(
    private val rootDir: File = File("."),
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry,
    private val documentOverride: AdapterControlMaterializationDocument? = null
) {
    private val document: AdapterControlMaterializationDocument by lazy {
        documentOverride ?: AdapterControlMaterializationLoader.load(rootDir)
    }
    private val evidenceIntegrity = AdapterControlEvidenceIntegrityAuthority(rootDir, targets, projections)

    /**
     * Runtime composition may intentionally expose only a subset of distribution
     * targets. Certify every active target exactly, while the public [analyze]
     * path continues to verify the complete distribution inventory.
     */
    private val certifiedDocument: AdapterControlMaterializationDocument by lazy {
        val loaded = document
        val active = loaded.copy(targets = loaded.targets.filter { it.target in targets })
        val report = evidenceIntegrity.analyze(active)
        check(report.status == "PASS") {
            "Adapter control materialization evidence is invalid: " +
                report.findings.joinToString(" | ") { "${it.code}:${it.target}:${it.family}:${it.message}" }
        }
        active
    }

    fun analyze(
        input: AdapterControlMaterializationDocument = document
    ): AdapterControlMaterializationReport = evidenceIntegrity.analyze(input)

    fun assess(plan: ExecutionPlan, target: String): AdapterControlAssessment {
        require(target in targets) { "Unknown target '$target'." }
        val record = certifiedDocument.targets.single { it.target == target }
        val claims = record.claims.associateBy(AdapterControlClaim::family)
        val requirements = requirementsFor(plan)
        val evidence = requirements.map { requirement ->
            val claim = claims[requirement.family]
            val semanticScopes = AdapterControlSemanticContract.scopesFor(requirement.semantic)
            when {
                requirement.completeness != AdapterControlRequirementCompleteness.COMPLETE -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNKNOWN,
                    detail = "Preserved ${requirement.family.name.lowercase()} metadata lacks the exact scope or value needed for provider certification."
                )
                semanticScopes == null -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNKNOWN,
                    detail = "Control semantic '${requirement.semantic}' is outside the closed adapter control contract."
                )
                requirement.scope !in semanticScopes -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNKNOWN,
                    detail = "Derived scope ${requirement.scope} contradicts the target-neutral semantic scope contract " +
                        "${semanticScopes.sortedBy { it.name }.joinToString()} for '${requirement.semantic}'."
                )
                claim == null -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNKNOWN,
                    detail = "No control-family claim exists for ${requirement.family}."
                )
                requirement.semantic in claim.semantics.supported && requirement.scope !in claim.scopes -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNSUPPORTED,
                    detail = "${claim.mechanism} Supported scopes are ${claim.scopes.sortedBy { it.name }.joinToString()}, " +
                        "not required scope ${requirement.scope}."
                )
                requirement.semantic in claim.semantics.supported -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.SATISFIED,
                    detail = "${claim.mechanism} Scope: ${requirement.scope}. Evidence: ${claim.evidenceReferences.joinToString()}"
                )
                requirement.semantic in claim.semantics.unsupported -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNSUPPORTED,
                    detail = claim.semantics.unsupported.getValue(requirement.semantic)
                )
                requirement.semantic in claim.semantics.unknown -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNKNOWN,
                    detail = claim.semantics.unknown.getValue(requirement.semantic)
                )
                else -> AdapterControlEvidence(
                    requirementId = requirement.id,
                    status = AdapterControlEvidenceStatus.UNKNOWN,
                    detail = "Control semantic '${requirement.semantic}' is absent from the declared partition."
                )
            }
        }
        val blockers = evidence
            .filter { it.status != AdapterControlEvidenceStatus.SATISFIED }
            .map(AdapterControlEvidence::requirementId)
        return AdapterControlAssessment(
            target = target,
            requirements = requirements,
            evidence = evidence,
            decision = if (blockers.isEmpty()) AdapterControlDecision.MATCHED else AdapterControlDecision.BLOCKED,
            blockingRequirementIds = blockers
        )
    }

    fun requireMatched(plan: ExecutionPlan, target: String): AdapterControlAssessment =
        assess(plan, target).also { assessment ->
            if (assessment.decision != AdapterControlDecision.MATCHED) {
                throw UnresolvedAdapterControlMaterializationException(assessment)
            }
        }

    fun reconcileDiagnostic(
        manifest: TargetManifest,
        assessment: AdapterControlAssessment
    ): TargetManifest {
        require(manifest.target == assessment.target) {
            "Diagnostic manifest target '${manifest.target}' does not match control assessment target '${assessment.target}'."
        }
        val metadata = manifest.metadata + assessmentMetadata(assessment)
        if (assessment.decision == AdapterControlDecision.MATCHED) {
            return manifest.copy(metadata = metadata)
        }

        val evidenceById = assessment.evidence.associateBy(AdapterControlEvidence::requirementId)
        require(evidenceById.size == assessment.evidence.size) {
            "Adapter control assessment for '${assessment.target}' contains duplicate evidence ids."
        }
        val issues = assessment.requirements.mapNotNull { requirement ->
            val evidence = evidenceById[requirement.id]
                ?: error("Adapter control requirement '${requirement.id}' has no assessment evidence.")
            if (evidence.status == AdapterControlEvidenceStatus.SATISFIED) return@mapNotNull null
            CompatibilityIssue(
                level = CompatibilityLevel.ERROR,
                target = assessment.target,
                nodeId = requirement.subject,
                feature = "control.${requirement.semantic}.adapter",
                message = "Adapter control requirement '${requirement.id}' at scope ${requirement.scope} is " +
                    "${evidence.status.name.lowercase()}: ${evidence.detail}"
            )
        }
        return AdapterDiagnosticReconciliation.blocked(manifest, metadata, issues)
    }

    fun requirementsFor(plan: ExecutionPlan): List<AdapterControlRequirement> =
        AdapterControlRequirementAuthority.derive(plan)

    private fun assessmentMetadata(assessment: AdapterControlAssessment): Map<String, String> = mapOf(
        "adapterControlEvidenceVersion" to AdapterControlMaterializationLoader.SUPPORTED_VERSION,
        "adapterControlDecision" to assessment.decision.name,
        "adapterControlRequirementCount" to assessment.requirements.size.toString(),
        "adapterControlBlockerCount" to assessment.blockingRequirementIds.size.toString()
    )
}
