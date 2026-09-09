package org.flowlang.generators.manifest

import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.topology.ExecutionTopologyAssessment

/** Immutable, already-authorized input visible to a concrete target adapter. */
data class TargetProjectionContext(
    val compilationAuthorization: CompilationAuthorization,
    val target: String,
    val compatibility: CompatibilityReport,
    val strict: Boolean,
    val topology: ExecutionTopologyAssessment,
    val diagnosticEvidence: Boolean = false
) {
    val plan get() = compilationAuthorization.executionPlan
    val graphDigest: String get() = compilationAuthorization.graphDigest.value

    init {
        compilationAuthorization.requireIntegrity()
        require(target.isNotBlank()) { "Projection context target must not be blank." }
        require(compatibility.target == target) {
            "Projection context target '$target' does not match compatibility target '${compatibility.target}'."
        }
    }
}

interface TargetManifestGenerator {
    val target: String
    val nativeProjectionCatalog: TargetNativeProjectionCatalog
        get() = TargetNativeProjectionCatalog.empty(target)

    fun generate(context: TargetProjectionContext): TargetManifest
}

abstract class ReconciledTargetManifestGenerator : TargetManifestGenerator {
    final override fun generate(context: TargetProjectionContext): TargetManifest {
        require(context.target == target) {
            "Manifest generator '$target' cannot consume context for '${context.target}'."
        }
        nativeProjectionCatalog.requireCompatibleRules(context.compatibility.projectionRules)
        return buildManifest(context)
            .reconcileCompatibilityReadiness()
            .also(nativeProjectionCatalog::requireManifest)
            .also { manifest ->
                if (context.diagnosticEvidence && context.compatibility.hasErrors) {
                    require(!manifest.compatibility.executable) {
                        "Diagnostic projection for unsupported target '$target' cannot claim executable readiness."
                    }
                }
            }
    }

    protected abstract fun buildManifest(context: TargetProjectionContext): TargetManifest
}

interface TargetManifestRenderer {
    val target: String
    val artifactFileName: String
    fun render(manifest: TargetManifest): String
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

    fun generate(context: TargetProjectionContext): TargetManifest {
        require(context.target == target) {
            "Projection provider '$target' cannot consume context for '${context.target}'."
        }
        nativeProjectionCatalog.requireCompatibleRules(context.compatibility.projectionRules)
        return generator.generate(context).also(nativeProjectionCatalog::requireManifest)
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
) : AdapterCatalog<TargetProjectionProvider> {
    override val targetIds: Set<String> = providersByTarget.keys
    override fun adapterFor(target: String): TargetProjectionProvider? = providersByTarget[target]

    fun providerFor(target: String): TargetProjectionProvider? = adapterFor(target)
    fun requireProvider(target: String): TargetProjectionProvider = requireAdapter(target)

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
