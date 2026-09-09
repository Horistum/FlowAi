package org.flowlang.adapters.continuity

import java.io.File
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetProjectionCapabilityResolver
import org.flowlang.generators.manifest.TargetProjectionExecutionGate
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.providerFor
import org.flowlang.generators.manifest.requireProvider

/**
 * Adapter-owned continuity boundary composed at the production projection edge.
 *
 * It has two distinct responsibilities over the same evidence source:
 * 1. produce a bounded plan-specific capability view before generic matching;
 * 2. require exact continuity satisfaction before executable provider rendering.
 */
class AdapterContinuityProjectionExecutionGate(
    rootDir: File,
    targets: Map<String, TargetCapability>,
    projections: AdapterCatalog<TargetProjectionProvider>,
    scopedSupports: List<AdapterContinuityScopedSupport>,
    private val capabilityResolver: org.flowlang.generators.manifest.TargetProjectionCapabilityResolver
) : TargetProjectionExecutionGate, TargetProjectionCapabilityResolver {
    private val authority = AdapterContinuitySatisfactionAuthority(
        rootDir = rootDir,
        targets = targets,
        projections = projections,
        scopedSupports = scopedSupports
    )

    override fun resolve(
        authorization: CompilationAuthorization,
        target: String,
        declared: TargetCapability
    ): TargetCapability = capabilityResolver.resolve(authorization, target, declared)

    override fun requireAuthorized(authorization: CompilationAuthorization, target: String) {
        authorization.requireIntegrity()
        authority.requireMatched(authorization.executionPlan, target)
    }
}
