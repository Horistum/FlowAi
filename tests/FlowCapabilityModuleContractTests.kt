package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertTrue
import org.flowlang.modules.ModuleContractAnalyzer
import org.flowlang.modules.ModuleRegistry
import java.io.File

class FlowCapabilityModuleContractTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)

    @Test
    fun capabilityModuleContractReportHasNoBlockingErrors() {
        val report = ModuleContractAnalyzer.analyze(registry)
        assertTrue(report.valid, report.issues.filter { it.level == "error" }.joinToString { it.code + ": " + it.message })
        assertTrue(report.totals.modules >= 5)
        assertTrue(report.totals.actions > 0)
    }

    @Test
    fun capabilityModuleContractReportExposesTargetImplications() {
        val report = ModuleContractAnalyzer.analyze(registry)
        assertTrue(report.totals.targetImplicationActions > 0)
        assertTrue(report.modules.any { module ->
            module.actions.any { action -> action.targetImplications.isNotEmpty() }
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
