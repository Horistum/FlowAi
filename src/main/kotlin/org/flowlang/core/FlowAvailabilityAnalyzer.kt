package org.flowlang.core

import org.flowlang.ast.ActionNode
import org.flowlang.ast.AggregateNode
import org.flowlang.ast.ApproveNode
import org.flowlang.ast.BinaryExpressionNode
import org.flowlang.ast.BooleanLiteralNode
import org.flowlang.ast.CallExpressionNode
import org.flowlang.ast.IdentifierLiteralNode
import org.flowlang.ast.NullLiteralNode
import org.flowlang.ast.NumberLiteralNode
import org.flowlang.ast.SecretRefNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.ast.ErrorHandlerNode
import org.flowlang.ast.ExpectNode
import org.flowlang.ast.ExpressionNode
import org.flowlang.ast.FailNode
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.ForNode
import org.flowlang.ast.IfNode
import org.flowlang.ast.IndexExpressionNode
import org.flowlang.ast.ListLiteralNode
import org.flowlang.ast.LogicalExpressionNode
import org.flowlang.ast.MapLiteralNode
import org.flowlang.ast.MatchNode
import org.flowlang.ast.MemberExpressionNode
import org.flowlang.ast.ParallelNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.ast.RetryNode
import org.flowlang.ast.SetNode
import org.flowlang.ast.SkipNode
import org.flowlang.ast.SourceLocation
import org.flowlang.ast.StatementNode
import org.flowlang.ast.TemplateStringNode
import org.flowlang.ast.TransformNode
import org.flowlang.ast.TryNode
import org.flowlang.ast.UnaryExpressionNode
import org.flowlang.ast.UnaryPostfixExpressionNode
import org.flowlang.ast.ValidateNode
import org.flowlang.ast.WhenNode

/**
 * Builds one path-sensitive data-flow product for both validator and planner.
 * Joins are deterministic and immutable; no branch mutates sibling state.
 */
class FlowAvailabilityAnalyzer {
    fun analyze(document: FlowDocument): FlowAvailabilityAnalysis =
        analyze(document, FlowWorkflowIdentity.Main)

    internal fun analyze(
        document: FlowDocument,
        workflow: FlowWorkflowIdentity
    ): FlowAvailabilityAnalysis {
        val builder = Builder(document, workflow)
        return builder.analyze()
    }

