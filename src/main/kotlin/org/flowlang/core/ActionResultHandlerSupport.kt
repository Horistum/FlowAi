package org.flowlang.core

import org.flowlang.ast.ActionNode
import org.flowlang.ast.SourceLocation

/** Shared admission rule until action result handlers have a canonical planning contract. */
internal object ActionResultHandlerSupport {
    const val CODE = "ACTION_RESULT_HANDLER_UNSUPPORTED"

    fun rejectionMessage(action: ActionNode): String? =
        if (action.handler?.rules.isNullOrEmpty()) null else
            "Action '${action.module}.${action.action}' has result-handler rules " +
                "(expect/when/when error) that cannot yet be preserved in the execution plan. " +
                "Compilation is blocked to prevent silently discarding those rules."

    fun requireSupported(action: ActionNode) {
        rejectionMessage(action)?.let { message ->
            throw UnsupportedActionResultHandlerException(action.module, action.action, action.sourceLocation, message)
        }
    }
}

/** Direct planning must fail even when the caller has skipped Flow validation. */
class UnsupportedActionResultHandlerException internal constructor(
    val moduleName: String,
    val actionName: String,
    val sourceLocation: SourceLocation?,
    message: String
) : IllegalStateException(message) {
    val code: String = ActionResultHandlerSupport.CODE
}
