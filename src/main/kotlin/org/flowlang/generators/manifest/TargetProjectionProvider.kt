package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.planner.ExecutionPlan

/**
 * Target-neutral generation boundary. Concrete target implementations live outside
 * Flow Core and are composed explicitly at an application or conformance edge.
 */
interface TargetManifestGenerator {
    val target: String
    fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest
}

/**
 * Reconciles every generated manifest before it can leave a concrete provider.
 */
abstract class ReconciledTargetManifestGenerator : TargetManifestGenerator {
    final override fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest =
        buildManifest(plan, compatibility).reconcileCompatibilityReadiness()

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
    val artifactFileName: String = renderer.artifactFileName

    init {
        require(target.isNotBlank()) { "Target projection provider must declare a non-blank target id." }
        require(renderer.target == target) {
            "Target projection provider mismatch: generator '${generator.target}' cannot be paired with renderer '${renderer.target}'."
        }
        require(artifactFileName.isNotBlank()) {
            "Target projection provider '$target' must declare a non-blank artifact file name."
        }
        require('/' !in artifactFileName && '\\' !in artifactFileName && artifactFileName !in setOf(".", "..")) {
            "Target projection provider '$target' must declare a file name, not a path: '$artifactFileName'."
        }
    }

    fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest {
        require(compatibility.target == target) {
            "Projection provider '$target' cannot generate compatibility evidence for '${compatibility.target}'."
        }
        return generator.generate(plan, compatibility)
    }

    fun render(manifest: TargetManifest): String {
        require(manifest.target == target) {
            "Projection provider '$target' cannot render manifest target '${manifest.target}'."
        }
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
 * Canonical manifest pipeline. Available target projections are injected instead
 * of being hardcoded in Flow Core.
 */
class TargetManifestGenerationPipeline(
    private val projections: TargetProjectionRegistry
) {
    fun generate(
        plan: ExecutionPlan,
        compatibility: CompatibilityReport,
        strict: Boolean = false
    ): TargetManifest {
        compatibility.assertAllowed(strict = strict)
        return projections.requireProvider(compatibility.target).generate(plan, compatibility)
    }

    companion object {
        /** Temporary bridge removed with the legacy monolithic conformance call site. */
        internal fun generate(
            plan: ExecutionPlan,
            compatibility: CompatibilityReport,
            strict: Boolean = false
        ): TargetManifest = org.flowlang.targets.builtin.BuiltInTargetProjections
            .pipeline()
            .generate(plan, compatibility, strict)
    }
}