    private class Builder(
        private val document: FlowDocument,
        private val workflow: FlowWorkflowIdentity
    ) {
        private val entries = linkedMapOf<FlowStatementPath, FlowAvailabilityState>()
        private val exits = linkedMapOf<FlowStatementPath, FlowAvailabilityState>()
        private val produced = linkedMapOf<ProducerKey, FlowProducerIdentity>()
        private val uses = mutableListOf<FlowAvailabilityUse>()
        private val issues = mutableListOf<FlowAvailabilityIssue>()
        private val merges = mutableListOf<FlowMergeContract>()

        fun analyze(): FlowAvailabilityAnalysis {
            var base = FlowAvailabilityState()
            document.flow.input.forEach { input ->
                base = base.withExternal(input.name, FlowValueType.fromWire(input.valueType.kind))
            }
            document.flow.vars.map { it.name }.distinct().forEach { name -> base = base.withExternal(name) }
            document.flow.systems.map { it.name }.distinct().forEach { name -> base = base.withExternal(name) }

            document.flow.vars.forEachIndexed { index, variable ->
                val path = FlowStatementPath.variable(index, workflow)
                entries[path] = base
                inspectExpression(variable.value, base, path, "variable '${variable.name}'")
                exits[path] = base
            }

            var normal = base
            document.flow.steps.forEachIndexed { index, statement ->
                normal = analyzeStatement(statement, normal, FlowStatementPath.flowStep(index, workflow))
            }

            document.flow.errorHandler?.steps?.let { statements ->
                analyzeStatements(statements, base.withExternal("error")) { index ->
                    FlowStatementPath.globalErrorStep(index, workflow)
                }
            }

            return FlowAvailabilityAnalysis(
                entryStates = entries.toMap(),
                exitStates = exits.toMap(),
                producedBindings = produced.toMap(),
                merges = merges.sortedWith(
                    compareBy({ it.identity.joinPath.workflow.value }, { it.identity.joinPath.value }, { it.identity.resultBinding })
                ),
                normalExitState = normal,
                uses = uses.toList(),
                issues = issues.distinctBy { issue ->
                    listOf(issue.code, issue.binding, issue.path.value, issue.location?.line, issue.location?.column)
                }
            )
        }

        private fun analyzeStatements(
            statements: List<StatementNode>,
            initial: FlowAvailabilityState,
            path: (Int) -> FlowStatementPath
        ): FlowAvailabilityState {
            var state = initial
            statements.forEachIndexed { index, statement ->
                state = analyzeStatement(statement, state, path(index))
            }
            return state
        }

        private fun analyzeStatement(
            statement: StatementNode,
            input: FlowAvailabilityState,
            path: FlowStatementPath
        ): FlowAvailabilityState {
            entries[path] = input
            val output = when (statement) {
                is ActionNode -> analyzeAction(statement, input, path)
                is IfNode -> analyzeIf(statement, input, path)
                is ForNode -> analyzeFor(statement, input, path)
                is ParallelNode -> analyzeParallel(statement, input, path)
                is MatchNode -> analyzeMatch(statement, input, path)
                is RetryNode -> analyzeStatements(statement.steps, input) { index -> path.child("body", index) }
                is TryNode -> analyzeTry(statement, input, path)
                is ApproveNode -> analyzeApprove(statement, input, path)
                is TransformNode -> {
                    inspectExpression(statement.source, input, path, "transform source")
                    val itemState = input.withExternal("item")
                    statement.where?.let { inspectExpression(it, itemState, path, "transform predicate", allowUndefined = true) }
                    statement.select.values.forEach { inspectExpression(it, itemState, path, "transform projection", allowUndefined = true) }
                    define(input, path, statement.target)
                }
                is AggregateNode -> {
                    inspectExpression(statement.source, input, path, "aggregate source")
                    val itemState = input.withExternal("item")
                    statement.fields.values.forEach { inspectExpression(it, itemState, path, "aggregate field", allowUndefined = true) }
                    define(input, path, statement.target)
                }
                is ValidateNode -> {
                    inspectExpression(statement.target, input, path, "validate target")
                    val itemState = input.withExternal("item")
                    statement.rules.forEach { rule ->
                        rule.reference?.let { inspectExpression(it, itemState, path, "validate rule", allowUndefined = true) }
                        rule.expression?.let { inspectExpression(it, itemState, path, "validate rule", allowUndefined = true) }
                    }
                    input
                }
                is SetNode -> {
                    val merge = statement.value as? CallExpressionNode
                    if (merge?.function == "merge") {
                        analyzeMerge(statement, merge, input, path)
                    } else {
                        inspectExpression(statement.value, input, path, "set value")
                        define(input, path, statement.name, inferValueType(statement.value, input))
                    }
                }
                is FailNode -> {
                    inspectExpression(statement.message, input, path, "fail message")
                    input.unreachable()
                }
                is SkipNode -> {
                    inspectExpression(statement.message, input, path, "skip message")
                    input
                }
                is ExpectNode -> {
                    val implicit = withImplicitResult(input)
                    statement.expressions.forEach { inspectExpression(it, implicit, path, "expect expression", allowUndefined = true) }
                    input
                }
                is ErrorHandlerNode -> {
                    val handlerInput = input.withExternal("error")
                    analyzeStatements(statement.steps, handlerInput) { index -> path.child("steps", index) }
                    input
                }
            }
            exits[path] = output
            return output
        }

        private fun analyzeAction(
            action: ActionNode,
            input: FlowAvailabilityState,
            path: FlowStatementPath
        ): FlowAvailabilityState {
            inspectExpression(action.target, input, path, "action target")
            action.params.values.forEach { inspectExpression(it, input, path, "action parameter") }
            action.safety?.condition?.let { inspectExpression(it, input, path, "action safety condition") }
            action.dependsOn.forEach { dependency ->
                inspectBinding(
                    dependency,
                    input,
                    path,
                    action.sourceLocation,
                    "declared dependency",
                    allowUniquePartialProducer = true
                )
            }

            val outputNames = (listOfNotNull(action.result?.name) + action.declaredOutputs).distinct()
            var output = input
            outputNames.forEach { name ->
                output = define(output, path, name)
            }

            action.handler?.rules?.forEachIndexed { ruleIndex, rule ->
                when (rule) {
                    is ExpectNode -> {
                        val implicit = withImplicitResult(output)
                        rule.expressions.forEach { inspectExpression(it, implicit, path, "result expectation", allowUndefined = true) }
                    }
                    is WhenNode -> {
                        val implicit = withImplicitResult(output).let { state ->
                            if (rule.isError) state.withExternal("error") else state
                        }
                        rule.condition?.let { inspectExpression(it, implicit, path, "result handler condition", allowUndefined = true) }
                        analyzeStatements(rule.steps, implicit) { index ->
                            path.child("handler.rules[$ruleIndex].steps", index)
                        }
                    }
                }
            }
            return output
        }

        private fun analyzeApprove(
            approve: ApproveNode,
            input: FlowAvailabilityState,
            path: FlowStatementPath
        ): FlowAvailabilityState {
            approve.params.values.forEach { inspectExpression(it, input, path, "approval parameter") }
            approve.dependsOn.forEach { dependency ->
                inspectBinding(
                    dependency,
                    input,
                    path,
                    approve.sourceLocation,
                    "declared dependency",
                    allowUniquePartialProducer = true
                )
            }
            var output = input
            (listOfNotNull(approve.result?.name) + approve.declaredOutputs)
                .distinct()
                .forEach { name -> output = define(output, path, name) }
            return output
        }

        private fun analyzeIf(
            statement: IfNode,
            input: FlowAvailabilityState,
            path: FlowStatementPath
        ): FlowAvailabilityState {
            val condition = analyzeCondition(statement.condition, input, path)
            val thenInput = condition.whenTrue.enterAlternative(path, "if:true")
            val otherwiseInput = condition.whenFalse.enterAlternative(path, "if:false")
            val thenState = analyzeStatements(statement.then, thenInput) { index -> path.child("then", index) }
            val otherwiseState = analyzeStatements(statement.otherwise, otherwiseInput) { index -> path.child("otherwise", index) }
            return joinAlternatives(listOf(thenState, otherwiseState))
        }

        private fun analyzeFor(
            statement: ForNode,
            input: FlowAvailabilityState,
            path: FlowStatementPath
        ): FlowAvailabilityState {
            inspectExpression(statement.source, input, path, "loop source")
            val zero = input.enterAlternative(path, "loop:zero")
            val bodyBase = input.enterAlternative(path, "loop:body")
            val originalItem = bodyBase.binding(statement.item)
            val bodyInput = bodyBase.withExternal(statement.item)
            val bodyOutput = analyzeStatements(statement.body, bodyInput) { index -> path.child("body", index) }
                .restore(statement.item, originalItem)
            // A dynamic loop may execute zero times.
            return joinAlternatives(listOf(zero, bodyOutput))
        }

        private fun analyzeParallel(
            statement: ParallelNode,
            input: FlowAvailabilityState,
            path: FlowStatementPath
        ): FlowAvailabilityState {
            val branchOutputs = statement.branches.mapIndexed { branchIndex, branch ->
                val branchIdentity = branch.name?.let { "parallel:name:$it" } ?: "parallel:index:$branchIndex"
                val branchInput = input.enterAlternative(path, branchIdentity, mergeable = false)
                analyzeStatements(branch.steps, branchInput) { index ->
                    path.child("branches[$branchIndex].steps", index)
                }
            }
            return joinConcurrent(input, branchOutputs)
        }

        private fun analyzeMatch(
            statement: MatchNode,
            input: FlowAvailabilityState,
            path: FlowStatementPath
        ): FlowAvailabilityState {
            inspectExpression(statement.source, input, path, "match source")
            val implicit = withImplicitResult(input)
            val alternatives = mutableListOf<FlowAvailabilityState>()
            statement.cases.forEachIndexed { caseIndex, matchCase ->
                matchCase.condition?.let { inspectExpression(it, implicit, path, "match condition", allowUndefined = true) }
                val caseInput = input.enterAlternative(path, "match:case:$caseIndex")
                alternatives += analyzeStatements(matchCase.steps, caseInput) { index ->
                    path.child("cases[$caseIndex].steps", index)
                }
            }
            statement.errorCase?.let { errorSteps ->
                val errorBase = input.enterAlternative(path, "match:error")
                val originalError = errorBase.binding("error")
                alternatives += analyzeStatements(errorSteps, errorBase.withExternal("error")) { index ->
                    path.child("errorCase", index)
                }.restore("error", originalError)
            }
            val defaultInput = input.enterAlternative(path, "match:default")
            alternatives += analyzeStatements(statement.defaultSteps, defaultInput) { index -> path.child("default", index) }
            return joinAlternatives(alternatives)
        }

        private fun analyzeTry(
            statement: TryNode,
            input: FlowAvailabilityState,
            path: FlowStatementPath
        ): FlowAvailabilityState {
            val successInput = input.enterAlternative(path, "try:success")
            val success = analyzeStatements(statement.steps, successInput) { index -> path.child("body", index) }
            val failureBase = input.enterAlternative(path, "try:error")
            val originalError = failureBase.binding("error")
            val handledFailure = analyzeStatements(statement.errorHandler.steps, failureBase.withExternal("error")) { index ->
                path.child("errorHandler", index)
            }.restore("error", originalError)
            return joinAlternatives(listOf(success, handledFailure))
        }

        private fun analyzeCondition(
            expression: ExpressionNode,
            input: FlowAvailabilityState,
            path: FlowStatementPath
        ): ConditionStates = when (expression) {
            is UnaryPostfixExpressionNode -> {
                val operand = expression.operand
                if (expression.operator == "exists" && operand is ReferenceNode) {
                    val reference = operand
                    val binding = reference.path.firstOrNull()
                    if (binding == null) {
                        ConditionStates(input, input)
                    } else {
                        inspectBinding(
                            binding,
                            input,
                            path,
                            reference.location,
                            "existence guard",
                            allowUniquePartialProducer = true
                        )
                        ConditionStates(
                            whenTrue = refinePresent(input, binding),
                            whenFalse = refineAbsent(input, binding)
                        )
                    }
                } else {
                    inspectExpression(expression, input, path, "condition")
                    ConditionStates(input, input)
                }
            }
            is UnaryExpressionNode -> {
                if (expression.operator == "not") {
                    val nested = analyzeCondition(expression.operand, input, path)
                    ConditionStates(nested.whenFalse, nested.whenTrue)
                } else {
                    inspectExpression(expression, input, path, "condition")
                    ConditionStates(input, input)
                }
            }
            is LogicalExpressionNode -> when (expression.operator) {
                "and" -> {
                    var trueState = input
                    val falseStates = mutableListOf<FlowAvailabilityState>()
                    expression.operands.forEach { operand ->
                        val nested = analyzeCondition(operand, trueState, path)
                        falseStates += nested.whenFalse
                        trueState = nested.whenTrue
                    }
                    ConditionStates(trueState, joinAlternatives(falseStates))
                }
                "or" -> {
                    var falseState = input
                    val trueStates = mutableListOf<FlowAvailabilityState>()
                    expression.operands.forEach { operand ->
                        val nested = analyzeCondition(operand, falseState, path)
                        trueStates += nested.whenTrue
                        falseState = nested.whenFalse
                    }
                    ConditionStates(joinAlternatives(trueStates), falseState)
                }
                else -> {
                    inspectExpression(expression, input, path, "condition")
                    ConditionStates(input, input)
                }
            }
            else -> {
                inspectExpression(expression, input, path, "condition")
                ConditionStates(input, input)
            }
        }

        private fun inspectExpression(
            expression: ExpressionNode,
            state: FlowAvailabilityState,
            path: FlowStatementPath,
            role: String,
            allowUndefined: Boolean = false
        ) {
            when (expression) {
                is ReferenceNode -> expression.path.firstOrNull()?.let { binding ->
                    inspectBinding(binding, state, path, expression.location, role, allowUndefined = allowUndefined)
                }
                is BinaryExpressionNode -> {
                    inspectExpression(expression.left, state, path, role, allowUndefined)
                    inspectExpression(expression.right, state, path, role, allowUndefined)
                }
                is UnaryExpressionNode -> inspectExpression(expression.operand, state, path, role, allowUndefined)
                is UnaryPostfixExpressionNode -> inspectExpression(expression.operand, state, path, role, allowUndefined)
                is LogicalExpressionNode -> expression.operands.forEach { inspectExpression(it, state, path, role, allowUndefined) }
                is ListLiteralNode -> expression.items.forEach { inspectExpression(it, state, path, role, allowUndefined) }
                is MapLiteralNode -> expression.entries.values.forEach { inspectExpression(it, state, path, role, allowUndefined) }
                is TemplateStringNode -> expression.parts.forEach { inspectExpression(it, state, path, role, allowUndefined) }
                is CallExpressionNode -> {
                    if (expression.function == "merge") {
                        issues += mergeIssue(
                            code = "MERGE_CONTEXT_INVALID",
                            message = "merge(...) is only valid as the complete value of a set statement.",
                            binding = "<merge>",
                            path = path
                        )
                    }
                    expression.args.forEach { inspectExpression(it, state, path, role, allowUndefined) }
                }
                is IndexExpressionNode -> {
                    inspectExpression(expression.target, state, path, role, allowUndefined)
                    inspectExpression(expression.index, state, path, role, allowUndefined)
                }
                is MemberExpressionNode -> inspectExpression(expression.target, state, path, role, allowUndefined)
                else -> Unit
            }
        }

        private fun inspectBinding(
            rawBinding: String,
            state: FlowAvailabilityState,
            path: FlowStatementPath,
            location: SourceLocation?,
            role: String,
            allowUniquePartialProducer: Boolean = false,
            allowUndefined: Boolean = false
        ) {
            val binding = rawBinding.replace('-', '_')
            val bindingState = state.binding(binding)
            val accepted = !state.reachable || bindingState.safeToRead ||
                (allowUndefined && bindingState.availability == FlowValueAvailability.UNDEFINED) ||
                (allowUniquePartialProducer &&
                    bindingState.availability == FlowValueAvailability.MAYBE_DEFINED &&
                    bindingState.reason == FlowAvailabilityReason.PARTIAL_PATHS &&
                    bindingState.uniqueProducer != null)
            uses += FlowAvailabilityUse(
                binding = binding,
                path = path,
                location = location,
                role = role,
                state = bindingState,
                accepted = accepted
            )
            if (!accepted) issues += issueForState(binding, bindingState, path, location, role)
        }

        private fun analyzeMerge(
            statement: SetNode,
            expression: CallExpressionNode,
            input: FlowAvailabilityState,
            path: FlowStatementPath
        ): FlowAvailabilityState {
            if (!input.reachable) return define(input, path, statement.name, null)
            val firstIssue = issues.size
            if (expression.args.size < 2) {
                issues += mergeIssue(
                    "MERGE_ARITY_INVALID",
                    "Explicit merge '${statement.name}' requires at least two source references.",
                    statement.name,
                    path
                )
            }
            val inputs = mutableListOf<FlowMergeInput>()
            val seen = mutableSetOf<String>()
            expression.args.forEachIndexed { index, argument ->
                val reference = argument as? ReferenceNode
                val rawBinding = reference?.takeIf { it.scope == "auto" && it.path.size == 1 }?.path?.singleOrNull()
                if (rawBinding == null) {
                    issues += mergeIssue(
                        "MERGE_INPUT_INVALID",
                        "Explicit merge '${statement.name}' input $index must be one simple binding reference.",
                        statement.name,
                        path,
                        reference?.location
                    )
                    return@forEachIndexed
                }
                val binding = rawBinding.replace('-', '_')
                if (!seen.add(binding)) {
                    issues += mergeIssue(
                        "MERGE_INPUT_DUPLICATE",
                        "Explicit merge '${statement.name}' repeats logical input '$rawBinding'.",
                        binding,
                        path,
                        reference.location
                    )
                    return@forEachIndexed
                }
                val state = input.binding(binding)
                val producer = state.uniqueProducer
                val accepted = state.availability != FlowValueAvailability.UNDEFINED && !state.external && producer != null
                uses += FlowAvailabilityUse(
                    binding = binding,
                    path = path,
                    location = reference.location,
                    role = "explicit merge input",
                    state = state,
                    accepted = accepted
                )
                when {
                    state.availability == FlowValueAvailability.UNDEFINED -> issues += mergeIssue(
                        "MERGE_SOURCE_UNDEFINED",
                        "Explicit merge '${statement.name}' references undefined input '$rawBinding'.",
                        binding,
                        path,
                        reference.location
                    )
                    state.external -> issues += mergeIssue(
                        "MERGE_SOURCE_EXTERNAL",
                        "Explicit merge '${statement.name}' input '$rawBinding' has external rather than path-local producer provenance.",
                        binding,
                        path,
                        reference.location
                    )
                    producer == null -> issues += mergeIssue(
                        "MERGE_SOURCE_AMBIGUOUS",
                        "Explicit merge '${statement.name}' input '$rawBinding' must identify exactly one producer.",
                        binding,
                        path,
                        reference.location
                    )
                    else -> {
                        val sourcePaths = state.producerPaths.getValue(producer)
                        if (sourcePaths.any { !it.mergeable }) {
                            issues += mergeIssue(
                                "MERGE_PATH_KIND_UNSUPPORTED",
                                "Explicit merge '${statement.name}' cannot use concurrent branch input '$rawBinding'.",
                                binding,
                                path,
                                reference.location
                            )
                        } else {
                            inputs += FlowMergeInput(binding, producer, sourcePaths, state.valueType)
                        }
                    }
                }
            }
            if (issues.size != firstIssue) return input

            val ownersByPath = input.paths.associateWith { incoming ->
                inputs.filter { incoming in it.paths }
            }
            val uncovered = ownersByPath.filterValues(List<FlowMergeInput>::isEmpty).keys
            val overlapping = ownersByPath.filterValues { it.size > 1 }.keys
            if (uncovered.isNotEmpty()) {
                issues += mergeIssue(
                    "MERGE_PATH_INCOMPLETE",
                    "Explicit merge '${statement.name}' does not cover paths: ${uncovered.map { it.value }.sorted().joinToString()}.",
                    statement.name,
                    path
                )
            }
            if (overlapping.isNotEmpty()) {
                issues += mergeIssue(
                    "MERGE_PATH_OVERLAP",
                    "Explicit merge '${statement.name}' selects more than one input on paths: ${overlapping.map { it.value }.sorted().joinToString()}.",
                    statement.name,
                    path
                )
            }
            val knownTypes = inputs.mapNotNull(FlowMergeInput::valueType).distinct()
            if (knownTypes.size > 1) {
                issues += mergeIssue(
                    "MERGE_TYPE_INCOMPATIBLE",
                    "Explicit merge '${statement.name}' has incompatible known types: ${knownTypes.map { it.value }.sorted().joinToString()}.",
                    statement.name,
                    path
                )
            }
            if (issues.size != firstIssue) return input

            val valueType = knownTypes.singleOrNull()
                ?.takeIf { candidate -> inputs.all { input -> input.valueType == candidate } }
            val contract = FlowMergeContract(
                identity = FlowMergeIdentity(path, statement.name),
                paths = input.paths,
                incoming = inputs.sortedWith(
                    compareBy({ it.binding }, { it.producer.statementPath.workflow.value }, { it.producer.statementPath.value })
                ),
                valueType = valueType
            )
            merges += contract
            bindingAliases(statement.name).forEach { alias -> produced[ProducerKey(path, alias)] = contract.producer }
            return input.withBinding(
                statement.name,
                FlowBindingState.merged(contract.producer, input.paths, valueType)
            )
        }

        private fun mergeIssue(
            code: String,
            message: String,
            binding: String,
            path: FlowStatementPath,
            location: SourceLocation? = null
        ): FlowAvailabilityIssue = FlowAvailabilityIssue(
            code = code,
            message = message,
            binding = binding,
            path = path,
            location = location,
            kind = FlowAvailabilityIssueKind.INVALID_MERGE
        )

        private fun inferValueType(expression: ExpressionNode, state: FlowAvailabilityState): FlowValueType? =
            when (expression) {
                is StringLiteralNode, is IdentifierLiteralNode, is TemplateStringNode -> FlowValueType("text")
                is NumberLiteralNode -> FlowValueType("number")
                is BooleanLiteralNode -> FlowValueType("boolean")
                is NullLiteralNode -> FlowValueType("null")
                is ListLiteralNode -> FlowValueType("list")
                is MapLiteralNode -> FlowValueType("map")
                is SecretRefNode -> FlowValueType("secret")
                is ReferenceNode -> expression.path.firstOrNull()?.let { state.binding(it).valueType }
                is BinaryExpressionNode, is LogicalExpressionNode, is UnaryExpressionNode,
                is UnaryPostfixExpressionNode -> FlowValueType("boolean")
                else -> null
            }

        private fun define(
            input: FlowAvailabilityState,
            path: FlowStatementPath,
            rawBinding: String,
            valueType: FlowValueType? = null
        ): FlowAvailabilityState {
            val producer = FlowProducerIdentity(path, rawBinding)
            bindingAliases(rawBinding).forEach { alias -> produced[ProducerKey(path, alias)] = producer }
            if (!input.reachable) return input
            return input.withBinding(rawBinding, FlowBindingState.produced(producer, input.paths, valueType))
        }

        private fun refinePresent(input: FlowAvailabilityState, binding: String): FlowAvailabilityState {
            val current = input.binding(binding)
            return when {
                current.availability == FlowValueAvailability.UNDEFINED -> input.unreachable()
                else -> input.restrictTo(current.coveredPaths)
            }
        }

        private fun refineAbsent(input: FlowAvailabilityState, binding: String): FlowAvailabilityState {
            val current = input.binding(binding)
            return when {
                current.availability == FlowValueAvailability.UNDEFINED -> input
                else -> input.restrictTo(input.paths - current.coveredPaths)
            }
        }

        private fun withImplicitResult(input: FlowAvailabilityState): FlowAvailabilityState {
            var state = input
            IMPLICIT_RESULT_NAMES.forEach { name -> state = state.withExternal(name) }
            return state
        }
    }
}
