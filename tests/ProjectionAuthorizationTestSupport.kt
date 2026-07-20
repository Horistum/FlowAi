package org.flowlang.tests

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestGenerator
import org.flowlang.generators.manifest.TargetProjectionAuthorization
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.planner.ExecutionPlan

/** Low-level projection fixture support. Production callers cannot construct authorization. */
internal fun TargetManifestGenerator.generate(
    plan: ExecutionPlan,
    compatibility: CompatibilityReport,
    strict: Boolean = false
): TargetManifest = generate(TargetProjectionAuthorization(plan, compatibility, strict))

/** Low-level provider fixture support. Production callers use TargetManifestGenerationPipeline. */
internal fun TargetProjectionProvider.generate(
    plan: ExecutionPlan,
    compatibility: CompatibilityReport,
    strict: Boolean = false
): TargetManifest = generate(TargetProjectionAuthorization(plan, compatibility, strict))
