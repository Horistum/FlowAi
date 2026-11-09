package org.flowlang.intent

/** Shared, bounded source traversal. This is an input inventory, never an execution graph. */
object IntentStructure {
    const val VERSION = org.flowlang.standard.FlowStandardVersions.STRUCTURED_INTENT_VERSION
    const val RECOVERY_VERSION = org.flowlang.standard.FlowStandardVersions.RECOVERY_INTENT_VERSION
    fun isStructured(version: String?) = org.flowlang.standard.FlowStandardVersions.isStructuredIntentVersion(version)
    const val MAX_DEPTH = 64
    const val MAX_STATEMENTS = 2000
    data class Guard(val branch: String, val positive: Boolean)
    data class Visit(val statement: IntentStatement, val path: String, val guards: List<Guard>, val retryDepth: Int = 0)

    fun visits(workflow: IntentWorkflow): List<Visit> {
        if (workflow.body == null) return workflow.steps.mapIndexed { index, step ->
            Visit(IntentOperation(step), "steps[$index]", emptyList())
        }
        val result = mutableListOf<Visit>()
        fun walk(body: List<IntentStatement>, path: String, guards: List<Guard>, depth: Int, retryDepth: Int = 0) {
            require(depth <= MAX_DEPTH) { "INTENT_STRUCTURE_LIMIT: nesting exceeds $MAX_DEPTH" }
            for ((index, statement) in body.withIndex()) {
                require(result.size < MAX_STATEMENTS) { "INTENT_STRUCTURE_LIMIT: statement count exceeds $MAX_STATEMENTS" }
                val location = "$path[$index]"
                result += Visit(statement, location, guards, retryDepth)
                if (statement is IntentBranch) {
                    walk(statement.then, "$location.then", guards + Guard(statement.id, true), depth + 1, retryDepth)
                    walk(statement.otherwise, "$location.otherwise", guards + Guard(statement.id, false), depth + 1, retryDepth)
                } else if (statement is IntentAttempt) {
                    walk(statement.body, "$location.body", guards + Guard(statement.id, true), depth + 1, retryDepth)
                    walk(statement.onFailure, "$location.onFailure", guards + Guard(statement.id, false), depth + 1, retryDepth)
                } else if (statement is IntentRetry) {
                    walk(statement.body, "$location.body", guards, depth + 1, retryDepth + 1)
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
        if (intent.intentVersion !in setOf("2.0", VERSION, RECOVERY_VERSION)) error("UNSUPPORTED_INTENT_VERSION", "Unsupported Intent version.")
        if (isStructured(intent.intentVersion)) {
            if (intent.workflows.isEmpty()) error("STRUCTURED_WORKFLOW_REQUIRED", "Structured Intent requires at least one workflow.")
            if (intent.failure.notify || intent.failure.rollback) error("STRUCTURED_FAILURE_POLICY_UNSUPPORTED", "Use explicit Intent 3.1 failure regions; legacy global recovery flags are not admitted.")
            if (intent.policies.any { it.type == IntentPolicyType.APPROVAL && !it.condition.isNullOrBlank() })
                error("STRUCTURED_IMPLICIT_APPROVAL_GUARD", "Place conditional approval in an explicit branch instead of a global approval condition.")
        }
        var count = 0
        intent.workflows.forEach { workflow ->
            if ((isStructured(intent.intentVersion)) != (workflow.body != null) || (workflow.body != null && workflow.steps.isNotEmpty())) {
                error("INTENT_STRUCTURE_VERSION_MISMATCH", "Structured Intent requires body; Intent 2.0 requires steps. Representations cannot be mixed.")
                return@forEach
            }
            if (workflow.body == null) return@forEach
            val visits = visits(workflow)
            count += visits.size
            require(count <= MAX_STATEMENTS) { "INTENT_STRUCTURE_LIMIT: document statement count exceeds $MAX_STATEMENTS" }
            val preceding = linkedMapOf<String, Visit>()
            val attempts = visits.filter { it.statement is IntentAttempt }.map { it.statement.id }.toSet()
            for (visit in visits) {
                val statement = visit.statement
                if (visit.guards.any { !it.positive && it.branch in attempts }) {
                    val bindings = when (statement) {
                        is IntentOperation -> listOf(statement.id.replace('-', '_')) + statement.operation.produces
                        is IntentLocalValue, is IntentMerge -> listOf(statement.id.replace('-', '_'))
                        else -> emptyList()
                    }
                    if ("error" in bindings) error("FAILURE_ERROR_BINDING_RESERVED", "Failure handlers cannot replace their original error binding.")
                }
                if (statement is IntentAttempt || statement is IntentRetry) {
                    if (intent.intentVersion != RECOVERY_VERSION) error("RECOVERY_INTENT_VERSION_REQUIRED", "Failure regions and retries require Intent 3.1.")
                    if (visit.retryDepth > 0) error("NESTED_RETRY_CONTROL_UNSUPPORTED", "Retry cannot contain another retry or a failure region.")
                }
                if (statement is IntentAttempt && (statement.body.isEmpty() || statement.onFailure.isEmpty()))
                    error("EMPTY_FAILURE_REGION", "An attempt requires nonempty protected and failure bodies.")
                if (statement is IntentRetry && (statement.maxAttempts !in 1..100 || statement.delaySeconds !in 0..3600 || statement.body.isEmpty()))
                    error("INVALID_RETRY_POLICY", "Retry requires a nonempty body, 1..100 total attempts and 0..3600 seconds fixed delay.")
                if (visit.retryDepth > 0 && statement is IntentOperation) {
                    val effects = org.flowlang.effects.CanonicalIntentEffectAuthority.effectsFor(statement.operation.capability,
                        CanonicalIntentMeaningAuthority.semanticParameters(statement.operation))
                    if (effects.isEmpty() || effects.any { it.operation != org.flowlang.effects.EffectOperation.READ })
                        error("RETRY_REQUIRES_READ_ONLY_OPERATION", "Retry requires known read-only semantic effects; changing operations need a future replay-safety contract.")
                }
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
