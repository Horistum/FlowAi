package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import org.flowlang.planner.ExecutionPlan
import org.flowlang.preview.PlanPreview

class FlowPlanPreviewTests {
    @Test
    fun planPreviewReportIsExplicitlyNonExecuting() {
        val report = PlanPreview().render(ExecutionPlan(flowName = "preview"))
        assertEquals("planning-preview", report.mode)
        assertFalse(report.executesCommands)
    }
}
