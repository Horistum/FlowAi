package org.flowlang.conformance

import java.io.File
import kotlin.test.*
import org.flowlang.adapters.continuity.*
import org.flowlang.ast.*
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.generators.manifest.*
import org.flowlang.planner.*
import org.flowlang.targets.builtin.GitHubActionsWorkspaceContinuityPlanner

class GitHubWorkspaceCompositionTests {
    private val root = File(".")
    private val revision = GitHubConditionCompositionFixture.REVISION
    private val fixture = GitHubWorkspaceCompositionFixture
    private val provider get() = ReferenceTargetProjections.registry.requireProvider("github-actions")
    private fun compile(document: FlowDocument) = GitHubConditionCompositionFixture.compile(root, document)
    private fun manifest(document: FlowDocument) = GitHubConditionCompositionFixture.manifest(root, compile(document), "github-actions")
    private fun matched(plan: ExecutionPlan): Boolean = AdapterContinuityRequirementAuthority.derive(plan).all {
        AdapterContinuityScopedSupportAuthority.matchingSupport(plan, "github-actions", it,
            BuiltInAdapterContinuityScopedSupport.declarations) != null
    }

    @Test fun repeatedConsumersShareOneUploadWithIndependentDownloads() {
        for (count in listOf(1, 2, 5)) {
            val document = fixture.document(revision, count)
            val unit = compile(document)
            assertTrue(matched(unit.authorization.executionPlan))
            val m = GitHubConditionCompositionFixture.manifest(root, unit, "github-actions")
            assertEquals(TargetRenderMode.EXECUTABLE, TargetRenderPolicy.evaluate(m).mode)
            assertEquals(count.toString(), m.metadata["workspaceContinuityTransferCount"])
            assertEquals(count + 2, m.jobs.size)
            val upload = m.jobs.first().steps.single { it.type == "adapter-continuity-upload" }
            val identity = upload.rendererPayload!!.bindings.getValue("name")
            val consumers = m.jobs.filter { it.id.startsWith("docker_build_") }
            assertEquals(count, consumers.size)
            consumers.forEach { job ->
                assertEquals(listOf("git_checkout_1"), job.dependsOn)
                assertEquals(listOf("adapter-continuity-download", "action"), job.steps.map { it.type })
                assertEquals(identity, job.steps.first().rendererPayload!!.bindings.getValue("name"))
            }
            assertEquals(1, m.jobs.last().steps.size)
            assertTrue(m.jobs.last().dependsOn.isEmpty())
            provider.render(m)
        }
    }

    @Test fun independentProducerPathsDoNotMergeArtifactsOrDependOnTaskOrder() {
        val original = fixture.document(revision, 2)
        val steps = original.flow.steps
        val secondProducer = (steps.first() as ActionNode).copy(result = ResultBindingNode(name = "otherSnapshot"))
        val secondConsumer = (steps[1] as ActionNode).copy(result = ResultBindingNode(name = "otherBuild"),
            dependsOn = listOf("otherSnapshot"))
        val expanded = original.copy(flow = original.flow.copy(steps = listOf(steps.first(), secondProducer,
            steps[1], secondConsumer, steps[2], steps.last())))
        val m = manifest(expanded)
        val transfers = m.jobs.flatMap { it.steps }.filter { it.type.startsWith("adapter-continuity-") }
        val uploads = transfers.filter { it.type == "adapter-continuity-upload" }
        val downloads = transfers.filter { it.type == "adapter-continuity-download" }
        assertEquals(2, uploads.size)
        assertEquals(3, downloads.size)
        assertEquals(2, uploads.map { it.rendererPayload!!.bindings.getValue("name") }.toSet().size)
        downloads.forEach { download ->
            val source = download.metadata.getValue("continuitySourceJob")
            assertEquals(m.jobs.single { it.id == source }.steps.last().rendererPayload!!.bindings.getValue("name"),
                download.rendererPayload!!.bindings.getValue("name"))
        }
        provider.render(m)
    }

