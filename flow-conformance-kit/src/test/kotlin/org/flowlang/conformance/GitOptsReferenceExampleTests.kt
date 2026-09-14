package org.flowlang.conformance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.modules.ModuleRegistry

class GitOptsReferenceExampleTests {
    @Test
    fun standaloneExternalModuleExampleCompilesAndPlansWithoutProductExtension() {
        val root = File("examples/reference/git-opts-planner")
        val modules = ModuleRegistry.fromDirectory(File(root, "modules"))
        val compilation = IntentYamlFrontend(FrontendCompilerComposition.compiler(modules))
            .compile(File(root, "intent/git-change.intent.yaml"))
            .requireAccepted()

        val plan = compilation.executionPlan
        val expectedSteps = listOf(
            "checkout",
            "create-branch",
            "update-readme",
            "commit",
            "push",
            "open-pull-request"
        )
        val expectedActions = mapOf(
            "checkout" to "checkout",
            "create-branch" to "createBranch",
            "update-readme" to "writeFile",
            "commit" to "commit",
            "push" to "push",
            "open-pull-request" to "openPullRequest"
        )

        assertEquals(expectedSteps, plan.tasks.map { requireNotNull(it.sourceId) })
        plan.tasks.forEach { task ->
            val sourceId = requireNotNull(task.sourceId)
            assertEquals("gitops", task.module)
            assertEquals(expectedActions.getValue(sourceId), task.action)
        }

        val required = setOf(
            "git.checkout",
            "git.branch.create",
            "workspace.file.write",
            "git.commit.create",
            "git.ref.publish",
            "scm.change-request.open"
        )
        assertTrue(plan.requiredCapabilities.containsAll(required), plan.requiredCapabilities.toString())

        val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
        assertEquals(setOf("reference-analysis"), targets.keys)
        val negotiation = CompatibilityAnalyzer(targets).negotiate(plan)
        assertEquals(listOf("reference-analysis"), negotiation.blockedTargets)
        assertTrue(negotiation.targets.single().unsupported.containsAll(required))
    }
}
