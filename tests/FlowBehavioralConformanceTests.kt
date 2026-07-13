package org.flowlang.tests

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanNode
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioral conformance for the generators, via reference semantics.
 *
 * Real Jenkins/GitHub Actions/Tekton execution is out of scope for the unit suite, so
 * this proves behavioral equivalence with an INDEPENDENT reference interpreter rather
 * than golden-file text equality: it computes, from the canonical plan and (separately)
 * from each generated manifest, which tasks execute under a given guard assignment, and
 * asserts they agree. A generation bug that drops or mistranslates a guard makes the two
 * diverge - demonstrated by the deliberately-corrupted "teeth" case.
 *
 * The interpreter lives only in the test sources: the standard itself never executes
 * workflows (docs/ARCHITECTURE_CONSTITUTION.md). This is a conformance oracle, like a
 * reference interpreter used to test a compiler back-end.
 */
class FlowBehavioralConformanceTests {

    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        .filterKeys { it in setOf("jenkins", "github-actions", "tekton") }
    private val generators = mapOf(
        "jenkins" to JenkinsManifestGenerator(),
        "github-actions" to GitHubActionsManifestGenerator(),
        "tekton" to TektonManifestGenerator()
    )

    /** Golden plan: build -> test -> (deploy when env==prod, else notify). Non-nested guards stay simple. */
    private fun goldenPlan(): ExecutionPlan = ExecutionPlan(
        flowName = "behavioral-flow",
        nodes = listOf(
            TaskNode(id = "build", module = "ci", action = "build", target = "all"),
            TaskNode(id = "test", module = "ci", action = "test", target = "all", dependsOn = listOf("build")),
            ConditionNode(
                id = "env-gate",
                condition = "env == 'prod'",
                then = listOf(TaskNode(id = "deploy", module = "cd", action = "deploy", target = "all", dependsOn = listOf("test"))),
                otherwise = listOf(TaskNode(id = "notify", module = "ops", action = "notify", target = "all", dependsOn = listOf("test")))
            )
        )
    )

    private fun manifestFor(target: String, plan: ExecutionPlan): TargetManifest =
        generators.getValue(target).generate(plan, CompatibilityAnalyzer(targets).analyze(plan, target))

    // --- reference guard semantics (handles bare conditions and `not (...)`) ---
    private fun guardHolds(guard: String?, truth: Map<String, Boolean>): Boolean {
        val g = guard?.trim().orEmpty()
        if (g.isEmpty()) return true
        if (g.startsWith("not (") && g.endsWith(")")) {
            val inner = g.substring(5, g.length - 1).trim()
            return !(truth[inner] ?: error("scenario missing truth for guard: $inner"))
        }
        return truth[g] ?: error("scenario missing truth for guard: $g")
    }

    private fun combine(a: String?, b: String?): String? = when {
        a.isNullOrBlank() -> b
        b.isNullOrBlank() -> a
        else -> "$a and $b"
    }

    /** Independent: task -> guard, derived from the canonical plan. */
    private fun planTaskGuards(plan: ExecutionPlan): Map<String, String?> {
        val out = linkedMapOf<String, String?>()
        fun walk(nodes: List<PlanNode>, guard: String?) {
            for (n in nodes) when (n) {
                is TaskNode -> out[n.id] = guard
                is ConditionNode -> {
                    walk(n.then, combine(guard, n.condition))
                    walk(n.otherwise, combine(guard, "not (${n.condition})"))
                }
                is ParallelGroupNode -> n.branches.forEach { walk(it.steps, guard) }
                is RetryGroupNode -> walk(n.body, guard)
                is TryPlanNode -> { walk(n.body, guard); walk(n.errorHandler, guard) }
                is LoopNode -> walk(n.body, guard)
                is MatchPlanNode -> { n.cases.forEach { walk(it.steps, guard) }; walk(n.errorCase, guard); walk(n.defaultSteps, guard) }
                else -> Unit
            }
        }
        walk(plan.nodes, null)
        return out
    }

    /** Independent: task -> guard, derived from a generated manifest (Jenkins condition-steps or job conditions). */
    private fun manifestTaskGuards(manifest: TargetManifest): Map<String, String?> {
        val out = linkedMapOf<String, String?>()
        fun walk(steps: List<TargetStep>, guard: String?) {
            for (s in steps) when {
                s.type == "condition" -> walk(s.children, combine(guard, s.params["condition"]))
                s.children.isNotEmpty() -> walk(s.children, guard)
                s.type == "action" -> out[s.name] = guard
                else -> Unit
            }
        }
        for (job in manifest.jobs) walk(job.steps, job.metadata["condition"])
        return out
    }

    private fun planReachable(plan: ExecutionPlan, truth: Map<String, Boolean>): Set<String> =
        planTaskGuards(plan).filterValues { guardHolds(it, truth) }.keys

    private fun manifestReachable(manifest: TargetManifest, truth: Map<String, Boolean>): Set<String> =
        manifestTaskGuards(manifest).filterValues { guardHolds(it, truth) }.keys

    @Test
    fun everyTargetMatchesPlanBehaviorWhenGuardTrue() {
        val plan = goldenPlan()
        val truth = mapOf("env == 'prod'" to true)
        val expected = planReachable(plan, truth)
        assertEquals(setOf("build", "test", "deploy"), expected)
        for (target in generators.keys) {
            assertEquals(expected, manifestReachable(manifestFor(target, plan), truth),
                "Target '$target' diverged from plan behavior when the guard is true.")
        }
    }

    @Test
    fun everyTargetMatchesPlanBehaviorWhenGuardFalse() {
        val plan = goldenPlan()
        val truth = mapOf("env == 'prod'" to false)
        val expected = planReachable(plan, truth)
        assertEquals(setOf("build", "test", "notify"), expected)
        for (target in generators.keys) {
            assertEquals(expected, manifestReachable(manifestFor(target, plan), truth),
                "Target '$target' diverged from plan behavior when the guard is false.")
        }
    }

    @Test
    fun noTaskIsDroppedOrAddedAcrossTargets() {
        val plan = goldenPlan()
        val planTasks = planTaskGuards(plan).keys
        for (target in generators.keys) {
            assertEquals(planTasks, manifestTaskGuards(manifestFor(target, plan)).keys,
                "Target '$target' changed the task set vs the plan.")
        }
    }

    /** Teeth: a generation bug that silently drops a guard MUST be detected as behavioral divergence. */
    @Test
    fun harnessDetectsASilentlyDroppedGuard() {
        val plan = goldenPlan()
        val truth = mapOf("env == 'prod'" to false)
        val planRan = planReachable(plan, truth) // build, test, notify (deploy correctly SKIPPED)

        val good = manifestFor("github-actions", plan)
        val corrupted = good.copy(jobs = good.jobs.map { job ->
            if (job.name == "deploy") job.copy(metadata = job.metadata - "condition") else job
        })
        val corruptedRan = manifestReachable(corrupted, truth)

        assertTrue("deploy" in corruptedRan, "Corrupted manifest must run deploy unconditionally.")
        assertTrue(planRan != corruptedRan, "Harness must detect the corrupted manifest diverging from plan behavior.")
    }
}
