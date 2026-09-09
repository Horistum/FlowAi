package org.flowlang.targets.builtin

import java.io.File
import org.flowlang.capabilities.TargetCapability
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetProjectionRegistry

/**
 * Compatibility facade for pre-module callers.
 *
 * Concrete provider composition is owned by flow-reference-distribution. This
 * facade remains until AR-07 removes the pre-module entry point.
 */
@Deprecated("Use ReferenceTargetProjections for distribution composition; scheduled for AR-07 removal")
object BuiltInTargetProjections {
    val registry: TargetProjectionRegistry
        get() = ReferenceTargetProjections.registry

    fun pipeline(
        targets: Map<String, TargetCapability>,
        rootDir: File = File(".")
    ): TargetManifestGenerationPipeline = ReferenceTargetProjections.pipeline(targets, rootDir)
}
