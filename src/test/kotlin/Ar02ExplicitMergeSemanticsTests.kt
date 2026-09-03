import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.ast.ActionNode
import org.flowlang.ast.BooleanLiteralNode
import org.flowlang.ast.CallExpressionNode
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.FlowNode
import org.flowlang.ast.IfNode
import org.flowlang.ast.InputNode
import org.flowlang.ast.ModuleImportNode
import org.flowlang.ast.NumberLiteralNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.ast.ResultBindingNode
import org.flowlang.ast.SetNode
import org.flowlang.ast.StatementNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.ast.SystemNode
import org.flowlang.ast.ValueTypeNode
import org.flowlang.compiler.CanonicalDependencyKind
import org.flowlang.compiler.CanonicalExecutionGraphBuilder
import org.flowlang.compiler.CanonicalExecutionGraphDigestComputer
import org.flowlang.compiler.CanonicalValueTypeId
import org.flowlang.compiler.CanonicalExecutionGraphValidator
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.CompilationSource
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.FlowSourceCompilationInput
import org.flowlang.core.FlowAvailabilityAnalyzer
import org.flowlang.core.FlowAvailabilityReason
import org.flowlang.core.FlowStatementPath
import org.flowlang.core.FlowValueAvailability
import org.flowlang.core.UnsafeFlowAvailabilityException
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ContinuityChannel
import org.flowlang.modules.ContinuityContract
import org.flowlang.modules.ContinuityKind
import org.flowlang.modules.FlowModule
import org.flowlang.modules.ModuleActionContract
import org.flowlang.modules.ModuleRegistry
import org.flowlang.modules.SchemaField
import org.flowlang.modules.SystemTypeContract
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ControlNode
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyResolution
import org.flowlang.planner.TaskNode
import org.flowlang.validator.FlowValidator

class Ar02ExplicitMergeSemanticsTests {
    private val modules = ModuleRegistry.fromDirectory(File("modules"))

    @Test
    fun exhaustiveExplicitMergeProducesOneMergedIdentityAndPathAwareEdges() {
        val document = mergeFlow(listOf("left", "right"))
        val analysis = FlowAvailabilityAnalyzer().analyze(document)
        val merge = analysis.merges.single()
        val state = analysis.stateAfter(FlowStatementPath.flowStep(1)).binding("joined")

        assertEquals(FlowValueAvailability.MERGED, state.availability)
        assertEquals(FlowAvailabilityReason.EXPLICIT_MERGE, state.reason)
        assertEquals(merge.producer, state.uniqueProducer)
        assertEquals(2, merge.paths.size)
        assertEquals(merge.paths, merge.incoming.flatMap { it.paths }.toSet())

        val validation = FlowValidator(modules).validate(document)
        assertTrue(validation.valid, validation.issues.toString())
        val plan = FlowPlanner(modules).plan(document)
        val condition = plan.nodes[0] as ConditionNode
        val mergeNode = plan.nodes[1] as ControlNode
        val consumer = plan.nodes[2] as TaskNode
        val branchProducers = listOf(condition.then.single().id, condition.otherwise.single().id).toSet()

        assertEquals(listOf(mergeNode.id), consumer.dependsOn)
        assertEquals(
            branchProducers,
            plan.dependencyRelations.filter {
                it.targetNodeId == mergeNode.id && it.kind == PlanDependencyKind.VALUE
            }.mapNotNull { it.sourceNodeId }.toSet()
        )
        assertTrue(plan.dependencyRelations.any {
            it.sourceNodeId == mergeNode.id &&
                it.targetNodeId == consumer.id &&
                it.kind == PlanDependencyKind.VALUE
        })

        val unit = compile(document, "merge-forward")
        assertEquals(1, unit.graph.valueMerges.size)
        val canonicalMerge = unit.graph.valueMerges.single()
        assertEquals(mergeNode.id, unit.executionPlan.nodes[1].id)
        assertEquals(2, canonicalMerge.inputs.size)
        assertEquals(canonicalMerge.paths.toSet(), canonicalMerge.inputs.flatMap { it.paths }.toSet())
        assertTrue(CanonicalExecutionGraphValidator.validate(
            org.flowlang.compiler.CanonicalExecutionGraphBuild(unit.graph, unit.authorization.bindings)
        ).valid)
    }

