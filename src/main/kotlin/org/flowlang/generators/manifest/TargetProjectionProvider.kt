package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.TargetCapability
import org.flowlang.planner.ExecutionPlan
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetMaterializationRequest

interface TargetManifestGenerator {
    val target: String
    val nativeProjectionCatalog: TargetNativeProjectionCatalog
        get() = TargetNativeProjectionCatalog.empty(target)

    fun generate(authorization: TargetProjectionAuthorization): TargetManifest
}

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

interface TargetManifestRenderer {
    val target: String
    val artifactFileName: String
    fun render(manifest: TargetManifest): String
}

fun interface TargetProjectionCapabilityResolver {
    fun resolve(plan: ExecutionPlan, target: String, declared: TargetCapability): TargetCapability
}

fun interface TargetProjectionExecutionGate {
    fun requireAuthorized(plan: ExecutionPlan, target: String)
}

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

    fun render(manifest: TargetManifest): String {
        require(manifest.target == target) {
            "Projection provider '$target' cannot render manifest target '${manifest.target}'."
        }
        nativeProjectionCatalog.requireManifest(manifest)
        TargetRenderPolicy.requireExecutable(manifest)
        return renderer.render(manifest)
    }
}

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
 * Canonical manifest pipeline with plan-specific capability and execution evidence.
 *
 * The legacy planning validator sees the untouched compatibility plan first, so
 * malformed retained fields keep their stable diagnostics. After that boundary,
 * every execution or diagnostic candidate is rebound to the exact validated
 * CanonicalExecutionGraph authorization before a provider can observe the plan.
 */
class TargetManifestGenerationPipeline(
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry,
    private val capabilityResolvers: List<TargetProjectionCapabilityResolver> = emptyList(),
    private val executionGates: List<TargetProjectionExecutionGate> = emptyList()
) {
    init {
        require(targets.isNotEmpty()) { "Target manifest pipeline requires a non-empty target registry." }
    }

    private val composedCapabilityResolvers: List<TargetProjectionCapabilityResolver> =
        capabilityResolvers + executionGates.filterIsInstance<TargetProjectionCapabilityResolver>()

    fun effectiveTarget(plan: ExecutionPlan, target: String): TargetCapability {
        val declared = requireNotNull(targets[target]) { "Unknown target '$target'." }
        return composedCapabilityResolvers.fold(declared) { current, resolver ->
            resolver.resolve(plan, target, current).also { resolved ->
                require(resolved.target == target) {
                    "Capability resolver for '$target' returned capability '${resolved.target}'."
                }
            }
        }
    }

    fun effectiveTargets(plan: ExecutionPlan, target: String): Map<String, TargetCapability> =
        targets + (target to effectiveTarget(plan, target))

    fun generate(request: TargetMaterializationRequest): TargetManifest {
        val provider = projections.requireProvider(request.target)
        val authority = MandatoryMaterializationAuthority(effectiveTargets(request.plan, request.target))
        val planningAuthorization = authority.authorize(request)
        val authorization = planningAuthorization.bindGraphAuthorization(request.authorization)
        executionGates.forEach { gate -> gate.requireAuthorized(authorization.plan, authorization.target) }
        return provider.generate(authorization)
    }

    fun generateDiagnosticEvidence(
        request: TargetDiagnosticMaterializationRequest
    ): TargetManifest {
        val provider = projections.requireProvider(request.target)
        val authority = MandatoryMaterializationAuthority(effectiveTargets(request.plan, request.target))
        val planningAuthorization = authority.authorizeDiagnosticEvidence(request)
        return provider.generate(planningAuthorization.bindGraphAuthorization(request.authorization))
    }

    private fun TargetProjectionAuthorization.bindGraphAuthorization(
        graphAuthorization: org.flowlang.compiler.CompilationAuthorization
    ): TargetProjectionAuthorization {
        graphAuthorization.requireIntegrity()
        require(graphAuthorization.executionPlan == plan) {
            "Planning validation and canonical graph authorization disagree on the execution plan."
        }
        return TargetProjectionAuthorization(
            plan = graphAuthorization.executionPlan,
            selection = selection,
            compatibility = compatibility,
            strict = strict,
            topology = topology,
            purpose = purpose
        )
    }
}
