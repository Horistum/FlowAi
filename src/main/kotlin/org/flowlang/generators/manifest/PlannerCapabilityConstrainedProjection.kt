package org.flowlang.generators.manifest

import org.flowlang.capabilities.TargetCapability
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlannerCapabilityConstraintGate

/**
 * Safe manifest projection helper.
 *
 * This keeps the renderer boundary honest: target manifest generation is invoked
 * only after the selected target has passed the planner capability constraint gate.
 */
fun TargetManifestGenerator.generateWithCapabilityConstraints(
    plan: ExecutionPlan,
    targets: Map<String, TargetCapability>,
    strict: Boolean = false
): TargetManifest {
    val gate = PlannerCapabilityConstraintGate(targets)
    val compatibility = gate.compatibilityForProjection(plan, target, strict)
    return generate(plan, compatibility)
}
