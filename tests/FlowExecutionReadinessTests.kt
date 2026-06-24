package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.ExecutionReadinessStatus
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import java.io.File

class FlowExecutionReadinessTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    private fun referencePlan() =
        IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
            .also { IntentCapabilityValidator(registry).validate(it).assertValid() }
            .let { IntentToAstPlanner(registry).plan(it) }
            .let { FlowPlanner(registry).plan(it) }

    @Test
    fun jenkinsReferencePlanIsReady() {
        val report = ExecutionReadinessAnalyzer(targets).analyze(referencePlan(), "jenkins")

        assertEquals(ExecutionReadinessStatus.READY, report.readiness)
        assertTrue(report.generationAllowed)
        assertTrue(report.productionReady)
        assertTrue(report.blockers.isEmpty())
    }

    @Test
    fun githubReferencePlanIsDegradedButGeneratable() {
        val report = ExecutionReadinessAnalyzer(targets).analyze(referencePlan(), "github-actions")

        assertEquals(ExecutionReadinessStatus.DEGRADED, report.readiness)
        assertTrue(report.generationAllowed)
        assertFalse(report.productionReady)
        assertTrue(report.warnings.any { it.capability == "approval.manual" })
    }

    @Test
    fun tektonReferencePlanIsBlocked() {
        val report = ExecutionReadinessAnalyzer(targets).analyze(referencePlan(), "tekton")

        assertEquals(ExecutionReadinessStatus.BLOCKED, report.readiness)
        assertFalse(report.generationAllowed)
        assertFalse(report.productionReady)
        assertTrue(report.blockers.any { it.capability == "approval.manual" || it.capability == "approvals" })
    }
}
