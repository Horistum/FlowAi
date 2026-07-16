package org.flowlang.tests

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.GitHubActionsManifestGenerator
import org.flowlang.targets.builtin.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestGenerator
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.targets.builtin.TektonManifestGenerator
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cross-target behavioral equivalence for the manifest generators.
 *
 * Asserts SEMANTIC properties that must hold for the same plan across every target
 * (task preservation, dependency preservation, consistent guard handling) rather than
 * byte-for-byte equality with a golden file. This is the round-trip-style check the
 * project's portability promise needs: the standard guarantees the same intent yields
 * the same behavior on each target, or an explicit diagnostic where a target cannot.
 */
class FlowGeneratorEquivalenceTests {

    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        .filterKeys { it in setOf("jenkins", "github-actions", "tekton") }

    private val generators: Map<String, TargetManifestGenerator> = mapOf(
        "jenkins" to JenkinsManifestGenerator(),
        "github-actions" to GitHubActionsManifestGenerator(),
        "tekton" to TektonManifestGenerator()
    )

    private fun samplePlan(condition: String): ExecutionPlan = ExecutionPlan(
        flowName = "equivalence-flow",
        nodes = listOf(
            TaskNode(id = "build", module = "ci", action = "build", target = "all"),
            TaskNode(id = "test", module = "ci", action = "test", target = "all", dependsOn = listOf("build")),
            ConditionNode(
                id = "deploy-gate",
                condition = condition,
                then = listOf(TaskNode(id = "deploy", module = "cd", action = "deploy", target = "all", dependsOn = listOf("test")))
            )
        )
    )

    private fun manifestFor(target: String, plan: ExecutionPlan): TargetManifest {
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, target)
        return generators.getValue(target).generate(plan, compatibility)
    }

    private fun stepNames(steps: List<TargetStep>): List<String> =
        steps.flatMap { listOf(it.name) + stepNames(it.children) }

    private fun semanticNames(manifest: TargetManifest): Set<String> =
        manifest.jobs.flatMap { listOf(it.name) + stepNames(it.steps) }.toSet()

    /** Every plan task must appear in every target's manifest - no task silently dropped. */
    @Test
    fun everyTargetPreservesEveryPlanTask() {
        val plan = samplePlan("env == 'prod'")
        val expectedTasks = plan.tasks.map { it.id }.toSet() // build, test, deploy
        for (target in generators.keys) {
            val present = semanticNames(manifestFor(target, plan))
            assertTrue(
                present.containsAll(expectedTasks),
                "Target '$target' dropped tasks: expected $expectedTasks, manifest exposed $present"
            )
        }
    }

    /** A guard expressible on all targets must produce no unsupported-condition diagnostic anywhere. */
    @Test
    fun translatableGuardIsConsistentAcrossTargets() {
        val plan = samplePlan("env == 'prod'")
        for (target in generators.keys) {
            val notes = manifestFor(target, plan).mappingNotes
            assertTrue(
                notes.none { it.feature == "condition.unsupported" },
                "Translatable guard must not be flagged unsupported on '$target'."
            )
        }
    }

    /** Dependency edges must be preserved on job-per-task targets (test<-build, deploy<-test). */
    @Test
    fun dependencyEdgesPreservedOnJobPerTaskTargets() {
        val plan = samplePlan("env == 'prod'")
        for (target in listOf("github-actions", "tekton")) {
            val manifest = manifestFor(target, plan)
            val dependentJobs = manifest.jobs.count { it.dependsOn.isNotEmpty() }
            assertTrue(
                dependentJobs >= 2,
                "Target '$target' must preserve the test<-build and deploy<-test dependencies (found $dependentJobs dependent jobs)."
            )
        }
    }
}
