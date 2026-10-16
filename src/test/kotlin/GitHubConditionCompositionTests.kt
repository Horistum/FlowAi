package org.flowlang.conformance

import java.io.File
import kotlin.test.*
import org.flowlang.ast.*
import org.flowlang.capabilities.SupportLevel
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.generators.manifest.*
import org.flowlang.parser.ExpressionParser
import org.flowlang.targets.builtin.GitHubConditionProjectionScope

class GitHubConditionCompositionTests {
    private val root = File(".")
    private val fixture = GitHubConditionCompositionFixture
    private fun generated() = fixture.compile(root).let { compilation ->
        compilation to fixture.manifest(root, compilation, "github-actions") }

    @Test fun bothAdaptersConsumeIdenticalCanonicalBranchesWithoutInventingSemanticEdges() {
        val (compilation, github) = generated()
        val digest = compilation.graphDigest
        val jenkins = fixture.manifest(root, compilation, "jenkins")
        val githubTasks = github.jobs.flatMap { it.steps }.map { it.id }.toSet()
        fun leaves(steps: List<TargetStep>): List<TargetStep> = steps.flatMap { step ->
            if (step.type == "action") listOf(step) else leaves(step.children)
        }
        val jenkinsTasks = leaves(jenkins.jobs.flatMap { it.steps }).map { it.id }.toSet()
        assertEquals((1..7).map { "git_checkout_$it" }.toSet(), githubTasks)
        assertEquals(githubTasks, jenkinsTasks)
        assertEquals(6, compilation.executionPlan.tasks.sumOf { it.dependsOn.size })
        assertEquals(digest, compilation.graphDigest)
        compilation.authorization.requireIntegrity()
        for ((target, manifest) in listOf("github-actions" to github, "jenkins" to jenkins)) {
            assertEquals(TargetRenderMode.EXECUTABLE, TargetRenderPolicy.evaluate(manifest).mode)
            assertTrue(ReferenceTargetProjections.registry.requireProvider(target).render(manifest).isNotBlank())
        }
        val jobs = github.jobs.associateBy { it.id }
        assertEquals(listOf("git_checkout_3", "git_checkout_4", "git_checkout_2", "git_checkout_1"), jobs.getValue("git_checkout_5").dependsOn)
        assertEquals(emptyList(), jobs.getValue("git_checkout_7").dependsOn)
    }

    @Test fun branchSelectionsAgreeAcrossBothProjectionModels() {
        val (compilation, github) = generated()
        val jenkins = fixture.manifest(root, compilation, "jenkins")
        for (enabled in listOf(false, true)) {
            fun evaluate(source: String): Boolean = evaluate(ExpressionParser.parseSource(source), enabled)
            fun active(steps: List<TargetStep>): Set<String> = steps.flatMap { step -> when (step.type) {
                "condition" -> if (evaluate(step.params.getValue("condition"))) active(step.children).toList() else emptyList()
                "action" -> listOf(step.id)
                else -> error("Unexpected structure ${step.type}")
            } }.toSet()
            val expected = setOf("git_checkout_1", "git_checkout_5", "git_checkout_6", "git_checkout_7") +
                if (enabled) setOf("git_checkout_2", "git_checkout_3") else setOf("git_checkout_4")
            assertEquals(expected, active(jenkins.jobs.single().steps))
            assertEquals(expected, github.jobs.filter { it.metadata["condition"]?.let(::evaluate) ?: true }.map { it.id }.toSet())
        }
    }

    @Test fun leafKindAndIndependentInputDoNotDefineConditionEligibility() {
        val document = fixture.document()
        val extra = document.copy(flow = document.flow.copy(input = document.flow.input + InputNode(name = "unrelated",
            valueType = ValueTypeNode("text"), default = StringLiteralNode(value = "ignored")),
            steps = document.flow.steps + ApproveNode(mode = "manual")))
        val compilation = fixture.compile(root, extra)
        val declared = TargetRegistryYamlLoader.loadDirectory(File(root, "targets")).getValue("github-actions")
        val scope = GitHubConditionProjectionScope(ReferenceTargetProjections.nativeCatalogs.getValue("github-actions"))
        assertEquals(SupportLevel.SUPPORTED, scope.resolve(compilation.authorization, "github-actions", declared).features["condition.evaluate"])
        assertEquals(SupportLevel.PARTIAL, declared.conditions)
        assertEquals(declared, GitHubConditionProjectionScope(null).resolve(compilation.authorization, "github-actions", declared))
    }

    @Test fun missingInputContextOrDefaultDoesNotInventBooleanValues() {
        val document = fixture.document()
        val declared = TargetRegistryYamlLoader.loadDirectory(File(root, "targets")).getValue("github-actions")
        val scope = GitHubConditionProjectionScope(ReferenceTargetProjections.nativeCatalogs.getValue("github-actions"))
        val variants = listOf(
            document.copy(flow = document.flow.copy(input = document.flow.input.map { it.copy(default = null) })),
            document.copy(flow = document.flow.copy(triggers = listOf(TriggerNode(id = "push", triggerType = "EVENT", event = "push")))))
        variants.forEach { variant ->
            assertEquals(declared, scope.resolve(fixture.compile(root, variant).authorization, "github-actions", declared))
        }
    }

    @Test fun nativeEnvelopeBindsEveryGuardDependencyAndAction() {
        val (_, manifest) = generated()
        val artifact = ReferenceTargetProjections.registry.requireProvider("github-actions").render(manifest)
        val envelope = File(fixture.WORKFLOW).readText()
        fixture.validateEnvelope(artifact, envelope)
        val mutants = listOf(
            envelope.replaceFirst("needs.git_checkout_1.result == 'success'", "true"),
            envelope.replaceFirst("needs.git_checkout_2.result == 'skipped'", "needs.git_checkout_2.result != 'success'"),
            envelope.replaceFirst("repository: \"Horistum/FlowAi\"", "repository: \"elsewhere/repo\""),
            envelope.replaceFirst("    needs: [prepare, git_checkout_1]", "    needs: [prepare]"),
            envelope.replaceFirst("!cancelled()", "always()"))
        mutants.forEach { assertNotEquals(envelope, it); assertFails { fixture.validateEnvelope(artifact, it) } }
    }

    @Test fun directRendererRejectsMissingConditionalAncestorWaits() {
        val (_, manifest) = generated()
        val altered = manifest.copy(jobs = manifest.jobs.map { job ->
            if (job.id == "git_checkout_5") job.copy(dependsOn = listOf("git_checkout_3", "git_checkout_4")) else job })
        assertFails { ReferenceTargetProjections.registry.requireProvider("github-actions").render(altered) }
    }

    private fun evaluate(e: ExpressionNode, enabled: Boolean): Boolean = when (e) {
        is BooleanLiteralNode -> e.value
        is ReferenceNode -> { require(e.path == listOf("enabled")); enabled }
        is UnaryExpressionNode -> { require(e.operator == "not"); !evaluate(e.operand, enabled) }
        is LogicalExpressionNode -> when (e.operator) {
            "and" -> e.operands.all { evaluate(it, enabled) }
            "or" -> e.operands.any { evaluate(it, enabled) }
            else -> error(e.operator)
        }
        else -> error("Unexpected expression $e")
    }
}
