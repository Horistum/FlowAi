package org.flowlang.conformance

import java.io.File
import org.flowlang.ast.ActionNode
import org.flowlang.ast.CallExpressionNode
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.FlowNode
import org.flowlang.ast.IfNode
import org.flowlang.ast.InputNode
import org.flowlang.ast.ModuleImportNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.ast.ResultBindingNode
import org.flowlang.ast.SetNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.ast.SystemNode
import org.flowlang.ast.ValueTypeNode
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.CompilationSource
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.FlowSourceCompilationInput
import org.flowlang.core.FlowAvailabilityAnalyzer
import org.flowlang.core.FlowAvailabilityReason
import org.flowlang.core.FlowStatementPath
import org.flowlang.core.FlowValueAvailability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ControlNode
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.validator.FlowValidator

/** Independent executable AR-02B evidence, not a source scan pretending to be behavior. */
class Ar02ExplicitMergeConformanceChecks(private val rootDir: File) {
    fun checks(): List<ConformanceCheck> = listOf(
        resultCheck(MERGE_CHECK, runCatching(::mergeErrors)),
        resultCheck(PERMUTATION_CHECK, runCatching(::permutationErrors)),
        resultCheck(CONTRACT_CHECK, runCatching(::contractErrors))
    )

    private fun mergeErrors(): List<String> = buildList {
        val modules = ModuleRegistry.fromDirectory(File(rootDir, "modules"))
        val document = fixture(listOf("left", "right"))
        val analysis = FlowAvailabilityAnalyzer().analyze(document)
        val merge = analysis.merges.singleOrNull()
        val state = analysis.stateAfter(FlowStatementPath.flowStep(1)).binding("joined")
        if (merge == null || state.availability != FlowValueAvailability.MERGED ||
            state.reason != FlowAvailabilityReason.EXPLICIT_MERGE || state.uniqueProducer != merge.producer
        ) {
            add("Exhaustive explicit merge did not produce one Merged producer identity.")
            return@buildList
        }
        if (merge.paths != merge.incoming.flatMap { it.paths }.toSet()) {
            add("Explicit merge path coverage is not exhaustive and exact.")
        }
        val validation = FlowValidator(modules).validate(document)
        if (!validation.valid) add("Valid explicit merge was rejected: ${validation.issues}.")
        val plan = FlowPlanner(modules).plan(document)
        val mergeNode = plan.nodes.getOrNull(1)
        if (mergeNode !is ControlNode) add("Explicit merge did not become one graph-addressable control node.")
        val mergeValueEdges = plan.dependencyRelations.filter {
            it.targetNodeId == mergeNode?.id && it.kind == PlanDependencyKind.VALUE
        }
        if (mergeValueEdges.size != 2) add("Explicit merge did not retain both incoming VALUE edges.")
        val unit = compile(modules, document, "ar02b-conformance")
        if (unit.graph.valueMerges.size != 1) add("Canonical graph does not own the typed merge contract.")
    }

    private fun permutationErrors(): List<String> = buildList {
        val modules = ModuleRegistry.fromDirectory(File(rootDir, "modules"))
        val forward = compile(modules, fixture(listOf("left", "right")), "ar02b-forward")
        val reverse = compile(modules, fixture(listOf("right", "left")), "ar02b-reverse")
        if (forward.graph != reverse.graph) {
            add("Merge input collection order changed canonical graph meaning.")
        }
        if (forward.graphDigest != reverse.graphDigest) {
            add("Merge input collection order changed canonical graph digest.")
        }
    }

