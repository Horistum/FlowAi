package org.flowlang.adapters.continuity

import java.io.File
import org.flowlang.adapters.portfolio.AdapterPortfolioDocument
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.AdapterPortfolioRole
import org.flowlang.adapters.portfolio.AdapterSupportClass
import org.flowlang.capabilities.TargetCapability
import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.providerFor
import org.flowlang.generators.manifest.requireProvider

/**
 * Validates adapter-owned continuity evidence before runtime matching.
 *
 * Registry features, provider names and platform documentation are deliberately
 * insufficient. A supported claim requires a composed provider, production code
 * evidence and an independent behavioral test in this repository.
 */
class AdapterContinuityEvidenceIntegrityAuthority(
    private val rootDir: File = File("."),
    private val targets: Map<String, TargetCapability>,
    private val projections: AdapterCatalog<TargetProjectionProvider>,
    private val portfolio: AdapterPortfolioDocument = AdapterPortfolioLoader.load(rootDir)
) {
    fun analyze(
        document: AdapterContinuityEvidenceDocument = AdapterContinuityEvidenceLoader.load(rootDir),
        requireCompletePortfolio: Boolean = true
    ): AdapterContinuityEvidenceReport {
        val findings = mutableListOf<AdapterContinuityFinding>()
        val duplicateTargets = document.targets.groupingBy(AdapterContinuityTargetRecord::target)
            .eachCount().filterValues { it > 1 }.keys.sorted()
        duplicateTargets.forEach { target ->
            findings += finding("ADAPTER_CONTINUITY_TARGET_DUPLICATE", target, "", "Target has more than one continuity record.")
        }

        val declaredTargets = document.targets.map(AdapterContinuityTargetRecord::target).toSet()
        val portfolioTargets = portfolio.records.map { it.target }.toSet()
        (targets.keys - declaredTargets).sorted().forEach { target ->
            findings += finding("ADAPTER_CONTINUITY_TARGET_MISSING", target, "", "Every active target registry entry requires continuity evidence.")
        }
        (declaredTargets - targets.keys).sorted().forEach { target ->
            findings += finding("ADAPTER_CONTINUITY_TARGET_UNKNOWN", target, "", "Continuity evidence has no active target registry entry.")
        }
        if (requireCompletePortfolio) {
            (portfolioTargets - declaredTargets).sorted().forEach { target ->
                findings += finding("ADAPTER_CONTINUITY_PORTFOLIO_TARGET_MISSING", target, "", "Every adapter portfolio record requires continuity evidence.")
            }
        }

        val portfolioByTarget = portfolio.records.associateBy { it.target }
        document.targets.sortedBy { it.target }.forEach { record ->
            val portfolioRecord = portfolioByTarget[record.target]
            val claimsByFamily = record.claims.groupBy(AdapterContinuityClaim::family)
            claimsByFamily.filterValues { it.size > 1 }.keys.sortedBy { it.name }.forEach { family ->
                findings += finding("ADAPTER_CONTINUITY_FAMILY_DUPLICATE", record.target, family.name, "Continuity family is declared more than once.")
            }
            (AdapterContinuityFamily.entries.toSet() - claimsByFamily.keys).sortedBy { it.name }.forEach { family ->
                findings += finding("ADAPTER_CONTINUITY_FAMILY_MISSING", record.target, family.name, "Required continuity family is missing.")
            }

            if (portfolioRecord == null) {
                findings += finding("ADAPTER_CONTINUITY_PORTFOLIO_RECORD_MISSING", record.target, "", "Adapter portfolio record is missing.")
            }

            record.claims.sortedBy { it.family.name }.forEach { claim ->
                validateClaim(record.target, claim, portfolioRecord, findings)
            }
        }

        return AdapterContinuityEvidenceReport(
            status = if (findings.isEmpty()) "PASS" else "FAIL",
            findings = findings.sortedWith(compareBy({ it.target }, { it.family }, { it.code }, { it.message })),
            targetCount = document.targets.size,
            claimCount = document.targets.sumOf { it.claims.size }
        )
    }

    private fun validateClaim(
        target: String,
        claim: AdapterContinuityClaim,
        portfolioRecord: org.flowlang.adapters.portfolio.AdapterPortfolioRecord?,
        findings: MutableList<AdapterContinuityFinding>
    ) {
        val family = claim.family.name
        val contract = AdapterContinuitySemanticContract.byFamily.getValue(claim.family)
        val overlaps = (claim.semantics.supported intersect claim.semantics.unsupported.keys) +
            (claim.semantics.supported intersect claim.semantics.unknown.keys) +
            (claim.semantics.unsupported.keys intersect claim.semantics.unknown.keys)
        if (overlaps.isNotEmpty()) {
            findings += finding(
                "ADAPTER_CONTINUITY_SEMANTIC_OVERLAP",
                target,
                family,
                "Semantic partition overlaps: ${overlaps.sorted().joinToString()}."
            )
        }
        val unknownSemantics = claim.semantics.all - contract
        val missingSemantics = contract - claim.semantics.all
        if (unknownSemantics.isNotEmpty()) {
            findings += finding(
                "ADAPTER_CONTINUITY_SEMANTIC_UNKNOWN",
                target,
                family,
                "Claim contains semantics outside the closed family contract: ${unknownSemantics.sorted().joinToString()}."
            )
        }
        if (missingSemantics.isNotEmpty()) {
            findings += finding(
                "ADAPTER_CONTINUITY_SEMANTIC_MISSING",
                target,
                family,
                "Claim does not classify every family semantic: ${missingSemantics.sorted().joinToString()}."
            )
        }
        val expectedStatus = runCatching {
            AdapterContinuitySemanticContract.expectedStatus(claim.semantics)
        }.getOrElse {
            findings += finding(
                "ADAPTER_CONTINUITY_PARTITION_MIXED",
                target,
                family,
                it.message ?: "Continuity semantic partition mixes evidence polarities."
            )
            null
        }
        if (expectedStatus != null && claim.status != expectedStatus) {
            findings += finding(
                "ADAPTER_CONTINUITY_STATUS_MISMATCH",
                target,
                family,
                "Claim status ${claim.status} does not match partition status $expectedStatus."
            )
        }

        val evidencePaths = claim.evidenceReferences.map { it.substringBefore('#') }
        claim.evidenceReferences.forEach { reference ->
            if (reference.startsWith("http://") || reference.startsWith("https://")) {
                findings += finding(
                    "ADAPTER_CONTINUITY_EXTERNAL_EVIDENCE",
                    target,
                    family,
                    "External platform documentation cannot be implementation evidence: $reference"
                )
            }
            val path = reference.substringBefore('#')
            if (!File(rootDir, path).isFile) {
                findings += finding(
                    "ADAPTER_CONTINUITY_EVIDENCE_UNRESOLVED",
                    target,
                    family,
                    "Evidence reference '$reference' does not resolve to a repository file."
                )
            }
        }

        val profileOnly = portfolioRecord?.role == AdapterPortfolioRole.SEMANTIC_REFERENCE ||
            portfolioRecord?.supportClass == AdapterSupportClass.PROFILE_ONLY
        if (profileOnly && claim.status != AdapterContinuityClaimStatus.UNKNOWN) {
            findings += finding(
                "ADAPTER_CONTINUITY_PROFILE_ONLY_PROMOTED",
                target,
                family,
                "Profile-only and semantic-reference targets must remain UNKNOWN until a provider supplies evidence."
            )
        }

        if (claim.status == AdapterContinuityClaimStatus.SUPPORTED) {
            if (projections.providerFor(target) == null) {
                findings += finding(
                    "ADAPTER_CONTINUITY_PROVIDER_MISSING",
                    target,
                    family,
                    "Supported continuity requires a composed projection provider."
                )
            }
            if (portfolioRecord?.role != AdapterPortfolioRole.TARGET_ADAPTER || profileOnly) {
                findings += finding(
                    "ADAPTER_CONTINUITY_PORTFOLIO_INELIGIBLE",
                    target,
                    family,
                    "Supported continuity requires a concrete target-adapter portfolio record."
                )
            }
            if (evidencePaths.none { it.startsWith("src/main/") }) {
                findings += finding(
                    "ADAPTER_CONTINUITY_IMPLEMENTATION_EVIDENCE_MISSING",
                    target,
                    family,
                    "Supported continuity requires repository production implementation evidence."
                )
            }
            if (evidencePaths.none { it.startsWith("src/test/") || it.startsWith("tests/") }) {
                findings += finding(
                    "ADAPTER_CONTINUITY_BEHAVIOR_EVIDENCE_MISSING",
                    target,
                    family,
                    "Supported continuity requires independent behavioral test evidence."
                )
            }
            evidencePaths.filter {
                it == AdapterContinuityEvidenceLoader.PATH || it.startsWith("targets/")
            }.forEach { path ->
                findings += finding(
                    "ADAPTER_CONTINUITY_EVIDENCE_SELF_REFERENTIAL",
                    target,
                    family,
                    "Supported continuity cannot cite '$path' as implementation evidence."
                )
            }
        }
    }

    private fun finding(code: String, target: String, family: String, message: String) =
        AdapterContinuityFinding(code, target, family, message)
}
