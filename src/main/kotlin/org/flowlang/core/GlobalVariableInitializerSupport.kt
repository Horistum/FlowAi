package org.flowlang.core

import org.flowlang.ast.FlowDocument
import org.flowlang.ast.SourceLocation

/** Global initialization has no canonical value/lifetime contract yet. */
internal object GlobalVariableInitializerSupport {
    const val CODE = "GLOBAL_VARIABLE_INITIALIZER_UNSUPPORTED"

    fun rejections(document: FlowDocument): List<Rejection> = document.flow.vars.map {
        Rejection(it.name, it.sourceLocation)
    }

    fun requireSupported(document: FlowDocument) {
        rejections(document).firstOrNull()?.let {
            throw UnsupportedGlobalVariableInitializerException(it.variableName, it.sourceLocation, it.message)
        }
    }

    data class Rejection(val variableName: String, val sourceLocation: SourceLocation?) {
        val message: String =
            "Global variable '$variableName' has an unsupported initializer. " +
                "The execution plan cannot yet preserve global initialization and value lifetime. " +
                "All global variable declarations are rejected, including unused or shadowed declarations."
    }
}

/** Direct AST callers cannot silently discard global definitions by skipping validation. */
class UnsupportedGlobalVariableInitializerException internal constructor(
    val variableName: String,
    val sourceLocation: SourceLocation?,
    message: String
) : IllegalStateException(message) {
    val code: String = GlobalVariableInitializerSupport.CODE
}
