package org.flowlang.generators.manifest

import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.capabilities.TargetCapability
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.modules.ModuleCatalog

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
        val compatibility = authorization.compatibility
        nativeProjectionCatalog.requireCompatibleRules(compatibility.projectionRules)
        return buildManifest(authorization)
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

    protected abstract fun buildManifest(authorization: TargetProjectionAuthorization): TargetManifest
}

interface TargetManifestRenderer {
    val target: String
    val artifactFileName: String
    fun render(manifest: TargetManifest): String
}

fun interface TargetProjectionCapabilityResolver {
    fun resolve(
        authorization: CompilationAuthorization,
        target: String,
        declared: TargetCapability
    ): TargetCapability
}

fun interface TargetProjectionExecutionGate {
    fun requireAuthorized(authorization: CompilationAuthorization, target: String)
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
    providers: Map<String, TargetProjectionProvider>
) : AdapterCatalog<TargetProjectionProvider> {
    private val providersByTarget = java.util.Collections.unmodifiableMap(LinkedHashMap(providers))
    override val targetIds: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(providersByTarget.keys))

    override fun adapterFor(target: String): TargetProjectionProvider? = providersByTarget[target]
    fun providerFor(target: String): TargetProjectionProvider? = adapterFor(target)

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
            return TargetProjectionRegistry(indexed)
        }

        fun of(vararg providers: TargetProjectionProvider): TargetProjectionRegistry = of(providers.asIterable())
        fun empty(): TargetProjectionRegistry = TargetProjectionRegistry(emptyMap())
    }
}

/** Lookup across the adapter SPI; a catalog cannot relabel a provider as another target. */
fun org.flowlang.adapters.contract.AdapterCatalog<TargetProjectionProvider>.providerFor(
    target: String
): TargetProjectionProvider? = adapterFor(target)?.also { provider ->
    require(provider.target == target) {
        "Adapter catalog key '$target' does not match provider '${provider.target}'."
    }
}

fun org.flowlang.adapters.contract.AdapterCatalog<TargetProjectionProvider>.requireProvider(
    target: String
): TargetProjectionProvider = providerFor(target) ?: error(
    "No target projection provider is registered for '$target'. " +
        "Available providers: ${targetIds.sorted().joinToString().ifBlank { "none" }}."
)

/**
 * Canonical manifest pipeline with explicitly supplied adapters and plan-specific evidence.
 *
 * The untouched compatibility plan is validated before graph authorization so
 * malformed retained fields keep their stable diagnostics. Neither the catalog
 * nor the adapter implementation can replace TargetProjectionAuthorization.
 */
class TargetManifestGenerationPipeline(
    private val targets: Map<String, TargetCapability>,
    private val projections: AdapterCatalog<TargetProjectionProvider>,
    private val capabilityResolvers: List<TargetProjectionCapabilityResolver> = emptyList(),
    private val executionGates: List<TargetProjectionExecutionGate> = emptyList(),
    private val modules: ModuleCatalog
) {
    init {
        require(targets.isNotEmpty()) { "Target manifest pipeline requires a non-empty target registry." }
    }

    private val composedCapabilityResolvers: List<TargetProjectionCapabilityResolver> =
        capabilityResolvers + executionGates.filterIsInstance<TargetProjectionCapabilityResolver>()

    fun effectiveTarget(
        authorization: CompilationAuthorization,
        target: String
    ): TargetCapability {
        authorization.requireIntegrity()
        val declared = requireNotNull(targets[target]) { "Unknown target '$target'." }
        return composedCapabilityResolvers.fold(declared) { current, resolver ->
            resolver.resolve(authorization, target, current).also { resolved ->
                require(resolved.target == target) {
                    "Capability resolver for '$target' returned capability '${resolved.target}'."
                }
            }
        }
    }

    fun effectiveTargets(
        authorization: CompilationAuthorization,
        target: String
    ): Map<String, TargetCapability> = targets + (target to effectiveTarget(authorization, target))

    private fun requireProvider(target: String): TargetProjectionProvider = projections.adapterFor(target)
        ?.also { require(it.target == target) { "Adapter catalog key '$target' returned provider '${it.target}'." } }
        ?: error(
            "No target projection provider is registered for '$target'. " +
                "Available providers: ${projections.targetIds.sorted().joinToString().ifBlank { "none" }}."
        )

    fun generate(request: TargetMaterializationRequest): TargetManifest {
        val rawPlan = request.plan
        ExecutionPlanMaterializationValidator.requireValid(rawPlan, modules)
        val graphAuthorization = request.authorization.also { it.requireIntegrity() }
        require(graphAuthorization.executionPlan == rawPlan) {
            "Planning validation and canonical graph authorization disagree on the execution plan."
        }
        val provider = requireProvider(request.target)
        val authority = MandatoryMaterializationAuthority(effectiveTargets(graphAuthorization, request.target), modules)
        val authorization = authority.authorize(request)
        executionGates.forEach { gate ->
            gate.requireAuthorized(authorization.compilationAuthorization, authorization.target)
        }
        return provider.generate(authorization)
    }

    fun generateDiagnosticEvidence(request: TargetDiagnosticMaterializationRequest): TargetManifest {
        val rawPlan = request.plan
        ExecutionPlanMaterializationValidator.requireValid(rawPlan, modules)
        val graphAuthorization = request.authorization.also { it.requireIntegrity() }
        require(graphAuthorization.executionPlan == rawPlan) {
            "Diagnostic planning validation and canonical graph authorization disagree on the execution plan."
        }
        val provider = requireProvider(request.target)
        val authority = MandatoryMaterializationAuthority(effectiveTargets(graphAuthorization, request.target), modules)
        return provider.generate(authority.authorizeDiagnosticEvidence(request))
    }
}
