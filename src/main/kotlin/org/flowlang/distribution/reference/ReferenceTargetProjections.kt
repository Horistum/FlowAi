package org.flowlang.distribution.reference

import java.io.File
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.safety.EnvironmentSafetyPolicy
import org.flowlang.generators.manifest.TargetEnvironmentSafetyEvidenceResolver
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.targets.builtin.GitHubActionsManifestGenerator
import org.flowlang.targets.builtin.GitHubActionsManifestRenderer
import org.flowlang.targets.builtin.JenkinsManifestGenerator
import org.flowlang.targets.builtin.JenkinsManifestRenderer
import org.flowlang.targets.builtin.TektonManifestGenerator
import org.flowlang.targets.builtin.TektonManifestRenderer

/** Concrete adapter composition owned by the reference distribution, never by generic authorities. */
object ReferenceTargetProjections {
    val registry: TargetProjectionRegistry by lazy { create(StandardEnvironmentSafetyPolicyNotes.policy()) }

    fun fromContracts(rootDir: File): TargetProjectionRegistry = create(StandardEnvironmentSafetyPolicyNotes.policy(rootDir))

    private fun create(policy: EnvironmentSafetyPolicy): TargetProjectionRegistry = TargetProjectionRegistry.of(
        TargetProjectionProvider(JenkinsManifestGenerator(), JenkinsManifestRenderer()),
        TargetProjectionProvider(GitHubActionsManifestGenerator(), GitHubActionsManifestRenderer(TargetEnvironmentSafetyEvidenceResolver(policy))),
        TargetProjectionProvider(TektonManifestGenerator(), TektonManifestRenderer())
    )

    val nativeCatalogs: Map<String, org.flowlang.generators.manifest.TargetNativeProjectionCatalog>
        get() = registry.targetIds.associateWith { registry.requireProvider(it).nativeProjectionCatalog }

    fun pipeline(
        targets: Map<String, org.flowlang.capabilities.TargetCapability>,
        rootDir: java.io.File = java.io.File("."),
        projections: TargetProjectionRegistry = registry,
        modules: org.flowlang.modules.ModuleCatalog = org.flowlang.modules.ModuleRegistry()
    ): org.flowlang.generators.manifest.TargetManifestGenerationPipeline =
        org.flowlang.generators.manifest.TargetManifestGenerationPipeline(
            targets = targets,
            projections = projections,
            executionGates = listOf(ReferenceAdapterEvidence.executionGate(rootDir, targets, projections)),
            modules = modules
        )
}
