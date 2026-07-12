package org.flowlang.generators.manifest

import org.flowlang.capabilities.PlannerCapabilityConstraintGate
import org.flowlang.capabilities.TargetCapability
import org.flowlang.planner.ExecutionPlan

/**
 * Safe target-manifest projection entry point.
 *
 * Direct generator classes remain small render-projection units, but callers that
 * have a target registry should use this helper so unsupported planner semantics
 * are stopped before target projection starts. Canonical generators return
 * readiness-reconciled manifests, so capability support is not confused with
 * concrete materialization or executable projection evidence.
 */
fun TargetManifestGenerator.generateWithCapabilityConstraints(
    plan: ExecutionPlan,
    targets: Map<String, TargetCapability>,
    strict: Boolean = false
): TargetManifest {
    val constraintReport = PlannerCapabilityConstraintGate(targets)
        .requireProjectionAllowed(plan, target, strict)
    return generate(plan, constraintReport.compatibility)
}
