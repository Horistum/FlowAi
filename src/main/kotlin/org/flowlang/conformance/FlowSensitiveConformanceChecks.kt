package org.flowlang.conformance

import java.io.File
import org.flowlang.ast.ActionNode
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.FlowNode
import org.flowlang.ast.ForNode
import org.flowlang.ast.IfNode
import org.flowlang.ast.InputNode
import org.flowlang.ast.ModuleImportNode
import org.flowlang.ast.ParallelBranchNode
import org.flowlang.ast.ParallelNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.ast.ResultBindingNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.ast.SystemNode
import org.flowlang.ast.ValueTypeNode
import org.flowlang.core.FlowAvailabilityAnalyzer
import org.flowlang.core.FlowAvailabilityReason
import org.flowlang.core.FlowStatementPath
import org.flowlang.core.FlowValueAvailability
import org.flowlang.core.UnsafeFlowAvailabilityException
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.TaskNode
import org.flowlang.validator.FlowValidator

/** Independent executable guardrail for the AR-02A data-flow cutover. */
class FlowSensitiveConformanceChecks(
    private val rootDir: File
) {
    fun checks(): List<ConformanceCheck> = listOf(
        resultCheck(SINGLE_OWNER_CHECK, runCatching(::singleOwnerErrors)),
        resultCheck(LATTICE_CHECK, runCatching(::latticeErrors)),
        resultCheck(REJECTION_CHECK, runCatching(::rejectionErrors))
    )

    private fun singleOwnerErrors(): List<String> = buildList {
        val compiler = read("src/main/kotlin/org/flowlang/compiler/FlowCompilationService.kt")
        val validator = read("src/main/kotlin/org/flowlang/validator/FlowValidator.kt")
        val planner = read("src/main/kotlin/org/flowlang/planner/FlowPlanner.kt")
        val model = read("src/main/kotlin/org/flowlang/core/FlowAvailabilityModel.kt")

        val analysisCalls = Regex(Regex.escape("flowAvailabilityAnalyzer.analyze(ast)")).findAll(compiler).count()
        if (analysisCalls != 1) {
            add("FlowCompilationService must compute exactly one shared availability result, found $analysisCalls calls.")
        }
        if ("flowValidator.validate(ast, availability)" !in compiler) {
            add("FlowCompilationService does not pass the shared availability result to FlowValidator.")
        }
        if ("flowPlanner.planWithProvenance(ast, availability)" !in compiler) {
            add("FlowCompilationService does not pass the shared availability result to FlowPlanner.")
        }
        if ("validate(document, FlowAvailabilityAnalyzer().analyze(document))" !in validator) {
            add("The direct FlowValidator boundary does not delegate through FlowAvailabilityAnalyzer.")
        }
        if ("planWithProvenance(document, FlowAvailabilityAnalyzer().analyze(document)).plan" !in planner) {
            add("The direct FlowPlanner boundary does not enforce the shared availability invariant.")
        }
        if ("val results = mutableMapOf<String, String>()" in planner || "ctx.results" in planner) {
            add("FlowPlanner still contains a mutable name-to-last-node producer authority.")
        }
        if ("mutableMapOf<FlowProducerIdentity, String>()" !in planner) {
            add("FlowPlanner does not bind plan nodes to typed FlowProducerIdentity values.")
        }
        if ("val workflow: FlowWorkflowIdentity" !in model) {
            add("Flow statement paths do not carry explicit workflow identity.")
        }
    }

    private fun latticeErrors(): List<String> = buildList {
        val analyzer = FlowAvailabilityAnalyzer()
        val straight = analyzer.analyze(
            flow(
                shell("value"),
                shell("consumer", ref("value"))
            )
        )
        val straightState = straight.bindingBefore(FlowStatementPath.flowStep(1), "value")
        if (straightState.availability != FlowValueAvailability.DEFINITELY_DEFINED || straightState.producers.size != 1) {
            add("A straight-line producer is not DefinitelyDefined with one producer.")
        }

        val partial = analyzer.analyze(
            flow(
                IfNode(condition = ref("condition"), then = listOf(shell("value"))),
                shell("consumer", ref("value"))
            )
        ).bindingBefore(FlowStatementPath.flowStep(1), "value")
        if (
            partial.availability != FlowValueAvailability.MAYBE_DEFINED ||
            partial.reason != FlowAvailabilityReason.PARTIAL_PATHS
        ) {
            add("An if-arm-only producer is not classified as MaybeDefined/PARTIAL_PATHS.")
        }

        val ambiguous = analyzer.analyze(
            flow(
                IfNode(
                    condition = ref("condition"),
                    then = listOf(shell("value", literal("then"))),
                    otherwise = listOf(shell("value", literal("otherwise")))
                ),
                shell("consumer", ref("value"))
            )
        ).bindingBefore(FlowStatementPath.flowStep(1), "value")
        if (
            ambiguous.availability != FlowValueAvailability.MAYBE_DEFINED ||
            ambiguous.reason != FlowAvailabilityReason.AMBIGUOUS_PRODUCERS ||
            ambiguous.producers.size != 2
        ) {
            add("Equal names from exclusive arms do not retain two ambiguous producer identities.")
        }

        val parallelLocal = analyzer.analyze(
            flow(
                ParallelNode(
                    branches = listOf(
                        ParallelBranchNode(name = "left", steps = listOf(shell("leftValue"))),
                        ParallelBranchNode(name = "right", steps = listOf(shell("rightValue")))
                    )
                ),
                shell("consumer", ref("leftValue"))
            )
        ).bindingBefore(FlowStatementPath.flowStep(1), "leftValue")
        if (
            parallelLocal.availability != FlowValueAvailability.MAYBE_DEFINED ||
            parallelLocal.reason != FlowAvailabilityReason.PARTIAL_PATHS
        ) {
            add("A parallel branch-local producer leaked into the parent availability state.")
        }

        val loopLocal = analyzer.analyze(
            flow(
                ForNode(item = "item", source = ref("items"), body = listOf(shell("loopValue"))),
                shell("consumer", ref("loopValue"))
            )
        ).bindingBefore(FlowStatementPath.flowStep(1), "loopValue")
        if (loopLocal.availability != FlowValueAvailability.MAYBE_DEFINED) {
            add("A possibly empty loop incorrectly publishes its body producer as definitely available.")
        }

        if (FlowValueAvailability.entries.toSet() != setOf(
                FlowValueAvailability.UNDEFINED,
                FlowValueAvailability.MAYBE_DEFINED,
                FlowValueAvailability.DEFINITELY_DEFINED,
                FlowValueAvailability.MERGED
            )
        ) {
            add("The AR-02 availability lattice does not expose the activated four states.")
        }
    }

    private fun rejectionErrors(): List<String> = buildList {
        val modules = ModuleRegistry.fromDirectory(File(rootDir, "modules"))
        val unsafe = flow(
            IfNode(condition = ref("condition"), then = listOf(shell("value"))),
            shell("consumer", ref("value"))
        )
        val validation = FlowValidator(modules).validate(unsafe)
        if (validation.valid || validation.issues.none { it.code == "VALUE_MAY_BE_UNDEFINED" }) {
            add("FlowValidator did not reject an unguarded MaybeDefined read with the stable AR-02 code.")
        }
        val plannerFailure = runCatching { FlowPlanner(modules).plan(unsafe) }.exceptionOrNull()
        if (plannerFailure !is UnsafeFlowAvailabilityException) {
            add("Direct FlowPlanner invocation bypassed the MaybeDefined read gate: ${plannerFailure?.javaClass?.name}.")
        }

        val branchLocal = flow(
            IfNode(
                condition = ref("condition"),
                then = listOf(shell("value", literal("then")), shell("thenConsumer", ref("value"))),
                otherwise = listOf(shell("value", literal("otherwise")), shell("elseConsumer", ref("value")))
            )
        )
        val localValidation = FlowValidator(modules).validate(branchLocal)
        if (!localValidation.valid) {
            add("Branch-local definitely-defined reads were rejected: ${localValidation.issues}.")
            return@buildList
        }
        val condition = FlowPlanner(modules).plan(branchLocal).nodes.singleOrNull() as? ConditionNode
        val thenProducer = condition?.then?.getOrNull(0) as? TaskNode
        val thenConsumer = condition?.then?.getOrNull(1) as? TaskNode
        val otherwiseProducer = condition?.otherwise?.getOrNull(0) as? TaskNode
        val otherwiseConsumer = condition?.otherwise?.getOrNull(1) as? TaskNode
        if (thenProducer == null || thenConsumer?.dependsOn != listOf(thenProducer.id)) {
            add("The then arm did not resolve its own typed producer.")
        }
        if (otherwiseProducer == null || otherwiseConsumer?.dependsOn != listOf(otherwiseProducer.id)) {
            add("The otherwise arm did not resolve its own typed producer.")
        }
        if (thenConsumer?.dependsOn?.contains(otherwiseProducer?.id) == true) {
            add("The then arm borrowed the producer selected while visiting the otherwise arm.")
        }
    }

    private fun resultCheck(name: String, result: Result<List<String>>): ConformanceCheck {
        val errors = result.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }
        return ConformanceCheck(
            name = name,
            passed = errors.isEmpty(),
            message = errors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
        )
    }

    private fun read(path: String): String {
        val file = File(rootDir, path)
        require(file.isFile) { "AR-02 conformance source is missing: $path" }
        return file.readText()
    }

    private fun flow(vararg steps: org.flowlang.ast.StatementNode): FlowDocument = FlowDocument(
        imports = listOf(ModuleImportNode(name = "shell", version = "1.0")),
        flow = FlowNode(
            name = "ar-02-conformance",
            input = listOf(
                InputNode(name = "condition", valueType = ValueTypeNode(kind = "boolean"), required = true),
                InputNode(name = "items", valueType = ValueTypeNode(kind = "list"), required = false)
            ),
            systems = listOf(SystemNode(name = "local", systemType = "shell")),
            steps = steps.toList()
        )
    )

    private fun shell(
        result: String,
        command: org.flowlang.ast.ExpressionNode = literal("work")
    ): ActionNode = ActionNode(
        module = "shell",
        action = "run",
        target = ref("local"),
        params = mapOf("command" to command),
        result = ResultBindingNode(name = result)
    )

    private fun ref(name: String): ReferenceNode = ReferenceNode(path = listOf(name))

    private fun literal(value: String): StringLiteralNode = StringLiteralNode(value = value)

    companion object {
        const val SINGLE_OWNER_CHECK = "architecture-recovery.ar-02.flow-analysis-single-owner"
        const val LATTICE_CHECK = "architecture-recovery.ar-02.value-availability-lattice"
        const val REJECTION_CHECK = "architecture-recovery.ar-02.maybe-defined-read-rejection"
    }
}
