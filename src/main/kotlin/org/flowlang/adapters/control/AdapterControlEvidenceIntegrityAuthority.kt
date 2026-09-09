package org.flowlang.adapters.control

import java.io.File
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.AdapterPortfolioRole
import org.flowlang.adapters.portfolio.AdapterSupportClass
import org.flowlang.capabilities.TargetCapability
import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.providerFor
import org.flowlang.generators.manifest.requireProvider

/**
 * Validates distribution evidence independently from plan requirement matching.
 * It proves inventory, partition, provider, ownership and evidence-reference
 * integrity, but it does not interpret an ExecutionPlan.
 */
internal class AdapterControlEvidenceIntegrityAuthority(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: AdapterCatalog<TargetProjectionProvider>
) {
    private val repositoryRoot = rootDir.canonicalFile.toPath()

    fun analyze(input: AdapterControlMaterializationDocument): AdapterControlMaterializationReport {
        val findings = mutableListOf<AdapterControlMaterializationFinding>()
        if (input.version != AdapterControlMaterializationLoader.SUPPORTED_VERSION) {
            finding(
                findings,
                "CONTROL_MANIFEST_VERSION_UNSUPPORTED",
                "manifest",
                "all",
                "Version '${input.version}' is unsupported; expected '${AdapterControlMaterializationLoader.SUPPORTED_VERSION}'."
            )
        }

        val recordsByTarget = input.targets.groupBy(AdapterControlTargetRecord::target)
        val expectedTargets = targets.keys
        input.targets.filter { it.target.isBlank() }.forEach {
            finding(findings, "CONTROL_TARGET_BLANK", "manifest", "all", "Target identity must not be blank.")
        }
        recordsByTarget.filterValues { it.size > 1 }.forEach { (target, records) ->
            finding(findings, "CONTROL_TARGET_DUPLICATE", target, "all", "Target occurs ${records.size} times.")
        }
        (expectedTargets - recordsByTarget.keys).sorted().forEach { target ->
            finding(findings, "CONTROL_TARGET_MISSING", target, "all", "Target has no control materialization record.")
        }
        (recordsByTarget.keys - expectedTargets).sorted().forEach { target ->
            finding(findings, "CONTROL_TARGET_UNKNOWN", target, "all", "Manifest target is absent from the target registry.")
        }

        val portfolio = AdapterPortfolioLoader.load(rootDir).records.associateBy { it.target }
        input.targets.forEach { record ->
            val claimsByFamily = record.claims.groupBy(AdapterControlClaim::family)
            claimsByFamily.filterValues { it.size > 1 }.forEach { (family, claims) ->
                finding(findings, "CONTROL_FAMILY_DUPLICATE", record.target, family.name, "Family occurs ${claims.size} times.")
            }
            (AdapterControlFamily.entries.toSet() - claimsByFamily.keys).forEach { family ->
                finding(findings, "CONTROL_FAMILY_MISSING", record.target, family.name, "Target does not declare the complete control family contract.")
            }

            val portfolioRecord = portfolio[record.target]
            record.claims.forEach { claim ->
                evaluateClaim(
                    target = record.target,
                    claim = claim,
                    role = portfolioRecord?.role,
                    supportClass = portfolioRecord?.supportClass,
                    findings = findings
                )
            }
        }

        return AdapterControlMaterializationReport(
            status = if (findings.isEmpty()) "PASS" else "FAIL",
            findings = findings,
            targetCount = input.targets.size,
            claimCount = input.targets.sumOf { it.claims.size }
        )
    }

    private fun evaluateClaim(
        target: String,
        claim: AdapterControlClaim,
        role: AdapterPortfolioRole?,
        supportClass: AdapterSupportClass?,
        findings: MutableList<AdapterControlMaterializationFinding>
    ) {
        validateClaimShape(target, claim, findings)
        val expected = AdapterControlSemanticContract.byFamily.getValue(claim.family)
        if (claim.semantics.all != expected) {
            finding(
                findings,
                "CONTROL_SEMANTIC_PARTITION_MISMATCH",
                target,
                claim.family.name,
                "declared=${claim.semantics.all.sorted()} expected=${expected.sorted()}"
            )
        }
        val overlaps = (claim.semantics.supported intersect claim.semantics.unsupported.keys) +
            (claim.semantics.supported intersect claim.semantics.unknown.keys) +
            (claim.semantics.unsupported.keys intersect claim.semantics.unknown.keys)
        if (overlaps.isNotEmpty()) {
            finding(
                findings,
                "CONTROL_SEMANTIC_PARTITION_OVERLAP",
                target,
                claim.family.name,
                overlaps.sorted().joinToString()
            )
        }
        if (!claim.status.matches(claim.semantics, expected)) {
            finding(
                findings,
                "CONTROL_STATUS_PARTITION_MISMATCH",
                target,
                claim.family.name,
                "Status ${claim.status} contradicts the semantic partition."
            )
        }

        val provider = projections.providerFor(target)
        if (claim.semantics.supported.isNotEmpty() && provider == null) {
            finding(
                findings,
                "CONTROL_SUPPORTED_WITHOUT_PROVIDER",
                target,
                claim.family.name,
                "Supported control semantics require a composed provider."
            )
        }
        if (claim.semantics.supported.isNotEmpty() && claim.ownership == AdapterControlOwnership.NONE) {
            finding(
                findings,
                "CONTROL_SUPPORTED_WITHOUT_OWNER",
                target,
                claim.family.name,
                "Supported control semantics require an explicit enforcement owner."
            )
        }
        if (claim.semantics.supported.isNotEmpty()) {
            val expectedSupportedScopes = AdapterControlSemanticContract.supportedScopes(claim.semantics.supported)
            if (claim.scopes != expectedSupportedScopes) {
                finding(
                    findings,
                    "CONTROL_SUPPORTED_SCOPE_CONTRACT_MISMATCH",
                    target,
                    claim.family.name,
                    "Supported semantics require exact scopes ${expectedSupportedScopes.sortedBy { it.name }}; " +
                        "declared=${claim.scopes.sortedBy { it.name }}."
                )
            }
        }
        if (AdapterControlScope.UNSPECIFIED in claim.scopes) {
            finding(
                findings,
                "CONTROL_CLAIM_SCOPE_UNSPECIFIED",
                target,
                claim.family.name,
                "Evidence claims must declare concrete supported or unsupported scopes."
            )
        }
        if (role == AdapterPortfolioRole.SEMANTIC_REFERENCE && claim.status != AdapterControlClaimStatus.UNKNOWN) {
            finding(
                findings,
                "CONTROL_SEMANTIC_REFERENCE_PROMOTED",
                target,
                claim.family.name,
                "Semantic references must remain UNKNOWN."
            )
        }
        if (supportClass == AdapterSupportClass.PROFILE_ONLY && claim.status != AdapterControlClaimStatus.UNKNOWN) {
            finding(
                findings,
                "CONTROL_PROFILE_ONLY_PROMOTED",
                target,
                claim.family.name,
                "Profile-only targets must remain UNKNOWN until a provider is composed."
            )
        }
        if ("approval.manual.inline" in claim.semantics.supported) {
            val approvalDefinition = provider?.nativeProjectionCatalog?.approvalDefinitions
                ?.singleOrNull { it.capability == "approval.manual" }
            if (approvalDefinition == null) {
                finding(
                    findings,
                    "CONTROL_APPROVAL_PROVIDER_EVIDENCE_MISSING",
                    target,
                    claim.family.name,
                    "Manual inline approval has no provider-owned payload definition."
                )
            }
        }
        validateEvidenceReferences(target, claim, findings)
        claim.platformReferences.forEach { reference ->
            if (!reference.startsWith("https://")) {
                finding(
                    findings,
                    "CONTROL_PLATFORM_REFERENCE_INVALID",
                    target,
                    claim.family.name,
                    "Platform reference must be HTTPS: $reference"
                )
            }
        }
    }

    private fun validateClaimShape(
        target: String,
        claim: AdapterControlClaim,
        findings: MutableList<AdapterControlMaterializationFinding>
    ) {
        if (claim.mechanism.isBlank()) {
            finding(findings, "CONTROL_MECHANISM_BLANK", target, claim.family.name, "Mechanism must not be blank.")
        }
        if (claim.scopes.isEmpty()) {
            finding(findings, "CONTROL_SCOPES_EMPTY", target, claim.family.name, "At least one control scope is required.")
        }
        if (claim.evidenceReferences.isEmpty()) {
            finding(findings, "CONTROL_EVIDENCE_MISSING", target, claim.family.name, "At least one repository evidence reference is required.")
        }
        if (claim.evidenceReferences.size != claim.evidenceReferences.toSet().size) {
            finding(findings, "CONTROL_EVIDENCE_DUPLICATE", target, claim.family.name, "Repository evidence references must be unique.")
        }
        if (claim.evidenceReferences.any(String::isBlank)) {
            finding(findings, "CONTROL_EVIDENCE_BLANK", target, claim.family.name, "Repository evidence references must not be blank.")
        }
        if (claim.platformReferences.size != claim.platformReferences.toSet().size) {
            finding(findings, "CONTROL_PLATFORM_REFERENCE_DUPLICATE", target, claim.family.name, "Platform references must be unique.")
        }
        if (claim.prerequisites.size != claim.prerequisites.toSet().size) {
            finding(findings, "CONTROL_PREREQUISITE_DUPLICATE", target, claim.family.name, "Prerequisites must be unique.")
        }
        if (claim.limitations.isEmpty() || claim.limitations.any(String::isBlank)) {
            finding(findings, "CONTROL_LIMITATIONS_INVALID", target, claim.family.name, "At least one non-blank limitation is required.")
        }
        val blankReasons = (claim.semantics.unsupported + claim.semantics.unknown)
            .filter { (semantic, reason) -> semantic.isBlank() || reason.isBlank() }
            .keys
        if (blankReasons.isNotEmpty()) {
            finding(
                findings,
                "CONTROL_SEMANTIC_REASON_BLANK",
                target,
                claim.family.name,
                "Semantic reasons must be non-blank: ${blankReasons.sorted().joinToString()}."
            )
        }
    }

    private fun validateEvidenceReferences(
        target: String,
        claim: AdapterControlClaim,
        findings: MutableList<AdapterControlMaterializationFinding>
    ) {
        val fileParts = claim.evidenceReferences.map { it.substringBefore('#') }
        fileParts.forEachIndexed { index, filePart ->
            val reference = claim.evidenceReferences[index]
            val candidate = runCatching { File(rootDir, filePart).canonicalFile.toPath() }.getOrNull()
            when {
                filePart == AdapterControlMaterializationLoader.PATH -> finding(
                    findings,
                    "CONTROL_EVIDENCE_SELF_REFERENTIAL",
                    target,
                    claim.family.name,
                    "Evidence cannot cite its own authority '$filePart'."
                )
                File(filePart).isAbsolute || candidate == null || !candidate.startsWith(repositoryRoot) -> finding(
                    findings,
                    "CONTROL_EVIDENCE_PATH_OUTSIDE_REPOSITORY",
                    target,
                    claim.family.name,
                    "Evidence must be a repository-relative path: $reference"
                )
                !candidate.toFile().isFile -> finding(
                    findings,
                    "CONTROL_EVIDENCE_UNRESOLVED",
                    target,
                    claim.family.name,
                    "Evidence file does not exist: $reference"
                )
                isSourceEvidence(filePart) && '#' in reference -> {
                    val anchor = reference.substringAfter('#')
                    if (anchor.isBlank()) {
                        finding(
                            findings,
                            "CONTROL_EVIDENCE_ANCHOR_BLANK",
                            target,
                            claim.family.name,
                            "Source evidence reference has a blank anchor: $reference"
                        )
                    } else if (!candidate.toFile().readText().contains(anchor)) {
                        finding(
                            findings,
                            "CONTROL_EVIDENCE_ANCHOR_UNRESOLVED",
                            target,
                            claim.family.name,
                            "Source evidence anchor '$anchor' does not exist in $filePart."
                        )
                    }
                }
            }
        }
        if (fileParts.isNotEmpty() && fileParts.all { it == TARGET_REGISTRY_PATH }) {
            finding(
                findings,
                "CONTROL_EVIDENCE_REGISTRY_ONLY",
                target,
                claim.family.name,
                "Target registry data may corroborate unsupported projection state but cannot be the only repository evidence."
            )
        }
        if (claim.semantics.supported.isNotEmpty() && fileParts.none(::isImplementationEvidence)) {
            finding(
                findings,
                "CONTROL_SUPPORTED_IMPLEMENTATION_EVIDENCE_MISSING",
                target,
                claim.family.name,
                "Supported semantics require at least one independent src/main implementation evidence reference."
            )
        }
        if (claim.semantics.supported.isNotEmpty() && fileParts.none(::isBehaviorEvidence)) {
            finding(
                findings,
                "CONTROL_SUPPORTED_BEHAVIOR_EVIDENCE_MISSING",
                target,
                claim.family.name,
                "Supported semantics require at least one independent src/test behavioral evidence reference."
            )
        }
    }

    private fun isSourceEvidence(path: String): Boolean =
        path.startsWith("src/main/") || path.startsWith("src/test/")

    private fun isImplementationEvidence(path: String): Boolean = path.startsWith("src/main/")

    private fun isBehaviorEvidence(path: String): Boolean = path.startsWith("src/test/")

    private fun AdapterControlClaimStatus.matches(
        semantics: AdapterControlSemanticPartition,
        expected: Set<String>
    ): Boolean = when (this) {
        AdapterControlClaimStatus.SUPPORTED -> semantics.supported == expected
        AdapterControlClaimStatus.PARTIAL -> semantics.supported.isNotEmpty() && semantics.supported != expected
        AdapterControlClaimStatus.UNSUPPORTED -> semantics.supported.isEmpty() && semantics.unknown.isEmpty()
        AdapterControlClaimStatus.UNKNOWN -> semantics.supported.isEmpty() && semantics.unknown.isNotEmpty()
    }

    private fun finding(
        findings: MutableList<AdapterControlMaterializationFinding>,
        code: String,
        target: String,
        family: String,
        message: String
    ) {
        findings += AdapterControlMaterializationFinding(code, target, family, message)
    }

    companion object {
        private const val TARGET_REGISTRY_PATH = "targets/builtin-targets.yaml"
    }
}
