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
            plan.copy(nodes = listOf(source, ConditionNode("guard", condition = "true", then = listOf(
                RetryGroupNode("retry", max = 2, delay = "0s", backoff = "fixed", body = listOf(consumer))))) + plan.nodes.drop(2)),
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

    @Test fun executedEnvelopePreservesEveryGeneratedTransferAndFalsifiesFourMutants() {
        val rendered = provider.render(manifest(fixture.conditionalProducerDocument(revision)))
        val envelope = File(root, fixture.WORKFLOW).readText()
        fixture.validateEnvelope(rendered, envelope, revision)
        for (changed in listOf(envelope.replace("include-hidden-files: \"true\"", "include-hidden-files: \"false\""),
            envelope.replace("needs: [prepare, git_checkout_1]", "needs: [prepare]"),
            envelope.replace("path: \".\"", "path: \"subdirectory\""),
            envelope.replace("flow-workspace-git_checkout_1-source", "wrong-artifact"),
            envelope.replace("if: \${{ ((true && false)) }}", "if: \${{ true }}"),
            envelope.replace("test \"\$OUTCOME\" = failure", "true"))) {
            assertNotEquals(envelope, changed)
            assertFails { fixture.validateEnvelope(rendered, changed, revision) }
        }
    }
    @Test fun nestedConditionConsumersRetainSelectionAndOneSharedUpload() {
        val document = fixture.conditionalDocument(revision)
        val unit = compile(document)
        assertTrue(matched(unit.authorization.executionPlan))
        val m = manifest(document)
        assertEquals("5", m.metadata["workspaceContinuityTransferCount"])
        assertEquals(1, m.jobs.flatMap { it.steps }.count { it.type == "adapter-continuity-upload" })
        val conditional = m.jobs.filter { it.metadata["condition"] != null }
        assertEquals(3, conditional.size)
        conditional.forEach { job ->
            assertEquals(listOf("git_checkout_1"), job.dependsOn)
            assertEquals(listOf("adapter-continuity-download", "action"), job.steps.map { it.type })
            assertEquals(job.metadata["condition"], job.steps.last().metadata["condition"])
        }
        val rendered = provider.render(m)
        assertTrue(rendered.contains("if: \${{ ((true && false)) }}"))
        assertTrue(rendered.contains("if: \${{ ((true && !(false))) }}"))
        assertTrue(rendered.contains("if: \${{ (!(true)) }}"))
    }

    @Test fun consumerPredicatesUseTheIndependentBooleanInputContract() {
        val base = fixture.conditionalDocument(revision)
        val branch = base.flow.steps[3] as IfNode
        for (value in listOf(false, true)) {
            val document = base.copy(flow = base.flow.copy(
                input = base.flow.input + InputNode(name = "enabled", valueType = ValueTypeNode("boolean"),
                    default = BooleanLiteralNode(value = value)),
                steps = base.flow.steps.map { if (it == branch) branch.copy(
                    condition = ReferenceNode(path = listOf("enabled"))) else it }))
            val m = manifest(document)
            assertEquals("5", m.metadata["workspaceContinuityTransferCount"])
            assertTrue(provider.render(m).contains("inputs.enabled"))
            val event = document.copy(flow = document.flow.copy(triggers = listOf(
                TriggerNode(id = "push", triggerType = "EVENT", event = "push"))))
            assertFails { provider.render(manifest(event)) }
        }
    }

    @Test fun directRendererRejectsLostForgedAndUnavailableConsumerGuards() {
        val m = manifest(fixture.conditionalDocument(revision))
        val consumer = m.jobs.first { it.metadata["condition"] != null }
        val leaf = consumer.steps.last()
        val changes = listOf(
            consumer.copy(metadata = consumer.metadata - "condition"),
            consumer.copy(metadata = consumer.metadata + ("condition" to "true")),
            consumer.copy(steps = consumer.steps.dropLast(1) + leaf.copy(metadata = leaf.metadata - "condition")),
            consumer.copy(metadata = consumer.metadata + ("condition" to "missing"), steps = consumer.steps.dropLast(1) +
                leaf.copy(metadata = leaf.metadata + ("condition" to "missing"))),
            consumer.copy(metadata = consumer.metadata + ("errorHandler" to "true")))
        changes.forEach { changed -> assertFails {
            provider.renderer.render(m.copy(jobs = m.jobs.map { if (it.id == changed.id) changed else it }))
        } }
        val producer = m.jobs.first()
        for (guard in listOf("condition" to "true", "errorHandler" to "true")) {
            assertFails { provider.renderer.render(m.copy(jobs = listOf(producer.copy(
                metadata = producer.metadata + guard)) + m.jobs.drop(1))) }
        }
    }

    @Test fun conditionalProducersComposeWithSameBranchAndNestedConsumers() {
        val document = fixture.conditionalProducerDocument(revision)
        assertTrue(matched(compile(document).authorization.executionPlan))
        val m = manifest(document)
        assertEquals(TargetRenderMode.EXECUTABLE, TargetRenderPolicy.evaluate(m).mode)
        assertEquals("9", m.metadata["workspaceContinuityTransferCount"])
        assertEquals(3, m.jobs.flatMap { it.steps }.count { it.type == "adapter-continuity-upload" })
        for ((producer, consumers) in mapOf("git_checkout_3" to listOf(6, 7, 8), "git_checkout_4" to listOf(9))) {
            val source = m.jobs.single { it.id == producer }
            assertNotNull(source.metadata["condition"])
            assertEquals(source.metadata["condition"], source.steps.first().metadata["condition"])
            val artifact = source.steps.last().rendererPayload!!.bindings.getValue("name")
            consumers.forEach { number ->
                val consumer = m.jobs.single { it.id == "docker_build_$number" }
                assertEquals(listOf(producer), consumer.dependsOn)
                assertEquals(artifact, consumer.steps.first().rendererPayload!!.bindings.getValue("name"))
            }
        }
        val rendered = provider.render(m)
        assertTrue(rendered.contains("needs.git_checkout_3.result == 'success'"))
        assertTrue(rendered.contains("needs.git_checkout_4.result == 'skipped' && !(!(true))"))
    }

    @Test fun endpointEligibilityUsesLexicalAncestryInsteadOfPredicateEquality() {
        val plan = compile(fixture.document(revision, 1)).authorization.executionPlan
        val source = plan.nodes[0] as TaskNode
        val consumer = plan.nodes[1] as TaskNode
        fun condition(id: String, yes: List<PlanNode>, no: List<PlanNode> = emptyList()) =
            ConditionNode(id, condition = "true", then = yes, otherwise = no)
        for (nodes in listOf(
            listOf(condition("outer", listOf(source, consumer))),
            listOf(condition("outer", listOf(source, condition("inner", listOf(consumer))))),
            listOf(condition("outer", emptyList(), listOf(source, consumer))))) {
            assertTrue(matched(plan.copy(nodes = nodes)), nodes.toString())
        }
        for (nodes in listOf(
            listOf(condition("outer", listOf(source)), consumer),
            listOf(condition("outer", listOf(source), listOf(consumer))),
            listOf(condition("one", listOf(source)), condition("two", listOf(consumer))),
            listOf(condition("outer", listOf(condition("inner", listOf(source)), consumer))),
            listOf(RetryGroupNode("retry", max = 2, delay = "0s", backoff = "fixed", body = listOf(source, consumer))),
            listOf(LoopNode("loop", item = "item", source = "[\"one\"]", body = listOf(source, consumer))),
            listOf(ParallelGroupNode("parallel", branches = listOf(PlanBranch("branch", listOf(source, consumer))))),
            listOf(TryPlanNode("recovery", body = listOf(source, consumer))))) {
            assertFalse(matched(plan.copy(nodes = nodes)), nodes.toString())
        }
    }

    @Test fun conditionalEndpointCompositionDoesNotDependOnConsumerCardinality() {
        for (count in listOf(1, 3, 6)) {
            val base = fixture.document(revision, count)
            for (selected in listOf(false, true)) {
                val document = base.copy(flow = base.flow.copy(steps = listOf(
                    IfNode(condition = BooleanLiteralNode(value = selected), then = base.flow.steps.dropLast(1)),
                    base.flow.steps.last())))
                val m = manifest(document)
                assertEquals(count.toString(), m.metadata["workspaceContinuityTransferCount"])
                assertEquals(1, m.jobs.flatMap { it.steps }.count { it.type == "adapter-continuity-upload" })
                provider.render(m)
            }
        }
    }

    @Test fun compilerRejectsWorkspaceUseOutsideTheProducerBranch() {
        val base = fixture.document(revision, 1)
        for (selected in listOf(false, true)) {
            val producer = IfNode(condition = BooleanLiteralNode(value = selected), then = listOf(base.flow.steps[0]))
            val escaped = base.copy(flow = base.flow.copy(steps = listOf(producer) + base.flow.steps.drop(1)))
            assertFails { provider.render(manifest(escaped)) }
        }
    }

    @Test fun producerPredicatesRetainBooleanInputAndEventAvailabilityChecks() {
        val base = fixture.conditionalProducerDocument(revision)
        val branch = base.flow.steps.last() as IfNode
        for (value in listOf(false, true)) {
            val document = base.copy(flow = base.flow.copy(
                input = base.flow.input + InputNode(name = "enabled", valueType = ValueTypeNode("boolean"),
                    default = BooleanLiteralNode(value = value)),
                steps = base.flow.steps.dropLast(1) + branch.copy(condition = ReferenceNode(path = listOf("enabled")))))
            val m = manifest(document)
            assertEquals("9", m.metadata["workspaceContinuityTransferCount"])
            assertTrue(provider.render(m).contains("inputs.enabled"))
            val event = document.copy(flow = document.flow.copy(triggers = listOf(
                TriggerNode(id = "push", triggerType = "EVENT", event = "push"))))
            assertFails { provider.render(manifest(event)) }
        }
    }

    private fun withGuard(job: TargetJob, guard: String?): TargetJob = job.copy(
        metadata = if (guard == null) job.metadata - "condition" else job.metadata + ("condition" to guard),
        steps = job.steps.map { step -> if (step.type != "action") step else step.copy(
            metadata = if (guard == null) step.metadata - "condition" else step.metadata + ("condition" to guard)) })

    @Test fun directRendererRejectsProducerGuardDriftAndConsumerBranchEscape() {
        val m = manifest(fixture.conditionalProducerDocument(revision))
        val producer = m.jobs.single { it.id == "git_checkout_3" }
        val consumer = m.jobs.single { it.id == "docker_build_7" }
        val invalid = listOf(
            producer.copy(metadata = producer.metadata - "condition"),
            producer.copy(steps = listOf(producer.steps.first().copy(metadata = producer.steps.first().metadata - "condition")) + producer.steps.drop(1)),
            withGuard(producer, "false"),
            withGuard(producer, "missing"),
            producer.copy(metadata = producer.metadata + ("errorHandler" to "true")),
            withGuard(consumer, null),
            withGuard(consumer, "not (false)"),
            withGuard(consumer, "true or false"),
            consumer.copy(dependsOn = emptyList()))
        invalid.forEach { changed -> assertFails {
            provider.renderer.render(m.copy(jobs = m.jobs.map { if (it.id == changed.id) changed else it }))
        } }
    }

    @Test fun guardRetentionPreservesBooleanStructureAndDoesNotFlattenParentheses() {
        val m = manifest(fixture.conditionalProducerDocument(revision))
        val source = "git_checkout_4"
        val target = "docker_build_9"
        fun render(producer: String, consumer: String) = provider.renderer.render(m.copy(jobs = m.jobs.map {
            when (it.id) { source -> withGuard(it, producer); target -> withGuard(it, consumer); else -> it }
        }))
        render("not (true or false)", "(not (true or false)) and true")
        render("true == false", "(true == false) and (false != true)")
        assertFails { render("not (true or false)", "not true or false") }
        assertFails { render("true == (false == true)", "(true == false) == true") }
        assertFails { render("true or false", "true") }
    }

}
