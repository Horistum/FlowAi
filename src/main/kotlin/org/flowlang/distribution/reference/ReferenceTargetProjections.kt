package org.flowlang.distribution.reference

import java.io.File
import org.flowlang.adapters.continuity.AdapterContinuityScopedCapabilityResolver
import org.flowlang.adapters.control.AdapterControlMaterializationAuthority
import org.flowlang.adapters.control.AdapterControlMaterializationDocument
import org.flowlang.adapters.control.AdapterControlMaterializationLoader
import org.flowlang.adapters.control.AdapterControlClaimStatus
import org.flowlang.adapters.control.AdapterControlFamily
import org.flowlang.capabilities.TargetCapability
import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.generators.manifest.TargetProjectionCapabilityResolver
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.generators.manifest.TargetStructuralProjectionSupportScope
import org.flowlang.generators.manifest.providerFor
import org.flowlang.targets.builtin.JenkinsRetryProjectionScope
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

    internal fun capabilityResolver(
        projections: AdapterCatalog<TargetProjectionProvider>
    ): TargetProjectionCapabilityResolver = TargetProjectionCapabilityResolver { authorization, target, declared ->
        val continuity = AdapterContinuityScopedCapabilityResolver().resolve(authorization, target, declared)
        JenkinsRetryProjectionScope(projections.providerFor(target)?.nativeProjectionCatalog)
            .resolve(authorization, target, continuity)
    }

    internal fun control(
        rootDir: File = File("."),
        targets: Map<String, TargetCapability>,
        projections: AdapterCatalog<TargetProjectionProvider> = registry,
        documentOverride: AdapterControlMaterializationDocument? = null
    ): AdapterControlMaterializationAuthority {
        val declared = AdapterControlMaterializationAuthority(rootDir, targets, projections, documentOverride)
        // Historical profile sources remain byte-pinned. Explicit caller documents retain
        // their own authority; invalid source evidence must never be repaired by composition.
        if (documentOverride != null || "jenkins" !in targets) return declared
        val retry = projections.providerFor("jenkins")?.nativeProjectionCatalog?.structuralDefinitions?.singleOrNull {
            it.structure == TargetStructuralProjectionKind.RETRY && it.kind == "JENKINS_STRUCTURE" &&
                it.reference == "retry" && it.supportScope == TargetStructuralProjectionSupportScope.PLAN_SCOPED
        } ?: return declared
        val source = AdapterControlMaterializationLoader.load(rootDir)
        val active = source.copy(targets = source.targets.filter { it.target in targets })
        if (declared.analyze(active).status != "PASS") return declared
        val composed = source.copy(targets = source.targets.map { record ->
            if (record.target != "jenkins") record else record.copy(claims = record.claims.map { claim ->
                if (claim.family != AdapterControlFamily.RETRY) claim else claim.copy(
                    status = AdapterControlClaimStatus.PARTIAL,
                    mechanism = "Native Jenkins retry(count) preserves the attempt limit and early success; the projection scope admits only fixed zero-delay single-checkout bodies.",
                    semantics = claim.semantics.copy(supported = claim.semantics.supported + "retry.attempt-limit",
                        unsupported = claim.semantics.unsupported - "retry.attempt-limit"),
                    evidenceReferences = (claim.evidenceReferences + listOf(retry.implementationEvidenceReference,
                        retry.behavioralEvidenceReference,
                        "src/main/kotlin/org/flowlang/targets/builtin/JenkinsRetryProjectionScope.kt")).distinct(),
                    prerequisites = claim.prerequisites + "The composed provider and authorized plan must satisfy JenkinsRetryProjectionScope.",
                    limitations = listOf("Only fixed zero-delay single-checkout retry bodies are executable.",
                        "No delay, variable backoff, failure-filter, transient-recovery or cancellation certification.")
                )
            })
        })
        return AdapterControlMaterializationAuthority(rootDir, targets, projections, composed)
    }

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