    @Test
    fun flowSourceSyntaxCompilesExplicitMergeThroughProductionFrontend() {
        val source = File.createTempFile("ar-02b-explicit-merge", ".flow")
        try {
            source.writeText(
                """
                use module "shell" version "1.0"

                flow "ar-02b-source" {
                  input {
                    condition: boolean required
                  }
                  systems {
                    system "local" {
                      type: shell
                    }
                  }
                  steps {
                    if condition {
                      set left = "left"
                    } else {
                      set right = "right"
                    }
                    set joined = merge(left, right)
                    shell.run local {
                      command: joined
                    } -> consumed
                  }
                }
                """.trimIndent()
            )
            val result = FlowSourceFrontend(FlowCompilationService(modules)).compile(source)
            val unit = assertNotNull((result as? CompilationResult.Accepted)?.unit, result.toString())
            val canonicalMerge = unit.graph.valueMerges.single()
            assertEquals("joined", canonicalMerge.resultBinding)

            val mergeNode = unit.executionPlan.nodes
                .filterIsInstance<ControlNode>()
                .single { it.detail?.startsWith("joined = merge") == true }
            val consumerOutput = unit.executionPlan.outputs.single { it.name == "consumed" }
            assertTrue(
                unit.executionPlan.dependencyRelations.any { relation ->
                    relation.sourceNodeId == mergeNode.id &&
                        relation.targetNodeId == consumerOutput.sourceNodeId &&
                        relation.kind == PlanDependencyKind.VALUE
                }
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun publicBuilderRejectsMergePlanWithoutPathAwareProducerContracts() {
        val plan = FlowPlanner(modules).plan(mergeFlow(listOf("left", "right")))
        val failure = assertFailsWith<IllegalArgumentException> {
            CanonicalExecutionGraphBuilder.build(plan)
        }
        assertTrue(failure.message.orEmpty().contains("path-aware merge contracts"))
    }

    @Test
    fun partiallyKnownInputTypesDoNotInventAMergeResultType() {
        val document = flow(
            IfNode(
                condition = ref("condition"),
                then = listOf(SetNode(name = "left", value = text("left"))),
                otherwise = listOf(shell("right", text("runtime")))
            ),
            merge("joined", "left", "right"),
            shell("consumer", ref("joined"))
        )
        val analysis = FlowAvailabilityAnalyzer().analyze(document)
        assertEquals(null, analysis.merges.single().valueType)
        assertTrue(FlowValidator(modules).validate(document).valid)
        assertEquals(null, compile(document, "merge-partial-type").graph.valueMerges.single().valueType)
    }

    @Test
    fun moduleNamedMergeDoesNotCollideWithExplicitMergeEvidenceNamespace() {
        val registry = ModuleRegistry(
            mapOf(
                "merge" to FlowModule(
                    name = "merge",
                    version = "1.0",
                    systemTypes = mapOf("local" to SystemTypeContract("local")),
                    actions = mapOf(
                        "produce" to ModuleActionContract(
                            name = "produce",
                            targetTypes = setOf("local")
                        ),
                        "consume" to ModuleActionContract(
                            name = "consume",
                            targetTypes = setOf("local"),
                            input = mapOf("value" to SchemaField("any", required = true))
                        )
                    )
                )
            )
        )
        val document = FlowDocument(
            imports = listOf(ModuleImportNode(name = "merge", version = "1.0")),
            flow = FlowNode(
                name = "merge-module-namespace",
                systems = listOf(SystemNode(name = "local", systemType = "local")),
                steps = listOf(
                    ActionNode(
                        module = "merge",
                        action = "produce",
                        target = ref("local"),
                        result = ResultBindingNode(name = "value")
                    ),
                    ActionNode(
                        module = "merge",
                        action = "consume",
                        target = ref("local"),
                        params = mapOf("value" to ref("value")),
                        result = ResultBindingNode(name = "consumed")
                    )
                )
            )
        )
        val result = FlowCompilationService(registry).compile(
            FlowSourceCompilationInput(
                source = CompilationSource.fromBytes(
                    frontend = CompilationFrontend.FLOW_SOURCE,
                    identity = "module-named-merge",
                    bytes = "module-named-merge".toByteArray()
                ),
                ast = document
            )
        )
        val unit = assertNotNull((result as? CompilationResult.Accepted)?.unit, result.toString())
        assertTrue(unit.graph.valueMerges.isEmpty())
        val consumer = unit.executionPlan.nodes[1] as TaskNode
        assertTrue(unit.executionPlan.dependencyRelations.any { relation ->
            relation.targetNodeId == consumer.id &&
                relation.kind == PlanDependencyKind.VALUE &&
                relation.evidenceReference == "merge.consume.params.value"
        })
    }

    @Test
    fun mergeArgumentStorageOrderDoesNotChangeCanonicalMeaningOrDigest() {
        val forward = compile(mergeFlow(listOf("left", "right")), "merge-forward")
        val reversed = compile(mergeFlow(listOf("right", "left")), "merge-reversed")

        assertEquals(forward.graph, reversed.graph)
        assertEquals(forward.graphDigest, reversed.graphDigest)
        assertNotEquals(
            (forward.executionPlan.nodes[1] as ControlNode).detail,
            (reversed.executionPlan.nodes[1] as ControlNode).detail
        )
    }

    @Test
    fun implicitSameNameJoinRemainsAmbiguousAndCannotBorrowMergeSemantics() {
        val document = flow(
            IfNode(
                condition = ref("condition"),
                then = listOf(SetNode(name = "value", value = text("then"))),
                otherwise = listOf(SetNode(name = "value", value = text("otherwise")))
            ),
            shell("consumer", ref("value"))
        )
        val analysis = FlowAvailabilityAnalyzer().analyze(document)
        val state = analysis.bindingBefore(FlowStatementPath.flowStep(1), "value")
        assertEquals(FlowValueAvailability.MAYBE_DEFINED, state.availability)
        assertEquals(FlowAvailabilityReason.AMBIGUOUS_PRODUCERS, state.reason)
        assertTrue(analysis.merges.isEmpty())

        val validation = FlowValidator(modules).validate(document)
        assertFalse(validation.valid)
        assertTrue(validation.issues.any { it.code == "VALUE_PRODUCER_AMBIGUOUS" })
        assertFailsWith<UnsafeFlowAvailabilityException> { FlowPlanner(modules).plan(document) }
    }

    @Test
    fun mergeRejectsIncompleteOverlapExternalAmbiguityAndKnownTypeConflict() {
        val overlap = flow(
            IfNode(
                condition = ref("condition"),
                then = listOf(
                    SetNode(name = "left", value = text("left")),
                    SetNode(name = "alsoLeft", value = text("also"))
                ),
                otherwise = listOf(SetNode(name = "right", value = text("right")))
            ),
            merge("joined", "left", "alsoLeft")
        )
        val overlapReport = FlowValidator(modules).validate(overlap)
        assertFalse(overlapReport.valid)
        assertTrue(overlapReport.issues.any { it.code == "MERGE_PATH_OVERLAP" })
        assertTrue(overlapReport.issues.any { it.code == "MERGE_PATH_INCOMPLETE" })

        val external = flow(merge("joined", "condition", "otherInput"), extraInput = true)
        val externalReport = FlowValidator(modules).validate(external)
        assertFalse(externalReport.valid)
        assertTrue(externalReport.issues.any { it.code == "MERGE_SOURCE_EXTERNAL" })

        val incompatible = mergeFlow(
            inputs = listOf("left", "right"),
            leftValue = text("left"),
            rightValue = NumberLiteralNode(value = 42.0, isInteger = true)
        )
        val incompatibleReport = FlowValidator(modules).validate(incompatible)
        assertFalse(incompatibleReport.valid)
        assertTrue(incompatibleReport.issues.any { it.code == "MERGE_TYPE_INCOMPATIBLE" })
    }

    @Test
    fun mergeFunctionIsReservedForTypedSetBoundary() {
        val document = flow(
            shell(
                "consumer",
                CallExpressionNode(function = "merge", args = listOf(ref("condition"), ref("condition")))
            )
        )
        val report = FlowValidator(modules).validate(document)
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "MERGE_CONTEXT_INVALID" })
        assertFailsWith<UnsafeFlowAvailabilityException> { FlowPlanner(modules).plan(document) }
    }

    @Test
    fun continuityAndOrderingResolveFromTheMergeNodeRatherThanAnArbitraryArm() {
        val registry = continuityRegistry()
        val document = FlowDocument(
            imports = listOf(ModuleImportNode(name = "merge-test", version = "1.0")),
            flow = FlowNode(
                name = "merge-continuity",
                input = listOf(InputNode(name = "condition", valueType = ValueTypeNode(kind = "boolean"), required = true)),
                systems = listOf(SystemNode(name = "local", systemType = "local")),
                steps = listOf(
                    IfNode(
                        condition = ref("condition"),
                        then = listOf(producer("left")),
                        otherwise = listOf(producer("right"))
                    ),
                    merge("joined", "left", "right"),
                    ActionNode(
                        module = "merge-test",
                        action = "consume",
                        target = ref("local"),
                        params = mapOf("value" to ref("joined")),
                        dependsOn = listOf("joined"),
                        result = ResultBindingNode(name = "consumed")
                    )
                )
            )
        )

        val report = FlowValidator(registry).validate(document)
        assertTrue(report.valid, report.issues.toString())
        val plan = FlowPlanner(registry).plan(document)
        val mergeNode = plan.nodes[1]
        val consumer = plan.nodes[2] as TaskNode
        val continuity = plan.dependencyRelations.single {
            it.targetNodeId == consumer.id &&
                it.kind == PlanDependencyKind.VALUE &&
                it.evidence == PlanDependencyEvidence.MODULE_CONTRACT
        }
        assertEquals(PlanDependencyResolution.RESOLVED, continuity.resolution)
        assertEquals(mergeNode.id, continuity.sourceNodeId)
        assertTrue(plan.dependencyRelations.any {
            it.sourceNodeId == mergeNode.id &&
                it.targetNodeId == consumer.id &&
                it.kind == PlanDependencyKind.ORDERING
        })
    }

    @Test
    fun canonicalMergeMutationChangesDigestAndMissingTypedEdgeFailsGraphValidation() {
        val unit = compile(mergeFlow(listOf("left", "right")), "merge-mutation")
        val merge = unit.graph.valueMerges.single()
        val first = merge.inputs.first()
        val second = merge.inputs.last()
        val reassigned = merge.copy(
            inputs = listOf(
                first.copy(paths = second.paths),
                second.copy(paths = first.paths)
            )
        )
        val mutated = unit.graph.copy(valueMerges = listOf(reassigned))
        assertNotEquals(unit.graphDigest, CanonicalExecutionGraphDigestComputer.digest(mutated))

        val requiredEdge = unit.graph.dependencyEdges.first {
            it.targetNodeId == merge.targetNodeId &&
                it.kind == CanonicalDependencyKind.VALUE
        }
        val missingEdge = unit.graph.copy(
            dependencyEdges = unit.graph.dependencyEdges - requiredEdge
        )
        val report = CanonicalExecutionGraphValidator.validate(
            org.flowlang.compiler.CanonicalExecutionGraphBuild(missingEdge, unit.authorization.bindings)
        )
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "graph.merge.edge.value.missing" })

