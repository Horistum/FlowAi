package org.flowlang.distribution.reference

import java.io.File
import org.flowlang.adapters.continuity.AdapterContinuityEvidenceDocument
import org.flowlang.adapters.continuity.AdapterContinuityEvidenceIntegrityAuthority
import org.flowlang.adapters.continuity.AdapterContinuityProjectionExecutionGate
import org.flowlang.adapters.continuity.AdapterContinuitySatisfactionAuthority
import org.flowlang.adapters.continuity.AdapterContinuityScopedCapabilityResolver
import org.flowlang.adapters.continuity.AdapterContinuityScopedSupport
import org.flowlang.adapters.continuity.AdapterContinuityScopedSupportIntegrityAuthority
import org.flowlang.adapters.continuity.BuiltInAdapterContinuityScopedSupport
import org.flowlang.adapters.control.AdapterControlMaterializationAuthority
import org.flowlang.adapters.control.AdapterControlMaterializationDocument
import org.flowlang.adapters.portfolio.AdapterExecutableReferencePromotionAuthority
import org.flowlang.adapters.portfolio.AdapterPortfolioAuthority
import org.flowlang.adapters.portfolio.AdapterPortfolioDocument
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.rendering.AdapterArtifactRenderingAuthority
import org.flowlang.adapters.rendering.AdapterArtifactRenderingDocument
import org.flowlang.adapters.rendering.AdapterArtifactRenderingEvidenceIntegrityAuthority
import org.flowlang.adapters.topology.AdapterTopologyEvidenceAuthority
import org.flowlang.adapters.trigger.AdapterTriggerAuthorizedRenderingAuthority
import org.flowlang.adapters.trigger.AdapterTriggerEvidenceDocument
import org.flowlang.adapters.trigger.AdapterTriggerEvidenceIntegrityAuthority
import org.flowlang.adapters.trigger.AdapterTriggerMaterializationAuthority
import org.flowlang.capabilities.TargetCapability
import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.TargetProjectionCapabilityResolver
import org.flowlang.generators.manifest.providerFor
import org.flowlang.targets.builtin.JenkinsRetryProjectionScope
import org.flowlang.targets.TargetRegistryYamlLoader

/**
 * Reference distribution composition, including its explicitly selected evidence.
 * Generic evidence authorities are compiled without this module or any adapter
 * implementation. Alternative distributions supply their own catalogs and inputs.
 */
object ReferenceAdapterEvidence {
    fun promotion(
        rootDir: File = File("."),
        targets: Map<String, TargetCapability>,
        projections: AdapterCatalog<TargetProjectionProvider>,
        scopedSupports: List<AdapterContinuityScopedSupport> = BuiltInAdapterContinuityScopedSupport.declarations
    ): AdapterExecutableReferencePromotionAuthority = AdapterExecutableReferencePromotionAuthority(
        rootDir = rootDir,
        targets = targets,
        projections = projections,
        scopedSupports = scopedSupports
    )

    fun portfolio(
        rootDir: File = File("."),
        targets: Map<String, TargetCapability> =
            TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets")),
        projections: AdapterCatalog<TargetProjectionProvider> = ReferenceTargetProjections.registry
    ): AdapterPortfolioAuthority = AdapterPortfolioAuthority(
        rootDir = rootDir,
        targets = targets,
        projections = projections,
        // Existing versioned portfolio evidence cites the retained facade. The
        // facade delegates to ReferenceTargetProjections; migrate anchors in AR-07.
        compositionEvidenceReference = "src/main/kotlin/org/flowlang/targets/builtin/BuiltInTargetProjections.kt"
    )

    fun authorizedRendering(
        rootDir: File = File("."),
        projections: AdapterCatalog<TargetProjectionProvider> = ReferenceTargetProjections.registry,
        delegate: AdapterArtifactRenderingAuthority =
            AdapterArtifactRenderingAuthority(rootDir, projections)
    ): AdapterTriggerAuthorizedRenderingAuthority = AdapterTriggerAuthorizedRenderingAuthority(
        rootDir = rootDir,
        projections = projections,
        delegate = delegate
    )

    fun trigger(
        rootDir: File = File("."),
        targets: Map<String, TargetCapability>,
        projections: AdapterCatalog<TargetProjectionProvider> = ReferenceTargetProjections.registry,
        documentOverride: AdapterTriggerEvidenceDocument? = null
    ): AdapterTriggerMaterializationAuthority = AdapterTriggerMaterializationAuthority(
        rootDir = rootDir,
        targets = targets,
        projections = projections,
        documentOverride = documentOverride
    )

