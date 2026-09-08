package org.flowlang.tests

import org.flowlang.frontend.FrontendCompilerComposition

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.contract.TargetAdapterContractAnalyzer
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import java.io.File

class FlowTargetAdapterContractTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    private fun referencePlan() =
        IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
            .also { IntentCapabilityValidator(registry).validate(it).assertValid() }
            .let { FrontendCompilerComposition.intentPlanner(registry).plan(it) }
            .let { FlowPlanner(registry).plan(it) }

    @Test
    fun adapterContractAllowsOnlyExecutionPlanAndTargetReports() {
        val contract = TargetAdapterContractAnalyzer(targets).analyze(referencePlan(), "jenkins")

        assertTrue(contract.generationAllowed)
        assertTrue(contract.allowedInputArtifacts.any { it.name == "execution-plan.json" })
        assertTrue(contract.allowedInputArtifacts.any { it.name == "execution-readiness-report.json" })
        assertFalse(contract.allowedInputArtifacts.any { it.name == "normalized-intent.json" })
        assertTrue(contract.forbiddenInputArtifacts.contains("normalized-intent.json"))
        assertTrue(contract.forbiddenInputArtifacts.contains("intent-decision-report.json"))
        assertTrue(contract.expectedOutputArtifacts.any { it.name == "target-manifest.json" })
        assertTrue(contract.expectedOutputArtifacts.any { it.name == "adapter-diagnostics.json" })
        assertTrue(contract.invariants.any { it.code == "ADAPTER_MUST_NOT_READ_INTENT" })
        assertTrue(contract.invariants.any { it.code == "ADAPTER_MUST_RESPECT_READINESS" })
    }

    @Test
    fun blockedTargetEmitsBlockingAdapterDiagnostics() {
        val contract = TargetAdapterContractAnalyzer(targets).analyze(referencePlan(), "tekton")

        assertFalse(contract.generationAllowed)
        assertTrue(contract.diagnostics.issues.any { it.code == "ADAPTER_CONTRACT_BLOCKED" })
    }
}
