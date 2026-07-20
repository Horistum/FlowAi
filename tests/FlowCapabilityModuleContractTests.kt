package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.modules.ModuleContractAnalyzer
import org.flowlang.modules.ModuleRegistry
import java.io.File

class FlowCapabilityModuleContractTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"))

    @Test
    fun capabilityModuleContractReportHasNoBlockingErrors() {
        val report = ModuleContractAnalyzer.analyze(registry)
        assertTrue(report.valid, report.issues.filter { it.level == "error" }.joinToString { it.code + ": " + it.message })
        assertTrue(report.totals.modules >= 5)
        assertTrue(report.totals.actions > 0)
    }

    @Test
    fun capabilityModuleContractsDoNotOwnTargetImplications() {
        val report = ModuleContractAnalyzer.analyze(registry)

        assertEquals(0, report.totals.targetImplicationActions)
        assertTrue(report.modules.all { module ->
            module.actions.all { action -> action.targetImplications.isEmpty() }
        })
    }

    @Test
    fun destructiveActionsRequireSafety() {
        val report = ModuleContractAnalyzer.analyze(registry)
        val destructive = report.modules.flatMap { it.actions }.filter { it.destructive }
        assertTrue(destructive.isNotEmpty())
        assertTrue(destructive.all { it.requiresSafety })
    }
}
