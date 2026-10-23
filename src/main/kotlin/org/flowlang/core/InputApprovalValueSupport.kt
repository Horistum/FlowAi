package org.flowlang.core

import org.flowlang.ast.*

/** Admission for values carried by existing input and approval plan contracts. */
internal object InputApprovalValueSupport {
    // These aliases already identify the same families in availability analysis.
    private const val MAX_DEFAULT_VALUES = 10_000
    private const val MAX_DEFAULT_DEPTH = 128

    private val kinds = mapOf(
        "text" to "text", "string" to "text",
        "number" to "number", "int" to "number", "integer" to "number", "float" to "number", "double" to "number",
        "boolean" to "boolean", "bool" to "boolean",
        "list" to "list", "array" to "list",
        "map" to "map", "object" to "map", "json" to "map", "yaml" to "map",
        "secret" to "secret", "any" to "any", "option" to "option"
    )

    fun rejections(input: InputNode): List<Rejection> = buildList {
        fun reject(code: String, message: String) = add(Rejection(code, message, input.sourceLocation))
        val kind = kinds[input.valueType.kind]
        if (kind == null) reject("INPUT_TYPE_UNSUPPORTED", "Input '${input.name}' has an unsupported type '${input.valueType.kind}'.")
        val choices = input.valueType.values
        if (kind != "option" && choices.isNotEmpty()) {
            reject("INPUT_OPTIONS_UNEXPECTED", "Input '${input.name}' declares choices on a non-option type.")
        }
        if (kind == "option") {
            if (choices.isEmpty()) reject("INPUT_OPTIONS_EMPTY", "Option input '${input.name}' requires at least one string choice.")
            if (choices.any { it !is StringLiteralNode || it.value.isBlank() }) {
                reject("INPUT_OPTION_VALUE_UNSUPPORTED", "Option input '${input.name}' accepts only non-blank literal string choices.")
            }
        }
        val value = input.default ?: return@buildList
        if (!isStaticValue(value)) {
            reject("INPUT_DEFAULT_UNSUPPORTED", "Input '${input.name}' requires a finite literal default or secret reference; dynamic initialization is unsupported.")
            return@buildList
        }
        val matches = when (kind) {
            "text" -> value is StringLiteralNode
            "number" -> isNumber(value)
            "boolean" -> value is BooleanLiteralNode
            "list" -> value is ListLiteralNode
            "map" -> value is MapLiteralNode
            "secret" -> value is SecretRefNode
            "any" -> true
            "option" -> value is StringLiteralNode
            else -> true // The unknown type already has an error.
        }
        if (!matches) reject("INPUT_DEFAULT_TYPE_MISMATCH", "Default for input '${input.name}' does not match its declared type '${input.valueType.kind}'.")
        if (kind == "option" && value is StringLiteralNode && choices.all { it is StringLiteralNode } &&
            choices.none { (it as StringLiteralNode).value == value.value }) {
            reject("INPUT_DEFAULT_NOT_IN_OPTION", "Default for input '${input.name}' is outside its declared option domain.")
        }
    }

    fun rejections(approval: ApproveNode): List<Rejection> = buildList {
        approval.params.keys.filter { it != "message" }.forEach {
            add(Rejection("APPROVAL_PARAM_UNSUPPORTED", "Approval parameter '$it' has no canonical representation. Only 'message' is supported.", approval.sourceLocation))
        }
        approval.params["message"]?.let {
            if ((it !is StringLiteralNode && it !is TemplateStringNode) || (it is StringLiteralNode && it.value.isBlank())) {
                add(Rejection("APPROVAL_MESSAGE_TYPE_MISMATCH", "Approval message must be a non-blank string literal or string template.", approval.sourceLocation))
            }
        }
    }

    fun requireSupported(input: InputNode) = requireSupported(rejections(input))
    fun requireSupported(approval: ApproveNode) = requireSupported(rejections(approval))
    private fun requireSupported(issues: List<Rejection>) {
        issues.firstOrNull()?.let { throw InvalidInputApprovalValueException(it.code, it.sourceLocation, it.message) }
    }

    private fun isNumber(value: ExpressionNode): Boolean = when (value) {
        is NumberLiteralNode -> value.value.isFinite() && (!value.isInteger ||
            (value.value >= Long.MIN_VALUE.toDouble() && value.value < Long.MAX_VALUE.toDouble() && value.value % 1.0 == 0.0))
        is UnaryExpressionNode -> value.operator in setOf("-", "+") && value.operand is NumberLiteralNode && isNumber(value.operand)
        else -> false
    }

    // Bounded iteration also protects direct typed-AST callers, outside parser limits.
    private fun isStaticValue(root: ExpressionNode): Boolean {
        val pending = ArrayDeque<Pair<ExpressionNode, Int>>()
        pending.add(root to 0)
        var visited = 0
        while (pending.isNotEmpty()) {
            val (value, depth) = pending.removeLast()
            if (++visited > MAX_DEFAULT_VALUES || depth > MAX_DEFAULT_DEPTH) return false
            when (value) {
                is StringLiteralNode, is BooleanLiteralNode, is NullLiteralNode, is SecretRefNode -> Unit
                is NumberLiteralNode, is UnaryExpressionNode -> if (!isNumber(value)) return false
                is ListLiteralNode -> {
                    if (value.items.size > MAX_DEFAULT_VALUES - visited - pending.size) return false
                    value.items.forEach { pending.add(it to depth + 1) }
                }
                is MapLiteralNode -> {
                    if (value.entries.size > MAX_DEFAULT_VALUES - visited - pending.size) return false
                    value.entries.values.forEach { pending.add(it to depth + 1) }
                }
                else -> return false
            }
        }
        return true
    }

    data class Rejection(val code: String, val message: String, val sourceLocation: SourceLocation?)
}

/** Direct planning must enforce the same value contract as compilation. */
class InvalidInputApprovalValueException internal constructor(
    val code: String,
    val sourceLocation: SourceLocation?,
    message: String
) : IllegalStateException(message)
