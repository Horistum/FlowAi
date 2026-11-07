package org.flowlang.intent

/** Shared, bounded source traversal. This is an input inventory, never an execution graph. */
object IntentStructure {
    const val VERSION = org.flowlang.standard.FlowStandardVersions.STRUCTURED_INTENT_VERSION
    const val MAX_DEPTH = 64
    const val MAX_STATEMENTS = 2000
    data class Guard(val branch: String, val positive: Boolean)
    data class Visit(val statement: IntentStatement, val path: String, val guards: List<Guard>)

    fun visits(workflow: IntentWorkflow): List<Visit> {
        if (workflow.body == null) return workflow.steps.mapIndexed { index, step ->
            Visit(IntentOperation(step), "steps[$index]", emptyList())
        }
        val result = mutableListOf<Visit>()
        fun walk(body: List<IntentStatement>, path: String, guards: List<Guard>, depth: Int) {
            require(depth <= MAX_DEPTH) { "INTENT_STRUCTURE_LIMIT: nesting exceeds $MAX_DEPTH" }
            for ((index, statement) in body.withIndex()) {
                require(result.size < MAX_STATEMENTS) { "INTENT_STRUCTURE_LIMIT: statement count exceeds $MAX_STATEMENTS" }
                val location = "$path[$index]"
                result += Visit(statement, location, guards)
                if (statement is IntentBranch) {
                    walk(statement.then, "$location.then", guards + Guard(statement.id, true), depth + 1)
                    walk(statement.otherwise, "$location.otherwise", guards + Guard(statement.id, false), depth + 1)
                }
            }
        }
        walk(workflow.body, "body", emptyList(), 0)
        return result
    }

    /** A predecessor must execute on every path to the protected operation. */
    fun covers(predecessor: Visit, consumer: Visit): Boolean =
        predecessor.guards.size <= consumer.guards.size &&
            consumer.guards.take(predecessor.guards.size) == predecessor.guards

    fun issues(intent: IntentDocument): List<IntentValidationIssue> = buildList {
        fun error(code: String, message: String) { add(IntentValidationIssue("error", code, message)) }
        if (intent.intentVersion !in setOf("2.0", VERSION)) error("UNSUPPORTED_INTENT_VERSION", "Unsupported Intent version.")
        if (intent.intentVersion == VERSION) {
            if (intent.workflows.isEmpty()) error("STRUCTURED_WORKFLOW_REQUIRED", "Intent 3.0 requires at least one workflow.")
            if (intent.failure.notify || intent.failure.rollback) error("STRUCTURED_FAILURE_POLICY_UNSUPPORTED", "Structured authoring requires explicit future failure-region support; legacy global recovery flags are not admitted.")
            if (intent.policies.any { it.type == IntentPolicyType.APPROVAL && !it.condition.isNullOrBlank() })
                error("STRUCTURED_IMPLICIT_APPROVAL_GUARD", "Place conditional approval in an explicit branch instead of a global approval condition.")
        }
        var count = 0
        intent.workflows.forEach { workflow ->
            if ((intent.intentVersion == VERSION) != (workflow.body != null) || (workflow.body != null && workflow.steps.isNotEmpty())) {
                error("INTENT_STRUCTURE_VERSION_MISMATCH", "Intent 3.0 requires body; Intent 2.0 requires steps. Representations cannot be mixed.")
                return@forEach
            }
            if (workflow.body == null) return@forEach
            val visits = visits(workflow)
            count += visits.size
            require(count <= MAX_STATEMENTS) { "INTENT_STRUCTURE_LIMIT: document statement count exceeds $MAX_STATEMENTS" }
            val preceding = linkedMapOf<String, Visit>()
            for (visit in visits) {
                val step = (visit.statement as? IntentOperation)?.operation
                step?.requires?.forEach { dependency ->
                    val prior = preceding[dependency]
                    if (prior == null || prior.statement !is IntentOperation ||
                        prior.guards.any { guard -> visit.guards.any { it.branch == guard.branch && it.positive != guard.positive } }) {
                        error("STRUCTURED_DEPENDENCY_SCOPE", "${workflow.name}.${visit.path}: dependency '$dependency' is forward, non-operation or belongs to a sibling alternative.")
                    }
                }
                preceding[visit.statement.id] = visit
            }
        }
    }
}
