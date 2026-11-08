package org.flowlang.intent

import org.flowlang.ast.*
import org.flowlang.identity.SemanticId
import org.flowlang.lowering.IntentExpressionParser
import org.flowlang.lowering.IntentValueExpressionLowering
import org.flowlang.modules.ModuleCatalog
import org.flowlang.modules.SchemaType

/** A bounded Boolean subset; neither nullable truthiness nor arbitrary expressions are certified. */
object StructuredBooleanPredicate {
    fun admitted(value: ExpressionNode, booleanReferences: Set<List<String>>, depth: Int = 0): Boolean {
        if (depth > IntentStructure.MAX_DEPTH) return false
        return when (value) {
            is BooleanLiteralNode -> true
            is ReferenceNode -> !value.safe && value.scope == "auto" && value.path in booleanReferences
            is UnaryExpressionNode -> value.operator == "not" && admitted(value.operand, booleanReferences, depth + 1)
            is LogicalExpressionNode -> value.operator in setOf("and", "or") && value.operands.isNotEmpty() &&
                value.operands.all { admitted(it, booleanReferences, depth + 1) }
            is BinaryExpressionNode -> value.operator in setOf("and", "or") &&
                admitted(value.left, booleanReferences, depth + 1) && admitted(value.right, booleanReferences, depth + 1)
            else -> false
        }
    }
}

internal object StructuredIntentPredicates {
    fun validate(intent: IntentDocument, bindings: Map<String, IntentBindingEvidence>, registry: ModuleCatalog, expressions: IntentExpressionParser) {
        if (!IntentStructure.isStructured(intent.intentVersion)) return
        val inputs = intent.inputs.filter { it.required && it.type == "boolean" && (it.default == null || it.default is IntentBoolean) }
            .map { listOf(it.name) }.toSet()
        intent.workflows.forEach { workflow ->
            val booleans = inputs.toMutableSet()
            IntentStructure.visits(workflow).forEach { visit ->
                fun lower(value: IntentValue) = IntentValueExpressionLowering.lower(value, expressions)
                fun symbol(id: String) = listOf(IntentStepIdentity.resultSymbol(SemanticId.of(id)))
                when (val statement = visit.statement) {
                    is IntentAttempt, is IntentRetry -> Unit
                    is IntentBranch -> require(StructuredBooleanPredicate.admitted(lower(statement.condition), booleans)) {
                        "STRUCTURED_BOOLEAN_REQUIRED: ${workflow.name}.${visit.path}.when requires a non-null Boolean contract and an admitted predicate."
                    }
                    is IntentLocalValue -> if (StructuredBooleanPredicate.admitted(lower(statement.value), booleans)) booleans += symbol(statement.id)
                    is IntentMerge -> if (statement.sources.all { it.path in booleans }) booleans += symbol(statement.id)
                    is IntentOperation -> {
                        val binding = bindings.getValue(statement.id)
                        if (binding.status == IntentBindingStatus.RESOLVED) {
                            val output = registry.findAction(requireNotNull(binding.module), requireNotNull(binding.action))?.output.orEmpty()
                            output.filterValues { it.type == SchemaType.BOOLEAN && it.required }.keys.forEach { field ->
                                booleans += symbol(statement.id) + field
                            }
                        }
                    }
                }
            }
        }
    }
}