    @Test fun unsupportedEndpointsChannelsOrderingAndMergingFailClosed() {
        val plan = compile(fixture.document(revision, 1)).authorization.executionPlan
        val source = plan.nodes.first() as TaskNode
        val consumer = plan.nodes[1] as TaskNode
        val relation = plan.dependencyRelations.single { it.kind == PlanDependencyKind.WORKSPACE }
        fun withRelation(changed: PlanDependencyRelation) = plan.copy(dependencyRelations =
            plan.dependencyRelations.map { if (it == relation) changed else it })
        val invalid = listOf(
            withRelation(relation.copy(channel = "other")),
            withRelation(relation.copy(sourceNodeId = "absent")),
            withRelation(relation.copy(resolution = PlanDependencyResolution.AMBIGUOUS)),
            withRelation(relation.copy(resolution = PlanDependencyResolution.UNRESOLVED)),
            plan.copy(nodes = listOf(ConditionNode("guard", condition = "true", then = listOf(source))) + plan.nodes.drop(1)),
            plan.copy(nodes = listOf(source, ConditionNode("guard", condition = "true", then = listOf(consumer))) + plan.nodes.drop(2)),
            plan.copy(nodes = listOf(source, consumer.copy(dependsOn = emptyList())) + plan.nodes.drop(2)),
            plan.copy(nodes = listOf(source.copy(action = "push")) + plan.nodes.drop(1)),
            plan.copy(dependencyRelations = plan.dependencyRelations + relation.copy(channel = "other")))
        invalid.forEach { assertFalse(matched(it), it.toString()) }
        // Cardinality promotion never repairs missing canonical workspace continuity.
        val document = fixture.document(revision, 1)
        val unbound = document.copy(flow = document.flow.copy(steps = document.flow.steps.map {
            if (it is ActionNode && it.module == "docker") it.copy(dependsOn = emptyList()) else it
        }))
        assertFails { manifest(unbound) }
    }

    @Test fun absentNativeJobsOrOrderingRetainDiagnosticBlockers() {
        val unit = compile(fixture.document(revision))
        val plan = unit.authorization.executionPlan
        val m = GitHubConditionCompositionFixture.manifest(root, unit, "github-actions")
        val bare = m.jobs.map { it.copy(steps = it.steps.filter { step -> step.type == "action" }) }
        for (jobs in listOf(bare.drop(1), bare.map { if (it.id == "docker_build_1") it.copy(dependsOn = emptyList()) else it })) {
            val result = GitHubActionsWorkspaceContinuityPlanner.materialize(plan, jobs)
            assertTrue(result.flatMap { it.steps }.any { it.type == "adapter-continuity-blocked" })
        }
    }

    @Test fun unrelatedConditionAndInputsDoNotInvalidateWorkspaceEndpoints() {
        val document = fixture.document(revision, 1)
        val changed = document.copy(flow = document.flow.copy(steps = document.flow.steps.dropLast(1) +
            IfNode(condition = BooleanLiteralNode(value = true), then = listOf(document.flow.steps.last()))))
        provider.render(manifest(changed))
    }

    @Test fun directRendererRejectsMissingReorderedAndSubstitutedTransfers() {
        val m = manifest(fixture.document(revision))
        val source = m.jobs.first()
        val consumer = m.jobs[1]
        val download = consumer.steps.first()
        val payload = download.rendererPayload!!
        val changed = listOf(
            source.copy(steps = source.steps.dropLast(1)),
            consumer.copy(steps = consumer.steps.drop(1)),
            consumer.copy(steps = consumer.steps.reversed()),
            consumer.copy(dependsOn = emptyList()),
            consumer.copy(steps = listOf(download.copy(rendererPayload = payload.copy(bindings = payload.bindings +
                ("name" to payload.bindings.getValue("name").copy(name = "other-artifact"))))) + consumer.steps.drop(1)))
        changed.forEach { job ->
            assertFails { provider.renderer.render(m.copy(jobs = m.jobs.map { if (it.id == job.id) job else it })) }
        }
        val rendered = provider.render(m)
        assertTrue(rendered.contains("persist-credentials: false"))
        assertFalse(rendered.contains("{{defaultContext}}"))
    }

    @Test fun executedEnvelopePreservesEveryGeneratedTransferAndFalsifiesTwoMutants() {
        val rendered = provider.render(manifest(fixture.document(revision)))
        val envelope = File(root, fixture.WORKFLOW).readText()
        fixture.validateEnvelope(rendered, envelope, revision)
        for (changed in listOf(envelope.replace("include-hidden-files: \"true\"", "include-hidden-files: \"false\""),
            envelope.replace("needs: [prepare, git_checkout_1]", "needs: [prepare]"),
            envelope.replace("path: \".\"", "path: \"subdirectory\""),
            envelope.replace("flow-workspace-git_checkout_1-source", "wrong-artifact"),
            envelope.replace("test \"\$OUTCOME\" = failure", "true"))) {
            assertNotEquals(envelope, changed)
            assertFails { fixture.validateEnvelope(rendered, changed, revision) }
        }
    }
}
