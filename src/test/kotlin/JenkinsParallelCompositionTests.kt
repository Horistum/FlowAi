import java.io.File
import kotlin.test.*
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.ast.ActionNode
import org.flowlang.ast.ParallelNode
import org.flowlang.capabilities.SupportLevel
import org.flowlang.conformance.GitHubConditionCompositionFixture
import org.flowlang.conformance.ParallelCompositionFixture
import org.flowlang.conformance.RetryCompositionFixture
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.generators.manifest.*
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.modules.ModuleRegistry

class JenkinsParallelCompositionTests {
    private val root = File(".")
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File(root, "targets")) }
    private val provider get() = ReferenceTargetProjections.registry.requireProvider("jenkins")
    private val pipeline by lazy { ReferenceTargetProjections.pipeline(targets, modules = ModuleRegistry.fromDirectory(File(root, "modules"))) }
    private fun compile(source: String) = RetryCompositionFixture.compile(root, source)
    private fun manifest(source: String) = pipeline.generate(TargetMaterializationRequest.fromCompilation(
        compile(source), testTargetSelection("jenkins", targets), strict = true))
    private fun source(branches: String, suffix: String = "") = """
        version "1.0"
        use module "git" version "1.0"
        use module "docker" version "1.0"
        flow "parallel" {
          systems { system "source" { type: git } system "builder" { type: docker } }
          steps { parallel failFast false { $branches } $suffix }
        }
    """.trimIndent()

    @Test fun cardinalityLabelsMessagesAndLeafPositionsDoNotDefineApplicability() {
        for (count in listOf(1, 2, 5)) {
            val branches = (1..count).joinToString("\n") { i ->
                """branch "same" {
                    ${ParallelCompositionFixture.checkout("before$i")}
                    ${ParallelCompositionFixture.GATE}
                    ${ParallelCompositionFixture.GATE}
                    ${ParallelCompositionFixture.checkout("after$i", "alternate")}
                }"""
            }
            val rendered = provider.render(manifest(source(branches)))
            val inputs = rendered.lineSequence().filter { it.trimStart().startsWith("input message:") }.toList()
            assertEquals(count * 2, inputs.size)
            assertEquals(inputs.size, inputs.map { it.substringAfter(", id: ") }.distinct().size)
            assertTrue(inputs.all { it.contains(", id: 'Flow_approve_") })
            assertEquals(count * 2, rendered.lineSequence().count { it.trimStart().startsWith("dir(") })
            assertEquals(count * 2, rendered.lineSequence().count { it.trimStart().startsWith("git branch:") })
        }
        for (fast in listOf(false, true)) for (failure in listOf(false, true)) {
            val rendered = provider.render(manifest(ParallelCompositionFixture.source(fast, failure)))
            assertEquals(2, rendered.lineSequence().count { it.trim() == "parallel([" })
            assertEquals(2, rendered.lineSequence().count { it.trim() == "failFast: $fast" })
        }
        assertEquals(SupportLevel.UNSUPPORTED, targets.getValue("jenkins").parallel)
    }

    @Test fun emptyAndHeterogeneousBranchesRetainMembership() {
        val generated = manifest(source("""branch "empty" { } branch "control" { ${ParallelCompositionFixture.GATE} }
            branch "workspace" { ${ParallelCompositionFixture.checkout("unbound")} }"""))
        val group = generated.jobs.single().steps.single()
        assertEquals(listOf(0, 1, 1), group.children.map { it.children.size })
        val rendered = provider.render(generated)
        group.children.forEach { assertContains(rendered, "'${it.id}': {") }
    }

    @Test fun nativeLeafSupportDoesNotEstablishSharedServiceIsolation() {
        for (body in listOf("docker.build builder { image: \"example/app:test\" path: \".\" push: false }",
            "if true { ${ParallelCompositionFixture.GATE} }", "parallel { branch \"nested\" { } }",
            "retry { max: 2 delay: \"0s\" backoff: fixed } { }"))
            assertFails { provider.render(manifest(source("branch \"unknown\" { $body }"))) }
        val dynamic = source("branch \"dynamic\" { ${ParallelCompositionFixture.checkout("dynamic")} }")
            .replace("flow \"parallel\" {", "flow \"parallel\" { input { revision: text default \"selected\" }")
            .replace("branch: \"selected\"", "branch: revision")
        assertFails { provider.render(manifest(dynamic)) }
        // An unused alias is harmless; branch-produced values are not exported.
        assertFails { provider.render(manifest(source("branch \"producer\" { ${ParallelCompositionFixture.checkout("result")} }",
            "if result.ok { ${ParallelCompositionFixture.checkout("consumer")} }"))) }
    }

    @Test fun localOrderingIsAcceptedButSiblingDependenciesAreNot() {
        val doc = compile(source("""branch "first" { ${ParallelCompositionFixture.checkout("a")} ${ParallelCompositionFixture.checkout("b")} }
            branch "second" { ${ParallelCompositionFixture.GATE} }""")).ast
        val group = doc.flow.steps.single() as ParallelNode
        fun bound(crossing: Boolean): TargetManifest {
            val first = group.branches.first()
            val producer = first.steps.first()
            val consumer = (first.steps[1] as ActionNode).copy(dependsOn = listOf("a"))
            val changed = group.copy(branches = if (crossing) listOf(first.copy(steps = listOf(producer)),
                group.branches[1].copy(steps = listOf(consumer))) else listOf(first.copy(steps = listOf(producer, consumer)), group.branches[1]))
            val unit = GitHubConditionCompositionFixture.compile(root, doc.copy(flow = doc.flow.copy(steps = listOf(changed))))
            return pipeline.generate(TargetMaterializationRequest.fromCompilation(unit, testTargetSelection("jenkins", targets), strict = true))
        }
        provider.render(bound(false))
        assertFails { provider.render(bound(true)) }
    }

    @Test fun directRendererRechecksIsolationDependenciesPathsAndControlShape() {
        val original = manifest(source("""branch "one" { ${ParallelCompositionFixture.checkout("a")} }
            branch "two" { ${ParallelCompositionFixture.checkout("b")} }"""))
        val job = original.jobs.single(); val group = job.steps.single(); val branch = group.children.first()
        val task = branch.children.single(); val sibling = group.children[1].children.single()
        val changes = listOf(branch.copy(id = "../shared"), branch.copy(id = group.children[1].id),
            branch.copy(children = listOf(task.copy(dependsOn = listOf(sibling.id)))),
            branch.copy(children = listOf(task.copy(rendererPayload = task.rendererPayload!!.copy(reference = "docker-build")))),
            branch.copy(children = listOf(task.copy(children = listOf(task.copy(id = "hidden"))))))
        for (changed in changes) assertFails { provider.renderer.render(original.copy(jobs = listOf(job.copy(
            steps = listOf(group.copy(children = listOf(changed) + group.children.drop(1))))))) }
    }
}
