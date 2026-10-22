package org.flowlang.core

import org.flowlang.ast.AggregateNode
import org.flowlang.ast.StatementNode
import org.flowlang.ast.TransformNode
import org.flowlang.ast.ValidateNode

/** One admission rule until source data-operation payloads have a canonical contract. */
internal object DataOperationPayloadSupport {
    const val CODE = "DATA_OPERATION_PAYLOAD_UNSUPPORTED"

    fun rejection(statement: StatementNode): Rejection? {
        val operation: String
        val fields: List<String>
        when (statement) {
            is TransformNode -> {
                operation = "transform"
                fields = buildList {
                    if (statement.where != null) add("where")
                    if (statement.select.isNotEmpty()) add("select")
                }
            }
            is AggregateNode -> {
                operation = "aggregate"
                fields = if (statement.fields.isEmpty()) emptyList() else listOf("fields")
            }
            is ValidateNode -> {
                operation = "validate"
                fields = if (statement.rules.isEmpty()) emptyList() else listOf("rules")
            }
            else -> return null
        }
        return fields.takeIf { it.isNotEmpty() }?.let { Rejection(operation, it) }
    }

    fun requireSupported(statement: StatementNode) {
        rejection(statement)?.let {
            throw UnsupportedDataOperationPayloadException(it.operation, it.fields, it.message)
        }
    }

    data class Rejection(val operation: String, val fields: List<String>) {
        val message: String =
            "Data operation '$operation' has unsupported payload fields: ${fields.joinToString(", ")}. " +
                "The execution plan cannot yet preserve them. Compilation is blocked to prevent " +
                "silently discarding authored data-operation meaning."
    }
}

/** Direct planning cannot discard payloads even when validation has been skipped. */
class UnsupportedDataOperationPayloadException internal constructor(
    val operationName: String,
    val unsupportedFields: List<String>,
    message: String
) : IllegalStateException(message) {
    val code: String = DataOperationPayloadSupport.CODE
}