    private fun contractErrors(): List<String> = buildList {
        val model = read("src/main/kotlin/org/flowlang/core/FlowAvailabilityModel.kt")
        val analyzer = read("src/main/kotlin/org/flowlang/core/FlowAvailabilityAnalyzer.kt")
        val graph = read("src/main/kotlin/org/flowlang/compiler/CanonicalExecutionGraph.kt")
        val planner = read("src/main/kotlin/org/flowlang/planner/FlowPlanner.kt")
        val versions = read("src/main/kotlin/org/flowlang/standard/FlowStandardVersions.kt")
        if ("data class FlowMergeContract" !in model || "val merges: List<FlowMergeContract>" !in model) {
            add("Typed merge contract is absent from the shared analysis product.")
        }
        if ("statement.value as? CallExpressionNode" !in analyzer || "MERGE_PATH_INCOMPLETE" !in analyzer) {
            add("The existing typed set/call construct does not enforce explicit merge semantics.")
        }
        if ("val valueMerges: List<CanonicalValueMerge>" !in graph) {
            add("CanonicalExecutionGraph does not own merge meaning.")
        }
        if ("planWithProvenance" !in planner || "mutableMapOf<FlowProducerIdentity, String>()" !in planner) {
            add("Planner does not expose path-aware producer bindings to canonical construction.")
        }
        if ("AST_VERSION = \"2.2\"" !in versions || "EXECUTION_PLAN_VERSION = \"2.4\"" !in versions) {
            add("AR-02B silently changed a public artifact version despite using an existing typed construct.")
        }
        val modules = ModuleRegistry.fromDirectory(File(rootDir, "modules"))
        val mergePlan = FlowPlanner(modules).plan(fixture(listOf("left", "right")))
        if (runCatching { org.flowlang.compiler.CanonicalExecutionGraphBuilder.build(mergePlan) }.isSuccess) {
            add("The public legacy graph builder accepted an explicit merge without path-aware producer contracts.")
        }
    }

    private fun compile(
        modules: ModuleRegistry,
        document: FlowDocument,
        identity: String
    ): org.flowlang.compiler.CompilationUnit {
        val result = FlowCompilationService(modules).compile(
            FlowSourceCompilationInput(
                source = CompilationSource.fromBytes(
                    CompilationFrontend.FLOW_SOURCE,
                    identity,
                    identity.toByteArray()
                ),
                ast = document
            )
        )
        return (result as? CompilationResult.Accepted)?.unit
            ?: error("AR-02B fixture compilation was rejected: $result")
    }

    private fun fixture(order: List<String>): FlowDocument = FlowDocument(
        imports = listOf(ModuleImportNode(name = "shell", version = "1.0")),
        flow = FlowNode(
            name = "ar-02b-conformance",
            input = listOf(InputNode(name = "condition", valueType = ValueTypeNode(kind = "boolean"), required = true)),
            systems = listOf(SystemNode(name = "local", systemType = "shell")),
            steps = listOf(
                IfNode(
                    condition = ref("condition"),
                    then = listOf(SetNode(name = "left", value = StringLiteralNode(value = "left"))),
                    otherwise = listOf(SetNode(name = "right", value = StringLiteralNode(value = "right")))
                ),
                SetNode(name = "joined", value = CallExpressionNode(function = "merge", args = order.map(::ref))),
                ActionNode(
                    module = "shell",
                    action = "run",
                    target = ref("local"),
                    params = mapOf("command" to ref("joined")),
                    result = ResultBindingNode(name = "consumer")
                )
            )
        )
    )

    private fun read(path: String): String = File(rootDir, path).readText()
    private fun ref(name: String): ReferenceNode = ReferenceNode(path = listOf(name))

    private fun resultCheck(name: String, result: Result<List<String>>): ConformanceCheck {
        val errors = result.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }
        return ConformanceCheck(name, errors.isEmpty(), errors.takeIf { it.isNotEmpty() }?.joinToString(" | "))
    }

    companion object {
        const val MERGE_CHECK = "architecture-recovery.ar-02.explicit-merge-integrity"
        const val PERMUTATION_CHECK = "architecture-recovery.ar-02.producer-permutation-invariance"
        const val CONTRACT_CHECK = "architecture-recovery.ar-02.public-contract-migration-integrity"
    }
}