        val forgedType = unit.graph.copy(
            valueMerges = listOf(merge.copy(valueType = CanonicalValueTypeId("number")))
        )
        val typeReport = CanonicalExecutionGraphValidator.validate(
            org.flowlang.compiler.CanonicalExecutionGraphBuild(forgedType, unit.authorization.bindings)
        )
        assertFalse(typeReport.valid)
        assertTrue(typeReport.issues.any { it.code == "graph.merge.type.mismatch" })

        val forgedBinding = unit.graph.copy(
            valueMerges = listOf(merge.copy(resultBinding = "forged"))
        )
        val bindingReport = CanonicalExecutionGraphValidator.validate(
            org.flowlang.compiler.CanonicalExecutionGraphBuild(forgedBinding, unit.authorization.bindings)
        )
        assertFalse(bindingReport.valid)
        assertTrue(bindingReport.issues.any { it.code == "graph.merge.target.detail.mismatch" })
    }

    private fun compile(document: FlowDocument, identity: String): org.flowlang.compiler.CompilationUnit {
        val bytes = identity.toByteArray()
        val result = FlowCompilationService(modules).compile(
            FlowSourceCompilationInput(
                source = CompilationSource.fromBytes(
                    frontend = CompilationFrontend.FLOW_SOURCE,
                    identity = identity,
                    bytes = bytes
                ),
                ast = document
            )
        )
        val accepted = result as? CompilationResult.Accepted
        assertNotNull(accepted, result.toString())
        return accepted.unit
    }

    private fun mergeFlow(
        inputs: List<String>,
        leftValue: org.flowlang.ast.ExpressionNode = text("left"),
        rightValue: org.flowlang.ast.ExpressionNode = text("right")
    ): FlowDocument = flow(
        IfNode(
            condition = ref("condition"),
            then = listOf(SetNode(name = "left", value = leftValue)),
            otherwise = listOf(SetNode(name = "right", value = rightValue))
        ),
        SetNode(
            name = "joined",
            value = CallExpressionNode(function = "merge", args = inputs.map(::ref))
        ),
        shell("consumer", ref("joined"))
    )

    private fun flow(vararg steps: StatementNode, extraInput: Boolean = false): FlowDocument = FlowDocument(
        imports = listOf(ModuleImportNode(name = "shell", version = "1.0")),
        flow = FlowNode(
            name = "ar-02b-merge",
            input = buildList {
                add(InputNode(name = "condition", valueType = ValueTypeNode(kind = "boolean"), required = true))
                if (extraInput) add(InputNode(name = "otherInput", valueType = ValueTypeNode(kind = "boolean"), required = true))
            },
            systems = listOf(SystemNode(name = "local", systemType = "shell")),
            steps = steps.toList()
        )
    )

    private fun merge(name: String, vararg inputs: String): SetNode = SetNode(
        name = name,
        value = CallExpressionNode(function = "merge", args = inputs.map(::ref))
    )

    private fun shell(result: String, command: org.flowlang.ast.ExpressionNode): ActionNode = ActionNode(
        module = "shell",
        action = "run",
        target = ref("local"),
        params = mapOf("command" to command),
        result = ResultBindingNode(name = result)
    )

    private fun producer(result: String): ActionNode = ActionNode(
        module = "merge-test",
        action = "produce",
        target = ref("local"),
        result = ResultBindingNode(name = result)
    )

    private fun continuityRegistry(): ModuleRegistry = ModuleRegistry(
        mapOf(
            "merge-test" to FlowModule(
                name = "merge-test",
                version = "1.0",
                systemTypes = mapOf("local" to SystemTypeContract("local")),
                actions = mapOf(
                    "produce" to ModuleActionContract(
                        name = "produce",
                        targetTypes = setOf("local"),
                        continuity = ContinuityContract(
                            provides = listOf(ContinuityChannel(ContinuityKind.VALUE, "payload"))
                        )
                    ),
                    "consume" to ModuleActionContract(
                        name = "consume",
                        targetTypes = setOf("local"),
                        input = mapOf("value" to SchemaField("any", required = true)),
                        continuity = ContinuityContract(
                            requires = listOf(ContinuityChannel(ContinuityKind.VALUE, "payload"))
                        )
                    )
                )
            )
        )
    )

    private fun ref(name: String): ReferenceNode = ReferenceNode(path = listOf(name))
    private fun text(value: String): StringLiteralNode = StringLiteralNode(value = value)
}
