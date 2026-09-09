package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanInput
import org.flowlang.planner.PlanTrigger

/** Public adapter-facing facade over target-neutral lowering internals. */
object AdapterManifestLowering {
    fun input(value: PlanInput): TargetInput = value.toTargetInput()
    fun trigger(value: PlanTrigger): TargetTrigger = value.toTargetTrigger()
    fun metadata(plan: ExecutionPlan, generator: String): Map<String, String> = baseMetadata(plan, generator)
    fun mappingNotes(report: CompatibilityReport, target: String): List<TargetMappingNote> = report.toMappingNotes(target)
    fun emptyStep(flowName: String): TargetStep = emptyProjectionStep(flowName)
    fun id(value: String): String = sanitizeId(value)
}
