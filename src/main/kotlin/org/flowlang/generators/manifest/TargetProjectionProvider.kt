package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.TargetCapability
import org.flowlang.planner.ExecutionPlan
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetMaterializationRequest

/**
 * Target-neutral generation boundary. Concrete target implementations live outside
 * Flow Core and are composed explicitly at an application or conformance edge.
 *
 * The generator accepts only authorization issued by
 * [MandatoryMaterializationAuthority]. Raw plans and caller-provided compatibility
 * reports are deliberately not a public generation API.
 */
interface TargetManifestGenerator {
    val target: String
    val nativeProjectionCatalog: TargetNativeProjectionCatalog
        get() = TargetNativeProjectionCatalog.empty(target)

    fun generate(authorization: TargetProjectionAuthorization): TargetManifest
}

/**
 * Reconciles every generated manifest before it can leave a concrete provider.
 */
abstract class ReconciledTargetManifestGenerator : TargetManifestGenerator {
    final override fun generate(authorization: TargetProjectionAuthorization): TargetManifest {
        require(authorization.target == target) {
            "Manifest generator '$target' cannot consume authorization for '${authorization.target}'."
        }
        val plan = authorization.plan
        val compatibility = authorization.compatibility
        nativeProjectionCatalog.requireCompatibleRules(compatibility.projectionRules)
        return buildManifest(plan, compatibility)
            .reconcileCompatibilityReadiness()
            .also(nativeProjectionCatalog::requireManifest)
            .also { manifest ->
                if (
                    authorization.purpose == TargetProjectionAuthorizationPurpose.DIAGNOSTIC_EVIDENCE &&
                    authorization.compatibility.hasErrors
                ) {
                    require(!manifest.compatibility.executable) {
                        "Diagnostic projection for unsupported target '$target' cannot claim executable readiness."
                    }
                }
            }
    }

    protected abstract fun buildManifest(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest
}

/**
 * Target-neutral serialization boundary implemented only by edge projection code.
 */
interface TargetManifestRenderer {
    val target: String
    val artifactFileName: String
    fun render(manifest: TargetManifest): String
}

/**
 * Immutable pairing of one manifest generator and one renderer for the same target.
 * This is explicit application composition, not a dynamic plugin lifecycle.
 */
class TargetProjectionProvider(
    val generator: TargetManifestGenerator,
    val renderer: TargetManifestRenderer
) {
    val target: String = generator.target
    val nativeProjectionCatalog: TargetNativeProjectionCatalog = generator.nativeProjectionCatalog
    val artifactFileName: String = renderer.artifactFileName

    init {
        require(target.isNotBlank()) { "Target projection provider must declare a non-blank target id." }
        require(renderer.target == target) {
            "Target projection provider mismatch: generator '${generator.target}' cannot be paired with renderer '${renderer.target}'."
        }
        require(nativeProjectionCatalog.target == target) {
            "Target projection provider mismatch: generator '$target' cannot use native projection catalog '${nativeProjectionCatalog.target}'."
        }
        require(artifactFileName.isNotBlank()) {
            "Target projection provider '$target' must declare a non-blank artifact file name."
        }
        require('/' !in artifactFileName && '\\' !in artifactFileName && artifactFileName !in setOf(".", "..")) {
            "Target projection provider '$target' must declare a file name, not a path: '$artifactFileName'."
        }
    }

    fun generate(authorization: TargetProjectionAuthorization): TargetManifest {
        require(authorization.target == target) {
            "Projection provider '$target' cannot consume authorization for '${authorization.target}'."
        }
        nativeProjectionCatalog.requireCompatibleRules(authorization.compatibility.projectionRules)
        return generator.generate(authorization).also(nativeProjectionCatalog::requireManifest)
    }

    /**
     * Emits only executable target syntax under the provider-owned artifact name.
     * Review evidence is rendered by the adapter artifact rendering authority under
     * a distinct review file identity.
     */
    fun render(manifest: TargetManifest): String {
        require(manifest.target == target) {
            "Projection provider '$target' cannot render manifest target '${manifest.target}'."
        }
        nativeProjectionCatalog.requireManifest(manifest)
        TargetRenderPolicy.requireExecutable(manifest)
        return renderer.render(manifest)
    }
}

/**
 * Closed, immutable registry supplied by a composition root.
 *
 * Core never discovers implementations and never enumerates platform ids.
 */
class TargetProjectionRegistry private constructor(
    private val providersByTarget: Map<String, TargetProjectionProvider>
) {
    val targetIds: Set<String> = providersByTarget.keys

    fun providerFor(target: String): TargetProjectionProvider? = providersByTarget[target]

    fun requireProvider(target: String): TargetProjectionProvider = providerFor(target)
        ?: error(
            "No target projection provider is registered for '$target'. " +
                "Available providers: ${targetIds.sorted().joinToString().ifBlank { "none" }}."
        )

    companion object {
        fun of(providers: Iterable<TargetProjectionProvider>): TargetProjectionRegistry {
            val indexed = linkedMapOf<String, TargetProjectionProvider>()
            providers.forEach { provider ->
                require(indexed.putIfAbsent(provider.target, provider) == null) {
                    "Duplicate target projection provider '${provider.target}'."
                }
            }
            return TargetProjectionRegistry(indexed.toMap())
        }

        fun of(vararg providers: TargetProjectionProvider): TargetProjectionRegistry = of(providers.asIterable())

        fun empty(): TargetProjectionRegistry = TargetProjectionRegistry(emptyMap())
    }
}

/**
 * Canonical manifest pipeline. Every public generation call passes through one
 * materialization authority before a concrete provider can see the plan.
 */
class TargetManifestGenerationPipeline(
    targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry
) {
    private val authority = MandatoryMaterializationAuthority(targets)

    fun generate(request: TargetMaterializationRequest): TargetManifest {
        val provider = projections.requireProvider(request.target)
        val authorization = authority.authorize(request)
        return provider.generate(authorization)
    }

    /**
     * Generates auditable diagnostic evidence for compatibility analysis and
     * conformance. Unsupported target semantics remain explicit and the
     * resulting manifest is forbidden from claiming executable readiness.
     */
    fun generateDiagnosticEvidence(
        request: TargetDiagnosticMaterializationRequest
    ): TargetManifest {
        val provider = projections.requireProvider(request.target)
        val authorization = authority.authorizeDiagnosticEvidence(request)
        return provider.generate(authorization)
    }
}
