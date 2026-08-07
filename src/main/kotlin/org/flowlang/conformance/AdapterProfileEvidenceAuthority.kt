package org.flowlang.conformance

import java.io.File
import java.security.MessageDigest
import org.flowlang.adapters.binding.AdapterCapabilityBindingAuthority
import org.flowlang.adapters.binding.AdapterCapabilityBindingDocument
import org.flowlang.adapters.binding.AdapterCapabilityBindingLoader
import org.flowlang.adapters.continuity.AdapterContinuityEvidenceDocument
import org.flowlang.adapters.continuity.AdapterContinuityEvidenceIntegrityAuthority
import org.flowlang.adapters.continuity.AdapterContinuityEvidenceLoader
import org.flowlang.adapters.control.AdapterControlEvidenceIntegrityAuthority
import org.flowlang.adapters.control.AdapterControlMaterializationDocument
import org.flowlang.adapters.control.AdapterControlMaterializationLoader
import org.flowlang.adapters.portfolio.AdapterPortfolioAuthority
import org.flowlang.adapters.portfolio.AdapterPortfolioDocument
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.rendering.AdapterArtifactRenderingClaimStatus
import org.flowlang.adapters.rendering.AdapterArtifactRenderingDocument
import org.flowlang.adapters.rendering.AdapterArtifactRenderingEvidenceIntegrityAuthority
import org.flowlang.adapters.rendering.AdapterArtifactRenderingEvidenceLoader
import org.flowlang.adapters.topology.AdapterTopologyClaimStatus
import org.flowlang.adapters.topology.AdapterTopologyEvidenceAuthority
import org.flowlang.adapters.topology.AdapterTopologyEvidenceDocument
import org.flowlang.adapters.topology.AdapterTopologyEvidenceLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.modules.ModuleRegistry
import org.flowlang.targets.builtin.BuiltInTargetProjections

/**
 * C0.4 independently evaluates the current built-in implementation against the
 * frozen A0.1-A0.6 adapter-owned profile evidence. It never promotes support:
 * unsupported and unknown claims are first-class report entries and a truthful
 * negative claim does not make the conformance report fail.
 */
