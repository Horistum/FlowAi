package org.flowlang.adapters.rendering

import java.io.File
import org.flowlang.adapters.portfolio.AdapterPortfolioDocument
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.AdapterPortfolioRole
import org.flowlang.adapters.portfolio.AdapterSupportClass
import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.providerFor
import org.flowlang.generators.manifest.requireProvider

/**
 * Certifies distribution-owned renderer evidence independently from one concrete
 * manifest. Provider presence, target registry flags and output syntax are not
 * enough to claim executable artifact rendering.
 */
class AdapterArtifactRenderingEvidenceIntegrityAuthority(
    private val rootDir: File = File("."),
    private val projections: AdapterCatalog<TargetProjectionProvider>,
    private val portfolio: AdapterPortfolioDocument = AdapterPortfolioLoader.load(rootDir)
) {
    private val repositoryRoot = rootDir.canonicalFile.toPath()

    fun analyze(
        document: AdapterArtifactRenderingDocument = AdapterArtifactRenderingEvidenceLoader.load(rootDir),
        requireCompletePortfolio: Boolean = true
    ): AdapterArtifactRenderingEvidenceReport {
        val findings = mutableListOf<AdapterArtifactRenderingFinding>()
        if (document.version != AdapterArtifactRenderingEvidenceLoader.SUPPORTED_VERSION) {
            findings += finding(
                "ADAPTER_RENDERING_VERSION_UNSUPPORTED",
                "manifest",
                "Version '${document.version}' is unsupported; expected '${AdapterArtifactRenderingEvidenceLoader.SUPPORTED_VERSION}'."
            )
        }

        val recordsByTarget = document.targets.groupBy(AdapterArtifactRenderingRecord::target)
        recordsByTarget.filterValues { it.size > 1 }.keys.sorted().forEach { target ->
            findings += finding("ADAPTER_RENDERING_TARGET_DUPLICATE", target, "Target has more than one rendering record.")
        }
        val portfolioByTarget = portfolio.records.associateBy { it.target }
        val declaredTargets = recordsByTarget.keys
        if (requireCompletePortfolio) {
            (portfolioByTarget.keys - declaredTargets).sorted().forEach { target ->
                findings += finding("ADAPTER_RENDERING_TARGET_MISSING", target, "Every adapter portfolio target requires rendering evidence.")
            }
            (declaredTargets - portfolioByTarget.keys).sorted().forEach { target ->
                findings += finding("ADAPTER_RENDERING_TARGET_UNKNOWN", target, "Rendering evidence target is absent from the adapter portfolio.")
            }
        }

        val reviewNames = document.targets.groupBy { it.review.fileName }
        reviewNames.filterValues { it.size > 1 }.forEach { (fileName, records) ->
            records.forEach { record ->
                findings += finding(
                    "ADAPTER_RENDERING_REVIEW_FILENAME_DUPLICATE",
                    record.target,
                    "Review artifact file name '$fileName' is shared by multiple targets."
                )
            }
        }

        document.targets.sortedBy { it.target }.forEach { record ->
            validateRecord(record, portfolioByTarget[record.target], findings)
        }

        return AdapterArtifactRenderingEvidenceReport(
            status = if (findings.isEmpty()) "PASS" else "FAIL",
            targetCount = document.targets.size,
            findings = findings.sortedWith(compareBy({ it.target }, { it.code }, { it.message }))
        )
    }

    private fun validateRecord(
        record: AdapterArtifactRenderingRecord,
        portfolioRecord: org.flowlang.adapters.portfolio.AdapterPortfolioRecord?,
        findings: MutableList<AdapterArtifactRenderingFinding>
    ) {
        if (record.target.isBlank()) {
            findings += finding("ADAPTER_RENDERING_TARGET_BLANK", record.target, "Target identity must not be blank.")
        }
        validateFormat(record.target, "review", record.review, findings)
        record.executable?.let { validateFormat(record.target, "executable", it, findings) }
        if (record.review.fileName == record.executable?.fileName) {
            findings += finding(
                "ADAPTER_RENDERING_REVIEW_IMPERSONATES_EXECUTABLE",
                record.target,
                "Review evidence must not reuse the executable target artifact file name."
            )
        }
        if (record.evidenceReferences.isEmpty() || record.evidenceReferences.any(String::isBlank)) {
            findings += finding(
                "ADAPTER_RENDERING_EVIDENCE_INVALID",
                record.target,
                "At least one non-blank repository evidence reference is required."
            )
        }
        if (record.evidenceReferences.size != record.evidenceReferences.toSet().size) {
            findings += finding(
                "ADAPTER_RENDERING_EVIDENCE_DUPLICATE",
                record.target,
                "Repository evidence references must be unique."
            )
        }
        if (record.limitations.isEmpty() || record.limitations.any(String::isBlank)) {
            findings += finding(
                "ADAPTER_RENDERING_LIMITATIONS_INVALID",
                record.target,
                "At least one non-blank limitation is required."
            )
        }

        val profileOnly = portfolioRecord?.role == AdapterPortfolioRole.SEMANTIC_REFERENCE ||
            portfolioRecord?.supportClass == AdapterSupportClass.PROFILE_ONLY
        if (profileOnly && record.status != AdapterArtifactRenderingClaimStatus.UNKNOWN) {
            findings += finding(
                "ADAPTER_RENDERING_PROFILE_ONLY_PROMOTED",
                record.target,
                "Semantic references and profile-only targets must remain UNKNOWN."
            )
        }

        val provider = projections.providerFor(record.target)
        when (record.status) {
            AdapterArtifactRenderingClaimStatus.SUPPORTED -> {
                if (provider == null) {
                    findings += finding(
                        "ADAPTER_RENDERING_SUPPORTED_WITHOUT_PROVIDER",
                        record.target,
                        "Supported rendering requires a composed projection provider."
                    )
                }
                val executable = record.executable
                if (executable == null) {
                    findings += finding(
                        "ADAPTER_RENDERING_EXECUTABLE_FORMAT_MISSING",
                        record.target,
                        "Supported rendering requires an executable artifact format."
                    )
                } else if (provider != null && executable.fileName != provider.artifactFileName) {
                    findings += finding(
                        "ADAPTER_RENDERING_PROVIDER_FILENAME_MISMATCH",
                        record.target,
                        "Evidence declares '${executable.fileName}', but the provider owns '${provider.artifactFileName}'."
                    )
                }
            }
            AdapterArtifactRenderingClaimStatus.REVIEW_ONLY -> {
                if (provider == null) {
                    findings += finding(
                        "ADAPTER_RENDERING_REVIEW_ONLY_WITHOUT_PROVIDER",
                        record.target,
                        "REVIEW_ONLY is reserved for a composed provider whose executable rendering is not certified."
                    )
                }
                val executable = record.executable
                if (provider != null && executable?.fileName != provider.artifactFileName) {
                    findings += finding(
                        "ADAPTER_RENDERING_PROVIDER_FILENAME_MISMATCH",
                        record.target,
                        "Review-only evidence must still identify the provider-owned future artifact '${provider.artifactFileName}'."
                    )
                }
            }
            AdapterArtifactRenderingClaimStatus.UNKNOWN -> {
                if (record.executable != null) {
                    findings += finding(
                        "ADAPTER_RENDERING_UNKNOWN_EXECUTABLE_DECLARED",
                        record.target,
                        "UNKNOWN rendering evidence must not declare an executable artifact format."
                    )
                }
            }
        }

        validateEvidenceReferences(record, findings)
    }

    private fun validateFormat(
        target: String,
        role: String,
        format: AdapterArtifactFormat,
        findings: MutableList<AdapterArtifactRenderingFinding>
    ) {
        if (!isFileName(format.fileName)) {
            findings += finding(
                "ADAPTER_RENDERING_FILENAME_INVALID",
                target,
                "$role artifact '${format.fileName}' must be a file name, not a path."
            )
        }
        if (format.mediaType.isBlank() || '/' !in format.mediaType) {
            findings += finding(
                "ADAPTER_RENDERING_MEDIA_TYPE_INVALID",
                target,
                "$role artifact media type '${format.mediaType}' is invalid."
            )
        }
    }

    private fun validateEvidenceReferences(
        record: AdapterArtifactRenderingRecord,
        findings: MutableList<AdapterArtifactRenderingFinding>
    ) {
        val paths = record.evidenceReferences.map { it.substringBefore('#') }
        record.evidenceReferences.forEach { reference ->
            val path = reference.substringBefore('#')
            val candidate = runCatching { File(rootDir, path).canonicalFile.toPath() }.getOrNull()
            when {
                path == AdapterArtifactRenderingEvidenceLoader.PATH -> findings += finding(
                    "ADAPTER_RENDERING_EVIDENCE_SELF_REFERENTIAL",
                    record.target,
                    "Rendering evidence cannot cite its own authority document."
                )
                File(path).isAbsolute || candidate == null || !candidate.startsWith(repositoryRoot) -> findings += finding(
                    "ADAPTER_RENDERING_EVIDENCE_OUTSIDE_REPOSITORY",
                    record.target,
                    "Evidence must be a repository-relative path: $reference"
                )
                !candidate.toFile().isFile -> findings += finding(
                    "ADAPTER_RENDERING_EVIDENCE_UNRESOLVED",
                    record.target,
                    "Evidence file does not exist: $reference"
                )
            }
        }
        if (record.status == AdapterArtifactRenderingClaimStatus.SUPPORTED) {
            if (paths.none { it.startsWith("src/main/") }) {
                findings += finding(
                    "ADAPTER_RENDERING_IMPLEMENTATION_EVIDENCE_MISSING",
                    record.target,
                    "Supported rendering requires production implementation evidence."
                )
            }
            if (paths.none { it.startsWith("src/test/") || it.startsWith("tests/") }) {
                findings += finding(
                    "ADAPTER_RENDERING_BEHAVIOR_EVIDENCE_MISSING",
                    record.target,
                    "Supported rendering requires independent behavioral test evidence."
                )
            }
        }
    }

    private fun isFileName(value: String): Boolean =
        value.isNotBlank() && '/' !in value && '\\' !in value && value !in setOf(".", "..")

    private fun finding(code: String, target: String, message: String) =
        AdapterArtifactRenderingFinding(code, target, message)
}