    fun triggerIntegrity(
        rootDir: File = File("."),
        targets: Map<String, TargetCapability>,
        projections: AdapterCatalog<TargetProjectionProvider> = ReferenceTargetProjections.registry,
        portfolio: AdapterPortfolioDocument = AdapterPortfolioLoader.load(rootDir)
    ): AdapterTriggerEvidenceIntegrityAuthority = AdapterTriggerEvidenceIntegrityAuthority(
        rootDir = rootDir,
        targets = targets,
        projections = projections,
        portfolio = portfolio
    )

    fun executionGate(
        rootDir: File,
        targets: Map<String, TargetCapability>,
        projections: AdapterCatalog<TargetProjectionProvider>
    ): AdapterContinuityProjectionExecutionGate = AdapterContinuityProjectionExecutionGate(
        rootDir = rootDir,
        targets = targets,
        projections = projections,
        scopedSupports = BuiltInAdapterContinuityScopedSupport.declarations,
        capabilityResolver = TargetProjectionCapabilityResolver { authorization, target, declared ->
            val continuity = AdapterContinuityScopedCapabilityResolver().resolve(authorization, target, declared)
            JenkinsRetryProjectionScope(projections.providerFor(target)?.nativeProjectionCatalog)
                .resolve(authorization, target, continuity)
        }
    )

    fun continuity(
        rootDir: File = File("."),
        targets: Map<String, TargetCapability>,
        projections: AdapterCatalog<TargetProjectionProvider> = ReferenceTargetProjections.registry,
        documentOverride: AdapterContinuityEvidenceDocument? = null,
        scopedSupports: List<AdapterContinuityScopedSupport> =
            BuiltInAdapterContinuityScopedSupport.declarations
    ): AdapterContinuitySatisfactionAuthority = AdapterContinuitySatisfactionAuthority(
        rootDir = rootDir,
        targets = targets,
        projections = projections,
        documentOverride = documentOverride,
        scopedSupports = scopedSupports
    )

    fun continuityIntegrity(
        rootDir: File = File("."),
        targets: Map<String, TargetCapability>,
        projections: AdapterCatalog<TargetProjectionProvider> = ReferenceTargetProjections.registry,
        portfolio: AdapterPortfolioDocument = AdapterPortfolioLoader.load(rootDir)
    ): AdapterContinuityEvidenceIntegrityAuthority = AdapterContinuityEvidenceIntegrityAuthority(
        rootDir = rootDir,
        targets = targets,
        projections = projections,
        portfolio = portfolio
    )

    fun scopedSupportIntegrity(
        rootDir: File = File("."),
        declarations: List<AdapterContinuityScopedSupport> = BuiltInAdapterContinuityScopedSupport.declarations
    ): AdapterContinuityScopedSupportIntegrityAuthority = AdapterContinuityScopedSupportIntegrityAuthority(
        rootDir = rootDir,
        declarations = declarations
    )

    fun control(
        rootDir: File = File("."),
        targets: Map<String, TargetCapability>,
        projections: AdapterCatalog<TargetProjectionProvider> = ReferenceTargetProjections.registry,
        documentOverride: AdapterControlMaterializationDocument? = null
    ): AdapterControlMaterializationAuthority = AdapterControlMaterializationAuthority(
        rootDir = rootDir,
        targets = targets,
        projections = projections,
        documentOverride = documentOverride
    )

    fun topology(
        rootDir: File = File("."),
        targets: Map<String, TargetCapability> =
            TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets")),
        projections: AdapterCatalog<TargetProjectionProvider> = ReferenceTargetProjections.registry,
        portfolio: AdapterPortfolioDocument = AdapterPortfolioLoader.load(rootDir)
    ): AdapterTopologyEvidenceAuthority = AdapterTopologyEvidenceAuthority(
        rootDir = rootDir,
        targets = targets,
        projections = projections,
        portfolio = portfolio
    )

    fun renderingIntegrity(
        rootDir: File = File("."),
        projections: AdapterCatalog<TargetProjectionProvider> = ReferenceTargetProjections.registry,
        portfolio: AdapterPortfolioDocument = AdapterPortfolioLoader.load(rootDir)
    ): AdapterArtifactRenderingEvidenceIntegrityAuthority = AdapterArtifactRenderingEvidenceIntegrityAuthority(
        rootDir = rootDir,
        projections = projections,
        portfolio = portfolio
    )

    fun rendering(
        rootDir: File = File("."),
        projections: AdapterCatalog<TargetProjectionProvider> = ReferenceTargetProjections.registry,
        documentOverride: AdapterArtifactRenderingDocument? = null
    ): AdapterArtifactRenderingAuthority = AdapterArtifactRenderingAuthority(
        rootDir = rootDir,
        projections = projections,
        documentOverride = documentOverride
    )
}