class AdapterProfileEvidenceAuthority(
    private val rootDir: File = File("."),
    private val registry: ModuleRegistry = ModuleRegistry.fromDirectory(File(rootDir, "modules")),
    private val targets: Map<String, TargetCapability> =
        TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets")),
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    fun analyze(): AdapterProfileEvidenceAssessment {
        val sourceErrors = mutableListOf<String>()
        val implementationErrors = mutableListOf<String>()
        val coverageErrors = mutableListOf<String>()
        val visibilityErrors = mutableListOf<String>()
        val scopeErrors = mutableListOf<String>()
        val boundaryErrors = mutableListOf<String>()

        val sourceManifestResult = runCatching { AdapterProfileSourceManifestLoader.load(rootDir) }
        val sourceManifest = sourceManifestResult.getOrElse {
            sourceErrors += it.message ?: it.javaClass.simpleName
            return failedAssessment(
                sourceErrors,
                implementationErrors,
                coverageErrors,
                visibilityErrors,
                scopeErrors,
                boundaryErrors
            )
        }
        sourceErrors += validateFrozenSources(sourceManifest)
        scopeErrors += validateSourceScope(sourceManifest)

        val documentsResult = runCatching { loadDocuments() }
        val documents = documentsResult.getOrElse {
            implementationErrors += it.message ?: it.javaClass.simpleName
            return failedAssessment(
                sourceErrors,
                implementationErrors,
                coverageErrors,
                visibilityErrors,
                scopeErrors,
                boundaryErrors
            )
        }

        sourceErrors += validatePinnedVersions(sourceManifest, documents)
        implementationErrors += implementationErrors(documents)

        val claims = buildClaims(documents)
        val targetClaims = claims.filter { it.target != null }
        val bindingClaims = claims.filter { it.target == null }
        coverageErrors += validateCoverage(documents, targetClaims, bindingClaims)

        val targetEvidence = documents.portfolio.records.sortedBy { it.target }.map { record ->
            AdapterProfileTargetEvidence(
                target = record.target,
                role = record.role,
                supportClass = record.supportClass,
                claims = targetClaims.filter { it.target == record.target }
                    .sortedWith(compareBy({ it.dimension.ordinal }, { it.capability }))
            )
        }
        val unsupported = claims.filter { it.status == AdapterProfileClaimStatus.UNSUPPORTED }.sortedBy { it.id }
        val unknown = claims.filter { it.status == AdapterProfileClaimStatus.UNKNOWN }.sortedBy { it.id }
        val provisional = AdapterProfileEvidenceReport(
            status = "PASS",
            adapterBoundary = sourceManifest.adapterBoundary,
            frozenSources = sourceManifest.sources,
            targets = targetEvidence,
            bindingClaims = bindingClaims.sortedBy { it.id },
            unsupportedClaims = unsupported,
            unknownClaims = unknown,
            findings = emptyList()
        )

        visibilityErrors += AdapterProfileReportIntegrityAuthority.validate(provisional)
        scopeErrors += boundedPromotionScopeErrors(provisional)
        boundaryErrors += frozenBoundaryErrors()

        val allErrors = listOf(
            sourceErrors,
            implementationErrors,
            coverageErrors,
            visibilityErrors,
            scopeErrors,
            boundaryErrors
        ).flatten()
        val report = provisional.copy(
            status = if (allErrors.isEmpty()) "PASS" else "FAIL",
            findings = allErrors.sorted()
        )
        return AdapterProfileEvidenceAssessment(
            status = report.status,
            report = report,
            sourceErrors = sourceErrors.sorted(),
            implementationErrors = implementationErrors.sorted(),
            coverageErrors = coverageErrors.sorted(),
            visibilityErrors = visibilityErrors.sorted(),
            scopeErrors = scopeErrors.sorted(),
            boundaryErrors = boundaryErrors.sorted()
        )
    }

    private fun failedAssessment(
        sourceErrors: List<String>,
        implementationErrors: List<String>,
        coverageErrors: List<String>,
        visibilityErrors: List<String>,
        scopeErrors: List<String>,
        boundaryErrors: List<String>
    ): AdapterProfileEvidenceAssessment = AdapterProfileEvidenceAssessment(
        status = "FAIL",
        report = null,
        sourceErrors = sourceErrors.sorted(),
        implementationErrors = implementationErrors.sorted(),
        coverageErrors = coverageErrors.sorted(),
        visibilityErrors = visibilityErrors.sorted(),
        scopeErrors = scopeErrors.sorted(),
        boundaryErrors = boundaryErrors.sorted()
    )

    private fun loadDocuments(): ProfileDocuments = ProfileDocuments(
        portfolio = AdapterPortfolioLoader.load(rootDir),
        topology = AdapterTopologyEvidenceLoader.load(rootDir),
        bindings = AdapterCapabilityBindingLoader.load(rootDir),
        controls = AdapterControlMaterializationLoader.load(rootDir),
        continuity = AdapterContinuityEvidenceLoader.load(rootDir),
        rendering = AdapterArtifactRenderingEvidenceLoader.load(rootDir)
    )

    private fun validateFrozenSources(manifest: AdapterProfileSourceManifest): List<String> = buildList {
        val expected = REQUIRED_SOURCES.associateBy { it.first }
        val actual = manifest.sources.associateBy { it.id }
        val expectedOrder = REQUIRED_SOURCES.map { it.first }
        val actualOrder = manifest.sources.map { it.id }
        if (actualOrder != expectedOrder) {
            add("C0.4 frozen source order changed: declared=$actualOrder expected=$expectedOrder.")
        }
        val missing = expected.keys - actual.keys
        val extra = actual.keys - expected.keys
        if (missing.isNotEmpty()) add("C0.4 frozen source manifest is missing sources: ${missing.sorted().joinToString()}.")
        if (extra.isNotEmpty()) add("C0.4 frozen source manifest contains out-of-scope sources: ${extra.sorted().joinToString()}.")

        manifest.sources.forEach { source ->
            val requiredPath = expected[source.id]?.second
            if (requiredPath != null && source.path != requiredPath) {
                add("C0.4 source '${source.id}' path '${source.path}' must remain '$requiredPath'.")
            }
            val file = File(rootDir, source.path)
            if (!file.isFile) {
                add("C0.4 frozen source '${source.id}' is missing: ${file.path}.")
            } else {
                val observed = sha256(file.readBytes())
                if (observed != source.sha256) {
                    add("C0.4 frozen source '${source.id}' digest changed: declared=${source.sha256} observed=$observed.")
                }
            }
        }
    }

    private fun validateSourceScope(manifest: AdapterProfileSourceManifest): List<String> = buildList {
        if (manifest.adapterBoundary != AdapterProfileSourceManifest.ADAPTER_BOUNDARY) {
            add("C0.4 may consume only the frozen A0.6 adapter boundary.")
        }
        manifest.sources.forEach { source ->
            if (source.path.contains("/trigger/") || source.path.contains("trigger", ignoreCase = true)) {
                add("C0.4 source '${source.id}' leaks A0.7 trigger evidence into the A0.6 profile boundary.")
            }
            if (source.path.contains("a1", ignoreCase = true) || source.path.contains("promotion", ignoreCase = true)) {
                add("C0.4 source '${source.id}' leaks bounded post-A0.6 promotion evidence into the generic profile.")
            }
        }
    }

    private fun validatePinnedVersions(
        manifest: AdapterProfileSourceManifest,
        documents: ProfileDocuments
    ): List<String> = buildList {
        val observed = mapOf(
            "portfolio" to documents.portfolio.version,
            "topology" to documents.topology.version,
            "bindings" to documents.bindings.version,
            "controls" to documents.controls.version,
            "continuity" to documents.continuity.version,
            "rendering" to documents.rendering.version
        )
        manifest.sources.forEach { source ->
            val current = observed[source.id]
            if (current != null && current != source.documentVersion) {
                add("C0.4 source '${source.id}' version changed: declared=${source.documentVersion} observed=$current.")
            }
        }
    }

    private fun implementationErrors(documents: ProfileDocuments): List<String> = buildList {
        val portfolio = AdapterPortfolioAuthority(rootDir, targets, projections).evaluate(documents.portfolio)
        portfolio.findings.forEach { add("portfolio:${it.code}:${it.target}:${it.message}") }

        val topology = AdapterTopologyEvidenceAuthority(
            rootDir = rootDir,
            targets = targets,
            projections = projections,
            portfolio = documents.portfolio
        ).evaluate(documents.topology)
        topology.findings.forEach { add("topology:${it.code}:${it.target}:${it.claim}:${it.message}") }

        val bindings = AdapterCapabilityBindingAuthority(rootDir, registry).analyze(documents.bindings)
        bindings.findings.forEach { add("binding:${it.code}:${it.binding}:${it.message}") }

        val controls = AdapterControlEvidenceIntegrityAuthority(rootDir, targets, projections).analyze(documents.controls)
        controls.findings.forEach { add("control:${it.code}:${it.target}:${it.family}:${it.message}") }

        val continuity = AdapterContinuityEvidenceIntegrityAuthority(
            rootDir = rootDir,
            targets = targets,
            projections = projections,
            portfolio = documents.portfolio
        ).analyze(documents.continuity)
        continuity.findings.forEach { add("continuity:${it.code}:${it.target}:${it.family}:${it.message}") }

        val rendering = AdapterArtifactRenderingEvidenceIntegrityAuthority(
            rootDir = rootDir,
            projections = projections,
            portfolio = documents.portfolio
        ).analyze(documents.rendering)
        rendering.findings.forEach { add("rendering:${it.code}:${it.target}:${it.message}") }
    }

    private fun buildClaims(documents: ProfileDocuments): List<AdapterProfileClaim> = buildList {
        documents.topology.records.forEach { record ->
            record.claims.forEach { claim ->
                add(
                    targetClaim(
                        AdapterProfileDimension.TOPOLOGY,
                        record.target,
                        claim.key,
                        claim.status.toProfileStatus(),
                        AdapterTopologyEvidenceLoader.PATH,
                        claim.limitations.firstOrNull() ?: claim.mechanism
                    )
                )
            }
        }
        documents.controls.targets.forEach { record ->
            record.claims.forEach { claim ->
                claim.semantics.supported.forEach { semantic ->
                    add(targetClaim(AdapterProfileDimension.CONTROL, record.target, semantic, AdapterProfileClaimStatus.SUPPORTED, AdapterControlMaterializationLoader.PATH, claim.mechanism))
                }
                claim.semantics.unsupported.forEach { (semantic, reason) ->
                    add(targetClaim(AdapterProfileDimension.CONTROL, record.target, semantic, AdapterProfileClaimStatus.UNSUPPORTED, AdapterControlMaterializationLoader.PATH, reason))
                }
                claim.semantics.unknown.forEach { (semantic, reason) ->
                    add(targetClaim(AdapterProfileDimension.CONTROL, record.target, semantic, AdapterProfileClaimStatus.UNKNOWN, AdapterControlMaterializationLoader.PATH, reason))
                }
            }
        }
        documents.continuity.targets.forEach { record ->
            record.claims.forEach { claim ->
                claim.semantics.supported.forEach { semantic ->
                    add(targetClaim(AdapterProfileDimension.CONTINUITY, record.target, semantic, AdapterProfileClaimStatus.SUPPORTED, AdapterContinuityEvidenceLoader.PATH, claim.mechanism))
                }
                claim.semantics.unsupported.forEach { (semantic, reason) ->
                    add(targetClaim(AdapterProfileDimension.CONTINUITY, record.target, semantic, AdapterProfileClaimStatus.UNSUPPORTED, AdapterContinuityEvidenceLoader.PATH, reason))
                }
                claim.semantics.unknown.forEach { (semantic, reason) ->
                    add(targetClaim(AdapterProfileDimension.CONTINUITY, record.target, semantic, AdapterProfileClaimStatus.UNKNOWN, AdapterContinuityEvidenceLoader.PATH, reason))
                }
            }
        }
        documents.rendering.targets.forEach { record ->
            val status = when (record.status) {
                AdapterArtifactRenderingClaimStatus.SUPPORTED -> AdapterProfileClaimStatus.SUPPORTED
                AdapterArtifactRenderingClaimStatus.REVIEW_ONLY -> AdapterProfileClaimStatus.UNSUPPORTED
                AdapterArtifactRenderingClaimStatus.UNKNOWN -> AdapterProfileClaimStatus.UNKNOWN
            }
            val detail = record.limitations.firstOrNull()
                ?: "Artifact rendering status is ${record.status}."
            add(targetClaim(AdapterProfileDimension.RENDERING, record.target, EXECUTABLE_RENDERING_CAPABILITY, status, AdapterArtifactRenderingEvidenceLoader.PATH, detail))
        }
        documents.bindings.records.forEach { record ->
            record.mappedSemanticParameters.forEach { parameter ->
                add(bindingClaim(record.id, parameter, AdapterProfileClaimStatus.SUPPORTED, "Mapped by the frozen adapter binding profile."))
            }
            record.unsupportedSemanticParameters.forEach { (parameter, reason) ->
                add(bindingClaim(record.id, parameter, AdapterProfileClaimStatus.UNSUPPORTED, reason))
            }
        }
    }.sortedBy { it.id }

    private fun targetClaim(
        dimension: AdapterProfileDimension,
        target: String,
        capability: String,
        status: AdapterProfileClaimStatus,
        sourceReference: String,
        detail: String
    ): AdapterProfileClaim = AdapterProfileClaim(
        id = AdapterProfileClaimIdentity.id(dimension, target, capability),
        dimension = dimension,
        target = target,
        capability = capability,
        status = status,
        sourceReference = sourceReference,
        detail = detail
    )

    private fun bindingClaim(
        bindingId: String,
        parameter: String,
        status: AdapterProfileClaimStatus,
        detail: String
    ): AdapterProfileClaim {
        val capability = "$bindingId.semantic-parameter.$parameter"
        return AdapterProfileClaim(
            id = AdapterProfileClaimIdentity.id(AdapterProfileDimension.BINDING, null, capability),
            dimension = AdapterProfileDimension.BINDING,
            target = null,
            capability = capability,
            status = status,
            sourceReference = AdapterCapabilityBindingLoader.PATH,
            detail = detail
        )
    }

    private fun validateCoverage(
        documents: ProfileDocuments,
        targetClaims: List<AdapterProfileClaim>,
        bindingClaims: List<AdapterProfileClaim>
    ): List<String> = buildList {
        val portfolioTargets = documents.portfolio.records.map { it.target }.toSet()
        val sourceTargets = mapOf(
            "topology" to documents.topology.records.map { it.target }.toSet(),
            "controls" to documents.controls.targets.map { it.target }.toSet(),
            "continuity" to documents.continuity.targets.map { it.target }.toSet(),
            "rendering" to documents.rendering.targets.map { it.target }.toSet()
        )
        sourceTargets.forEach { (source, declared) ->
            if (declared != portfolioTargets) {
                add("C0.4 $source target coverage differs from portfolio: declared=${declared.sorted()} expected=${portfolioTargets.sorted()}.")
            }
        }
        val reportedTargets = targetClaims.mapNotNull { it.target }.toSet()
        if (reportedTargets != portfolioTargets) {
            add("C0.4 report target coverage differs from portfolio: reported=${reportedTargets.sorted()} expected=${portfolioTargets.sorted()}.")
        }
        val dimensionsByTarget = targetClaims.groupBy { it.target }.mapValues { (_, claims) -> claims.map { it.dimension }.toSet() }
        val requiredDimensions = setOf(
            AdapterProfileDimension.TOPOLOGY,
            AdapterProfileDimension.CONTROL,
            AdapterProfileDimension.CONTINUITY,
            AdapterProfileDimension.RENDERING
        )
        portfolioTargets.sorted().forEach { target ->
            val observed = dimensionsByTarget[target].orEmpty()
            if (observed != requiredDimensions) {
                add("C0.4 target '$target' dimensions differ: observed=${observed.sortedBy { it.ordinal }} expected=${requiredDimensions.sortedBy { it.ordinal }}.")
            }
        }
        val expectedBindingClaims = documents.bindings.records.sumOf {
            it.mappedSemanticParameters.size + it.unsupportedSemanticParameters.size
        }
        if (bindingClaims.size != expectedBindingClaims) {
            add("C0.4 binding claim coverage differs: reported=${bindingClaims.size} expected=$expectedBindingClaims.")
        }
        val duplicateClaims = (targetClaims + bindingClaims).groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        if (duplicateClaims.isNotEmpty()) {
            add("C0.4 report contains duplicate claim identities: ${duplicateClaims.sorted().joinToString()}.")
        }
    }

    private fun boundedPromotionScopeErrors(report: AdapterProfileEvidenceReport): List<String> = buildList {
        val githubContinuity = report.targets.singleOrNull { it.target == "github-actions" }
            ?.claims?.singleOrNull {
                it.dimension == AdapterProfileDimension.CONTINUITY &&
                    it.capability == "artifact.shared-workspace"
            }
        if (githubContinuity == null) {
            add("C0.4 generic GitHub Actions shared-workspace continuity claim is missing.")
        } else if (githubContinuity.status != AdapterProfileClaimStatus.UNSUPPORTED) {
            add("C0.4 generic GitHub Actions shared-workspace continuity was promoted to ${githubContinuity.status}; A1.0 is bounded evidence only.")
        }
    }

    private fun frozenBoundaryErrors(): List<String> = buildList {
        val frozenInventories = mapOf(
            ConformanceSuiteInventory.PATH to ConformanceSuiteInventory.load(rootDir).preClosureChecks,
            AdapterConformanceInventory.PATH to AdapterConformanceInventory.load(rootDir).checks,
            AdapterA1ConformanceInventory.PATH to AdapterA1ConformanceInventory.load(rootDir).checks,
            RealWorldCorpusConformanceChecks.INVENTORY_PATH to loadSimpleInventory(RealWorldCorpusConformanceChecks.INVENTORY_PATH),
            AbstractTopologyMatrixConformanceInventory.PATH to AbstractTopologyMatrixConformanceInventory.load(rootDir).checks,
            SemanticEquivalenceConformanceInventory.PATH to SemanticEquivalenceConformanceInventory.load(rootDir).checks
        )
        frozenInventories.forEach { (path, checks) ->
            val leaked = checks.filter { it.startsWith(CHECK_PREFIX) }
            if (leaked.isNotEmpty()) add("Frozen inventory '$path' contains C0.4 checks: $leaked.")
        }
    }

    private fun loadSimpleInventory(path: String): List<String> {
        val yaml = org.flowlang.serialization.FlowYaml.readMap(File(rootDir, path))
        return (yaml["checks"] as? Iterable<*>)?.map { it.toString() }.orEmpty()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

    private fun AdapterTopologyClaimStatus.toProfileStatus(): AdapterProfileClaimStatus = when (this) {
        AdapterTopologyClaimStatus.SUPPORTED -> AdapterProfileClaimStatus.SUPPORTED
        AdapterTopologyClaimStatus.PARTIAL -> AdapterProfileClaimStatus.PARTIAL
        AdapterTopologyClaimStatus.UNSUPPORTED -> AdapterProfileClaimStatus.UNSUPPORTED
        AdapterTopologyClaimStatus.UNKNOWN -> AdapterProfileClaimStatus.UNKNOWN
    }

    private data class ProfileDocuments(
        val portfolio: AdapterPortfolioDocument,
        val topology: AdapterTopologyEvidenceDocument,
        val bindings: AdapterCapabilityBindingDocument,
        val controls: AdapterControlMaterializationDocument,
        val continuity: AdapterContinuityEvidenceDocument,
        val rendering: AdapterArtifactRenderingDocument
    )

    companion object {
        const val CHECK_PREFIX = "conformance.c0.4."
        private const val EXECUTABLE_RENDERING_CAPABILITY = "artifact.executable-rendering"
        private val REQUIRED_SOURCES = listOf(
            "portfolio" to AdapterPortfolioLoader.PATH,
            "topology" to AdapterTopologyEvidenceLoader.PATH,
            "bindings" to AdapterCapabilityBindingLoader.PATH,
            "controls" to AdapterControlMaterializationLoader.PATH,
            "continuity" to AdapterContinuityEvidenceLoader.PATH,
            "rendering" to AdapterArtifactRenderingEvidenceLoader.PATH
        )
    }
}

/**
 * Report-level honesty gate. It deliberately validates the redundant negative
 * indexes because those are the consumer-facing fields most likely to be
 * accidentally filtered while the underlying target claims remain present.
 */
object AdapterProfileReportIntegrityAuthority {
    fun validate(report: AdapterProfileEvidenceReport): List<String> = buildList {
        val claims = report.targets.flatMap { it.claims } + report.bindingClaims
        val duplicateIds = claims.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.sorted()
        if (duplicateIds.isNotEmpty()) add("C0.4 report contains duplicate claims: ${duplicateIds.joinToString()}.")

        val expectedUnsupported = claims.filter { it.status == AdapterProfileClaimStatus.UNSUPPORTED }.sortedBy { it.id }
        val observedUnsupported = report.unsupportedClaims.sortedBy { it.id }
        if (observedUnsupported != expectedUnsupported) {
            val expectedIds = expectedUnsupported.map { it.id }
            val observedIds = observedUnsupported.map { it.id }
            val hidden = expectedIds - observedIds.toSet()
            val invented = observedIds - expectedIds.toSet()
            add("C0.4 unsupported index is not exact: hidden=${hidden.sorted()} invented=${invented.sorted()} payloadMatch=${observedUnsupported == expectedUnsupported}.")
        }

        val expectedUnknown = claims.filter { it.status == AdapterProfileClaimStatus.UNKNOWN }.sortedBy { it.id }
        val observedUnknown = report.unknownClaims.sortedBy { it.id }
        if (observedUnknown != expectedUnknown) {
            val expectedIds = expectedUnknown.map { it.id }
            val observedIds = observedUnknown.map { it.id }
            val hidden = expectedIds - observedIds.toSet()
            val invented = observedIds - expectedIds.toSet()
            add("C0.4 unknown index is not exact: hidden=${hidden.sorted()} invented=${invented.sorted()} payloadMatch=${observedUnknown == expectedUnknown}.")
        }

        report.unsupportedClaims.forEach { claim ->
            if (claim.detail.isBlank()) add("C0.4 unsupported claim '${claim.id}' has no reason.")
        }
        report.unknownClaims.forEach { claim ->
            if (claim.detail.isBlank()) add("C0.4 unknown claim '${claim.id}' has no reason.")
        }
    }
}
