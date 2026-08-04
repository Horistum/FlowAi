package org.flowlang.adapters.continuity

import java.io.File
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetProjectionExecutionGate
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.planner.ExecutionPlan

/**
 * Adapter-owned execution gate for continuity evidence.
 *
 * Target capability and topology registries describe available platform
 * mechanisms. This gate proves that the selected provider implements every
 * continuity relation required by the concrete plan before target syntax can be
 * emitted.
 */
class AdapterContinuityProjectionExecutionGate(
    rootDir: File,
    targets: Map<String, TargetCapability>,
    projections: TargetProjectionRegistry
) : TargetProjectionExecutionGate {
    private val authority = AdapterContinuitySatisfactionAuthority(
        rootDir = rootDir,
        targets = targets,
        projections = projections
    )

    override fun requireAuthorized(plan: ExecutionPlan, target: String) {
        authority.requireMatched(plan, target)
    }
}
